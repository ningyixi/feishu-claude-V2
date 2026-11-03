# H2 to local MySQL migration and portable multi-computer storage

## Requirements
- Discover the actual H2 source; do not substitute a blank or test database for user data.
- Reuse the user's local OrbStack MySQL container with a separate project schema and dedicated account; preserve unrelated databases.
- Configure this project for MySQL while retaining explicit H2 compatibility for migration/testing.
- Reusable offline source migration: preserve all tables, columns, row values including long Unicode text, nulls, primary keys and identity continuation. Produce source backup and independent per-table verification before declaring success.
- Portable local MySQL backup/restore including KB files and path relocation. Each computer keeps its own database; scripts transfer snapshots, not automatic concurrent synchronization.
- No credentials or business data in Git. Test against real H2 fixtures and real MySQL in OrbStack. Report actual source migration as pending if no user source is available.

## Validation
- JVM migration unit/integration coverage against populated H2 and isolated MySQL schema.
- Start the packaged application on migrated MySQL schema; check Hibernate compatibility, CRUD and generated keys.
- Export/restore round trip with KB files, Unicode, checksums and path rebasing.
- Existing Maven tests and production build compatibility.

## User steering
All application frontend/backend/middleware must run in OrbStack. Old H2 file is on another computer and unavailable now. Start the stack first, then proceed to the new LLM Wiki task. Real-source migration remains pending that file; no claim of business-data import.

## Implemented verification
Independent MySQL/Java/Node/Nginx stack starts on 3307/18081/18080. MySQL LONGTEXT mappings fixed, including legacy H2 LOB fields. Real populated H2 fixture migration verified Unicode/emoji, 100000-character text, nulls, binary data, decimal/timestamp precision, ID zero, case-distinct unique values, foreign keys, next generated ID 1001 and refusal to overwrite. Source and backup hashes match. Backup/restore of local MySQL and volumes completed against isolated scratch schema with row-count verification.
