package com.lark.oapi.sample.ws.service;

import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 程序化生成的最小 OOXML / OLE2 测试夹具（11-23-kb-office-documents）。
 * 只拼解析器实际需要的包结构；真实二进制 Word97 文档无法凭空构造，
 * .doc 成功态由真实文件验证（见任务回报），单测覆盖失败分类与改名判定。
 */
final class OfficeFixtures {

    static final String NS_W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    static final String NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    static final String NS_P = "http://schemas.openxmlformats.org/presentationml/2006/main";
    static final String NS_S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    static final String NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    static final String NS_PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships";

    private OfficeFixtures() {}

    // ===== zip 打包 =====

    static Path zip(Path dir, String fileName, Map<String, String> textEntries) throws IOException {
        Map<String, byte[]> m = new LinkedHashMap<>();
        textEntries.forEach((k, v) -> m.put(k, v.getBytes(StandardCharsets.UTF_8)));
        return zipBytes(dir, fileName, m);
    }

    static Path zipBytes(Path dir, String fileName, Map<String, byte[]> entries) throws IOException {
        Path f = dir.resolve(fileName);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(f))) {
            for (Map.Entry<String, byte[]> en : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(en.getKey()));
                zos.write(en.getValue());
                zos.closeEntry();
            }
        }
        return f;
    }

    // ===== docx =====

    /** 覆盖点：Heading1/2、w:tab→空格、instrText 域代码、w:del 修订删除、w:ins 修订插入、表格 */
    static final String RICH_DOC_XML = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="%s"><w:body>
            <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>第一章 接口概述</w:t></w:r></w:p>
            <w:p><w:r><w:t>本接口提供酒店静态信息查询。</w:t></w:r><w:r><w:tab/><w:t>分页返回。</w:t></w:r></w:p>
            <w:p><w:pPr><w:pStyle w:val="Heading2"/></w:pPr><w:r><w:t>1.1 认证方式</w:t></w:r></w:p>
            <w:p><w:r><w:instrText> PAGE MERGEFORMAT </w:instrText></w:r><w:r><w:t>签名算法为HMAC-SHA256，密钥仅保存在服务端。</w:t></w:r></w:p>
            <w:p><w:r><w:t>所有请求必须携带 X-Access-Key、X-Timestamp、X-Nonce、X-Signature 四个请求头；时间戳与服务器偏差不得超过五分钟，随机数在十分钟内不得重复，否则网关将直接拒绝请求并返回鉴权失败错误码。</w:t></w:r></w:p>
            <w:p><w:r><w:t>酒店静态信息接口支持按城市、按酒店编号批量查询，单次请求最多返回一百条记录；超出部分请使用分页参数循环拉取，并在本地做增量合并，避免全量覆盖导致营业状态字段丢失。</w:t></w:r></w:p>
            <w:p><w:del w:id="1"><w:r><w:delText>已废弃的旧字段oldField说明</w:delText></w:r></w:del><w:ins w:id="2"><w:r><w:t>现行字段newField说明</w:t></w:r></w:ins></w:p>
            <w:tbl>
            <w:tr><w:tc><w:p><w:r><w:t>字段</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>类型</w:t></w:r></w:p></w:tc></w:tr>
            <w:tr><w:tc><w:p><w:r><w:t>hotelId</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>string</w:t></w:r></w:p></w:tc></w:tr>
            </w:tbl>
            </w:body></w:document>
            """.formatted(NS_W);

    static Path docx(Path dir, String fileName, String documentXml) throws IOException {
        return zip(dir, fileName, Map.of("word/document.xml", documentXml));
    }

    static Path richDocx(Path dir, String fileName) throws IOException {
        return docx(dir, fileName, RICH_DOC_XML);
    }

    // ===== pptx =====

    static final String PRESENTATION_XML = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <p:presentation xmlns:p="%s" xmlns:a="%s"/>
            """.formatted(NS_P, NS_A);

    /** 一页幻灯片：每个参数渲染为一个 a:p 段落 */
    static String slideXml(String... paragraphs) {
        StringBuilder tx = new StringBuilder();
        for (String p : paragraphs) tx.append("<a:p><a:r><a:t>").append(p).append("</a:t></a:r></a:p>");
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <p:sld xmlns:a="%s" xmlns:p="%s"><p:cSld><p:spTree><p:sp><p:txBody>
                %s
                </p:txBody></p:sp></p:spTree></p:cSld></p:sld>
                """.formatted(NS_A, NS_P, tx);
    }

    /** slides: slideNo → 段落数组；entry 写入顺序按 slideNo 降序（故意制造字典序陷阱） */
    static Path pptx(Path dir, String fileName, Map<Integer, String[]> slides) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("ppt/presentation.xml", PRESENTATION_XML);
        List<Integer> nos = new ArrayList<>(slides.keySet());
        nos.sort(Comparator_DESC());
        for (int no : nos) entries.put("ppt/slides/slide" + no + ".xml", slideXml(slides.get(no)));
        return zip(dir, fileName, entries);
    }

    private static java.util.Comparator<Integer> Comparator_DESC() {
        return java.util.Comparator.reverseOrder();
    }

    // ===== xlsx =====

    /**
     * 生成 xlsx。单元格标记约定："s:<idx>"=共享字符串、"n:<num>"=数字、其余=内联字符串。
     *
     * @param sheets 每个元素是一个 sheet 的行清单（与 sheetNames 一一对应）
     */
    static Path xlsx(Path dir, String fileName, String[] sheetNames, List<List<String[]>> sheets,
                     List<String> sharedStrings) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        StringBuilder wbSheets = new StringBuilder();
        StringBuilder rels = new StringBuilder();
        for (int i = 0; i < sheetNames.length; i++) {
            wbSheets.append("<sheet name=\"").append(sheetNames[i]).append("\" sheetId=\"").append(i + 1)
                    .append("\" r:id=\"rId").append(i + 1).append("\"/>");
            rels.append("<Relationship Id=\"rId").append(i + 1)
                .append("\" Type=\"").append(NS_R).append("/worksheet\" Target=\"worksheets/sheet").append(i + 1).append(".xml\"/>");
        }
        entries.put("xl/workbook.xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="%s" xmlns:r="%s"><sheets>%s</sheets></workbook>
                """.formatted(NS_S, NS_R, wbSheets));
        entries.put("xl/_rels/workbook.xml.rels", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="%s">%s</Relationships>
                """.formatted(NS_PKG_REL, rels));
        StringBuilder sst = new StringBuilder();
        for (String s : sharedStrings) sst.append("<si><t>").append(s).append("</t></si>");
        entries.put("xl/sharedStrings.xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <sst xmlns="%s" count="%d" uniqueCount="%d">%s</sst>
                """.formatted(NS_S, sharedStrings.size(), sharedStrings.size(), sst));
        for (int i = 0; i < sheets.size(); i++)
            entries.put("xl/worksheets/sheet" + (i + 1) + ".xml", sheetXml(sheets.get(i)));
        return zip(dir, fileName, entries);
    }

    static String sheetXml(List<String[]> rows) {
        StringBuilder sb = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="%s"><sheetData>
                """.formatted(NS_S));
        for (int ri = 0; ri < rows.size(); ri++) {
            sb.append("<row r=\"").append(ri + 1).append("\">");
            String[] cells = rows.get(ri);
            for (int ci = 0; ci < cells.length; ci++) {
                String ref = colRef(ci) + (ri + 1);
                String v = cells[ci];
                if (v.startsWith("s:")) sb.append("<c r=\"").append(ref).append("\" t=\"s\"><v>").append(v.substring(2)).append("</v></c>");
                else if (v.startsWith("n:")) sb.append("<c r=\"").append(ref).append("\"><v>").append(v.substring(2)).append("</v></c>");
                else sb.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t>").append(v).append("</t></is></c>");
            }
            sb.append("</row>");
        }
        return sb.append("</sheetData></worksheet>").toString();
    }

    static String colRef(int i) {
        StringBuilder sb = new StringBuilder();
        for (i++; i > 0; i = (i - 1) / 26) sb.insert(0, (char) ('A' + (i - 1) % 26));
        return sb.toString();
    }

    // ===== OLE2（POI POIFS 构造真实复合文档头） =====

    /** 含指定根流的 OLE2 文件（内容随意——只用于结构分类分支，HWPF 深解析必然报损坏） */
    static Path ole2(Path dir, String fileName, String rootEntryName) throws IOException {
        Path f = dir.resolve(fileName);
        try (POIFSFileSystem fs = new POIFSFileSystem()) {
            fs.createDocument(new ByteArrayInputStream("dummy-payload".getBytes(StandardCharsets.UTF_8)), rootEntryName);
            try (OutputStream out = Files.newOutputStream(f)) {
                fs.writeFilesystem(out);
            }
        }
        return f;
    }

    static Path encryptedOle2(Path dir, String fileName) throws IOException {
        return ole2(dir, fileName, "EncryptedPackage");
    }

    static Path legacyPptOle2(Path dir, String fileName) throws IOException {
        return ole2(dir, fileName, "PowerPoint Document");
    }

    static Path legacyXlsOle2(Path dir, String fileName) throws IOException {
        return ole2(dir, fileName, "Workbook");
    }

    static Path fakeWordOle2(Path dir, String fileName) throws IOException {
        return ole2(dir, fileName, "WordDocument");
    }
}
