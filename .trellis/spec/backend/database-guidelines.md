# Database and local container contract

## 1. Scope
Default runtime storage is MySQL 8.4 in OrbStack. H2 remains an explicit legacy profile for offline migration and fixtures. All frontend/backend/database services use compose.yml.

## 2. Signatures
- `python3 scripts/database.py init|up|down|status|build`
- `new-store --name <unique-schema>` selects a new empty schema and separate data/home volumes without deleting old stores.
- `migrate-h2 --source <config.mv.db>` uses the standalone JDBC tool scripts/H2ToMysql.java.
- `backup --output <zip>` / `restore <zip>` transfer the local SQL/data snapshot.

## 3. Configuration
Private .env.mysql is generated with independent random application/root passwords, UID/GID and workspace mapping. Never commit it. mysql profile uses FEISHU_MYSQL_URL/USER/PASSWORD; h2 uses FEISHU_DB_PATH and optional FEISHU_H2_USER/PASSWORD. Docker data paths remain /app/data and /home/node. No cloud service is required. Claude login and MCP settings must be provisioned inside the backend container.

All large String fields, including @Lob fields with named @Column, declare length 2147483647. On MySQL, @Lob with an unqualified length=255 can become TINYTEXT. Never rely on H2 accepting large strings as evidence of MySQL capacity.

## 4. Validation and failures
Migration opens existing H2 read-only and refuses a nonempty target. Metadata conversion preserves IDs, defaults, indexes and foreign keys; unsupported source structures fail. Each row is fingerprinted with typed, length-prefixed values; target data is independently reread and compared. Binary source backup and JSON report are retained. Data mismatch rolls back the insert transaction; MySQL DDL and later constraint changes cannot be globally rolled back, so failures leave a documented target for inspection.

Restore verifies archive membership, checksum manifest and safe paths, then checks per-table counts. It requires an empty target and stopped application. Backup stops frontend/backend to keep SQL and knowledge files consistent. Git is not database synchronization.

## 5. Good/Base/Bad
Good: switch to a fresh local store, migrate, verify, relocate external KB files, then start backend.
Base: clean installation creates schema and built-in workflow templates with no previous business data.
Bad: copy H2 into the repo and assume Git pulls include it; silently skip unsupported tables; overwrite an existing target; narrow long JSON columns to TINYTEXT.

## 6. Verification
Run Maven verify in Java 21 container, scripts.tests.test_migration against a real isolated MySQL schema, packaged application startup and REST checks through Nginx. Verify LONGTEXT capacity and a backup/restore round trip. Real user-source migration is not complete until that actual file and external knowledge folder are available and verified.

## 7. Wrong vs correct
Wrong: `@Lob @Column(name="nodes_json")` with implicit length=255 and direct H2 SQL import into MySQL.
Correct: explicit large text length, MySQL driver/profile, portable JDBC migration, content checksums and durable container volumes. Never use docker compose down -v as a routine stop command.

## Windows handoff boundary
The runtime can use native Windows Java/MySQL, but `scripts/database.py` explicitly requires Docker. Native migration/backup wrappers and the user's actual source data are not yet verified. The converter lowercases table names; do not assume existing Flowable ACT_* tables or Windows/Linux MySQL dumps are portable without identifier-case and engine-version validation. MySQL lower_case_table_names is an initialization-time setting; never change an existing store to conceal a migration mismatch. See `docs/Windows无Docker迁移交接.md` before native Windows migration.
