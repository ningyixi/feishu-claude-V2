import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Offline H2 -> empty MySQL schema. Run through scripts/database.py; credentials are environment-only. */
public class H2ToMysql {
    record Col(String source, String target, int type, int size, int scale, boolean nullable, boolean identity, String defaultValue, Long nextId) {}
    record Idx(boolean unique, SortedMap<Integer,String> columns) {}
    record Fk(String target, SortedMap<Integer,String> local, SortedMap<Integer,String> remote, String update, String delete) {}
    record Table(String source, String target, List<Col> columns, List<String> pk, Map<String,Idx> indexes, Map<String,Fk> fks) {}
    static String required(String name) { String v=System.getenv(name); if(v==null || v.isBlank()) throw new IllegalArgumentException("Missing environment: " + name); return v; }
    static String env(String name, String fallback) { return System.getenv().getOrDefault(name,fallback); }
    static String q(String name) { return "`"+name.replace("`","``")+"`"; }
    static String h(String name) { return "\""+name.replace("\"","\"\"")+"\""; }
    static String lower(String name) { return name.toLowerCase(Locale.ROOT); }
    static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
    static String hash(byte[] bytes) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    static String names(Collection<String> columns) { return columns.stream().map(H2ToMysql::q).collect(java.util.stream.Collectors.joining(",")); }
    static void sql(Connection db, String statement) throws SQLException { try(var s=db.createStatement()) {s.execute(statement);} }

    static String sqlType(Col c) {
        return switch(c.type) {
            case Types.BOOLEAN, Types.BIT -> "BOOLEAN";
            case Types.TINYINT -> "TINYINT";
            case Types.SMALLINT -> "SMALLINT";
            case Types.INTEGER -> "INT";
            case Types.BIGINT -> "BIGINT";
            case Types.NUMERIC, Types.DECIMAL -> "DECIMAL("+Math.min(c.size,65)+","+c.scale+")";
            case Types.FLOAT, Types.DOUBLE -> "DOUBLE";
            case Types.REAL -> "FLOAT";
            case Types.CHAR, Types.NCHAR -> "CHAR("+c.size+")";
            case Types.VARCHAR, Types.NVARCHAR -> c.size<=16000 ? "VARCHAR("+c.size+")" : "LONGTEXT";
            case Types.LONGVARCHAR, Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB -> "LONGTEXT";
            case Types.BINARY, Types.VARBINARY -> c.size<=16000 ? "VARBINARY("+c.size+")" : "LONGBLOB";
            case Types.LONGVARBINARY, Types.BLOB -> "LONGBLOB";
            case Types.DATE -> "DATE";
            case Types.TIME -> "TIME(6)";
            case Types.TIMESTAMP -> "DATETIME(6)";
            default -> throw new IllegalArgumentException("Unsupported source type " + c.type + " for " + c.source + "; refusing lossy conversion");
        };
    }

    static List<Table> metadata(Connection source) throws SQLException {
        var meta=source.getMetaData(); List<Table> tables=new ArrayList<>(); Set<String> targetNames=new HashSet<>();
        try(var rows=meta.getTables(null,null,"%",new String[]{"TABLE","VIEW"})) {
            while(rows.next()) {
                String schema=rows.getString("TABLE_SCHEM");
                if("INFORMATION_SCHEMA".equals(schema)) continue;
                if(!"PUBLIC".equals(schema) || "VIEW".equals(rows.getString("TABLE_TYPE"))) throw new IllegalArgumentException("Custom schemas/views require a separate migration plan: "+schema);
                String name=rows.getString("TABLE_NAME"), target=lower(name);
                if(!targetNames.add(target)) throw new IllegalArgumentException("Case-folded table collision: "+name);
                List<Col> cols=new ArrayList<>();
                try(var columns=meta.getColumns(null,"PUBLIC",name,"%")) {
                    while(columns.next()) {
                        String col=columns.getString("COLUMN_NAME"); boolean identity="YES".equals(columns.getString("IS_AUTOINCREMENT")); Long next=null;
                        if(identity) try(var statement=source.prepareStatement("SELECT IDENTITY_BASE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_NAME=? AND COLUMN_NAME=?")) {
                            statement.setString(1,name); statement.setString(2,col);
                            try(var result=statement.executeQuery()) {if(result.next()) next=result.getLong(1);}
                        }
                        cols.add(new Col(col,lower(col),columns.getInt("DATA_TYPE"),columns.getInt("COLUMN_SIZE"),columns.getInt("DECIMAL_DIGITS"),
                                columns.getInt("NULLABLE")!=DatabaseMetaData.columnNoNulls,identity,columns.getString("COLUMN_DEF"),next));
                    }
                }
                SortedMap<Integer,String> pk=new TreeMap<>();
                try(var keys=meta.getPrimaryKeys(null,"PUBLIC",name)) {while(keys.next()) pk.put(keys.getInt("KEY_SEQ"),lower(keys.getString("COLUMN_NAME")));}
                Map<String,Idx> indexes=new LinkedHashMap<>();
                try(var indexesRs=meta.getIndexInfo(null,"PUBLIC",name,false,false)) {while(indexesRs.next()) {
                    String index=indexesRs.getString("INDEX_NAME"), column=indexesRs.getString("COLUMN_NAME");
                    if(index==null || column==null) continue;
                    indexes.computeIfAbsent(index,k -> {try{return new Idx(!indexesRs.getBoolean("NON_UNIQUE"),new TreeMap<>());}catch(SQLException ex){throw new IllegalStateException(ex);}})
                            .columns.put(indexesRs.getInt("ORDINAL_POSITION"),lower(column));
                }}
                Map<String,Fk> fks=new LinkedHashMap<>();
                try(var keys=meta.getImportedKeys(null,"PUBLIC",name)) {while(keys.next()) {
                    String fk=keys.getString("FK_NAME");
                    if(!fks.containsKey(fk)) fks.put(fk,new Fk(lower(keys.getString("PKTABLE_NAME")),new TreeMap<>(),new TreeMap<>(),action(keys.getInt("UPDATE_RULE")),action(keys.getInt("DELETE_RULE"))));
                    fks.get(fk).local.put(keys.getInt("KEY_SEQ"),lower(keys.getString("FKCOLUMN_NAME")));
                    fks.get(fk).remote.put(keys.getInt("KEY_SEQ"),lower(keys.getString("PKCOLUMN_NAME")));
                }}
                tables.add(new Table(name,target,cols,new ArrayList<>(pk.values()),indexes,fks));
            }
        }
        tables.sort(Comparator.comparing(Table::target));
        if(tables.isEmpty()) throw new IllegalArgumentException("Source has no application tables; refusing to claim migration");
        for(var table:tables) for(var col:table.columns) {
            if((col.type==Types.DECIMAL || col.type==Types.NUMERIC) && (col.size>65 || col.scale>30)) throw new IllegalArgumentException("Decimal exceeds MySQL precision: "+table.source+"."+col.source);
            sqlType(col); // Validate every mapping before creating a target table.
        }
        return tables;
    }
    static String action(int action) {
        return switch(action) {
            case DatabaseMetaData.importedKeyCascade -> "CASCADE";
            case DatabaseMetaData.importedKeySetNull -> "SET NULL";
            case DatabaseMetaData.importedKeyNoAction, DatabaseMetaData.importedKeyRestrict -> "RESTRICT";
            default -> throw new IllegalArgumentException("Unsupported foreign-key action: "+action);
        };
    }
    static void create(Connection target, Table table) throws Exception {
        List<String> definitions=new ArrayList<>();
        for(var col:table.columns) {
            String definition=q(col.target)+" "+sqlType(col)+(col.nullable ? " NULL" : " NOT NULL");
            if(col.identity) definition+=" AUTO_INCREMENT";
            else if(col.defaultValue!=null) definition+=" DEFAULT "+col.defaultValue;
            definitions.add(definition);
        }
        if(!table.pk.isEmpty()) definitions.add("PRIMARY KEY ("+names(table.pk)+")");
        for(var entry:table.indexes.entrySet()) {
            var index=entry.getValue();
            if(new ArrayList<>(index.columns.values()).equals(table.pk)) continue;
            String name="idx_"+hash((table.target+"/"+entry.getKey()).getBytes(StandardCharsets.UTF_8)).substring(0,20);
            definitions.add((index.unique ? "UNIQUE " : "")+"KEY "+q(name)+" ("+names(index.columns.values())+")");
        }
        sql(target,"CREATE TABLE "+q(table.target)+" ("+String.join(",",definitions)+") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin ROW_FORMAT=DYNAMIC");
    }
    static Object value(ResultSet rows, int pos, Col col) throws SQLException {
        if(rows.getObject(pos)==null) return null;
        return switch(col.type) {
            case Types.CLOB, Types.NCLOB, Types.CHAR, Types.NCHAR, Types.VARCHAR, Types.NVARCHAR, Types.LONGVARCHAR, Types.LONGNVARCHAR -> rows.getString(pos);
            case Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> rows.getBytes(pos);
            case Types.BOOLEAN, Types.BIT -> rows.getBoolean(pos);
            case Types.TIMESTAMP -> rows.getObject(pos,LocalDateTime.class);
            case Types.DATE -> rows.getObject(pos,LocalDate.class);
            case Types.TIME -> rows.getObject(pos,LocalTime.class);
            case Types.NUMERIC, Types.DECIMAL -> rows.getBigDecimal(pos);
            default -> rows.getObject(pos);
        };
    }
    static List<String> fingerprints(Connection db, Table table, boolean source) throws Exception {
        List<String> hashes=new ArrayList<>();
        String cols=table.columns.stream().map(c -> source?h(c.source):q(c.target)).collect(java.util.stream.Collectors.joining(","));
        try(var statement=db.createStatement();var rows=statement.executeQuery("SELECT "+cols+" FROM "+(source?"PUBLIC."+h(table.source):q(table.target)))) {
            while(rows.next()) {
                var digest=MessageDigest.getInstance("SHA-256");
                for(int i=0;i<table.columns.size();i++) {
                    Object value=value(rows,i+1,table.columns.get(i));
                    if(value==null) {digest.update((byte)0);continue;}
                    digest.update((byte)1);
                    byte[] bytes;
                    if(value instanceof byte[] binary) bytes=binary;
                    else if(value instanceof Number number) bytes=new java.math.BigDecimal(number.toString()).stripTrailingZeros().toPlainString().getBytes(StandardCharsets.UTF_8);
                    else bytes=value.toString().getBytes(StandardCharsets.UTF_8);
                    digest.update(java.nio.ByteBuffer.allocate(8).putLong(bytes.length).array()); digest.update(bytes);
                }
                hashes.add(hex(digest.digest()));
            }
        }
        Collections.sort(hashes); return hashes;
    }
    static Map<String,Object> verify(Connection source, Connection target, List<Table> tables) throws Exception {
        Map<String,Object> result=new LinkedHashMap<>();
        for(var table:tables) {
            var before=fingerprints(source,table,true); var after=fingerprints(target,table,false);
            if(!before.equals(after)) throw new IllegalStateException("Value/count verification failed: "+table.target);
            result.put(table.target,Map.of("rows",before.size(),"sha256",hash(String.join("\n",before).getBytes(StandardCharsets.UTF_8))));
        }
        return result;
    }
    static void copy(Connection source, Connection target, Table table) throws SQLException {
        String sourceCols=table.columns.stream().map(c -> h(c.source)).collect(java.util.stream.Collectors.joining(","));
        String targetCols=table.columns.stream().map(c -> q(c.target)).collect(java.util.stream.Collectors.joining(","));
        String parameters=String.join(",",Collections.nCopies(table.columns.size(),"?"));
        try(var read=source.createStatement();var rows=read.executeQuery("SELECT "+sourceCols+" FROM PUBLIC."+h(table.source));
            var insert=target.prepareStatement("INSERT INTO "+q(table.target)+" ("+targetCols+") VALUES ("+parameters+")")) {
            int batch=0;
            while(rows.next()) {
                for(int i=0;i<table.columns.size();i++) insert.setObject(i+1,value(rows,i+1,table.columns.get(i)));
                insert.addBatch(); if(++batch%200==0) insert.executeBatch();
            }
            if(batch%200!=0) insert.executeBatch();
        }
    }
    public static void main(String[] args) throws Exception {
        Path reportDir=Path.of(required("REPORT_DIR")); Files.createDirectories(reportDir);
        Map<String,Object> report=new LinkedHashMap<>(); report.put("status","failed"); report.put("started",Instant.now().toString());
        var gson=new GsonBuilder().setPrettyPrinting().create();
        try {
            String sourceUrl=required("SOURCE_URL");
            if(!sourceUrl.contains("IFEXISTS=TRUE") || !sourceUrl.contains("ACCESS_MODE_DATA=r")) throw new IllegalArgumentException("Source must be existing and read-only");
            try(Connection source=DriverManager.getConnection(sourceUrl,env("SOURCE_USER","sa"),env("SOURCE_PASSWORD",""));
                Connection target=DriverManager.getConnection(required("TARGET_URL"),required("TARGET_USER"),required("TARGET_PASSWORD"))) {
                if(!"MySQL".equals(target.getMetaData().getDatabaseProductName())) throw new IllegalArgumentException("Target is not MySQL");
                try(var tables=target.getMetaData().getTables(target.getCatalog(),null,"%",new String[]{"TABLE","VIEW"})) {
                    if(tables.next()) throw new IllegalArgumentException("Target schema is not empty; refusing to merge or overwrite");
                }
                var tables=metadata(source);
                Path original=Path.of(required("SOURCE_FILE")), backup=reportDir.resolve("source.mv.db");
                Files.copy(original,backup); // Source is open read-only and file-locked; original remains untouched.
                report.put("source_sha256",hash(Files.readAllBytes(backup)));
                report.put("source_backup",backup.toString());
                sql(target,"SET SESSION sql_mode = CONCAT(@@sql_mode, ',NO_AUTO_VALUE_ON_ZERO')");
                for(var table:tables) create(target,table);
                target.setAutoCommit(false);
                try {
                    for(var table:tables) copy(source,target,table);
                    report.put("tables",verify(source,target,tables));
                    target.commit();
                } catch(Exception ex) {target.rollback();throw ex;}
                target.setAutoCommit(true);
                for(var table:tables) {
                    for(var col:table.columns) if(col.identity && col.nextId!=null) sql(target,"ALTER TABLE "+q(table.target)+" AUTO_INCREMENT="+Math.max(1,col.nextId));
                    for(var entry:table.fks.entrySet()) {
                        var fk=entry.getValue();
                        String name="fk_"+hash((table.target+"/"+entry.getKey()).getBytes(StandardCharsets.UTF_8)).substring(0,20);
                        sql(target,"ALTER TABLE "+q(table.target)+" ADD CONSTRAINT "+q(name)+" FOREIGN KEY ("+names(fk.local.values())+") REFERENCES "+q(fk.target)+" ("+names(fk.remote.values())+") ON UPDATE "+fk.update+" ON DELETE "+fk.delete);
                    }
                }
                verify(source,target,tables); report.put("status","verified"); report.put("finished",Instant.now().toString());
                System.out.println("Verified "+tables.size()+" tables. Original H2 retained. Report: "+reportDir.resolve("migration-report.json"));
            }
        } finally {
            Files.writeString(reportDir.resolve("migration-report.json"),gson.toJson(report),StandardCharsets.UTF_8);
        }
    }
}
