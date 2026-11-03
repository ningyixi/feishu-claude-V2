# Supplier LLM Wiki and text A/B workflow

## User requirements
- Start the whole application stack (frontend, backend, MySQL) using OrbStack first.
- Preserve the existing text mode; create an LLM-maintained, interlinked wiki as a second mode.
- Choose TEXT vs WIKI at workflow/job creation. Persist the mode; no hidden fallback or mode changes during an A/B run.
- Keep immutable raw source material as evidence. Wiki is a compiled knowledge view, not destructive summarization or vector chunk retrieval.
- Ingest into source pages, domain concept/API/business pages, index/navigation, provenance and explicit contradictions/unresolved questions.
- Give agents a compact knowledge map and relevant entry pages, with stable links to complete source material. Use runtime stage contracts rather than only an LLM guessing routes.
- Track source versions, wiki generation state and run context size/mode for comparison. Changed source material must make an obsolete wiki visibly stale.
- No wiki generation without a configured bound execution instance. Failed generation blocks wiki execution; do not fall back to TEXT.

## Plan
Research the primary LLM Wiki proposal and current supplier flow; implement source-preserving compilation, deterministic validation and publication, explicit stage entrypoints, read-only wiki/source access, UI mode selection/build/view, and comparative execution receipts. Verify against MySQL-backed application and OrbStack tests.

## Delivered and verified
- Persisted immutable TEXT/WIKI selection, legacy TEXT compatibility and isolated execution lanes.
- Full source snapshots, cached extraction, validated linked pages, editable supplier rules, stale gates and fixed-version reads.
- Program-delivered critical and stage pages; explicit size failures and no TEXT fallback.
- UI compilation/view/navigation and separate character/observed token receipts.
- 80 default tests passed; isolated real MySQL/JPA/files/HTTP integration passed with stubbed model; running Nginx/backend/MySQL smoke passed. Frontend and production Docker image builds passed.
- Documentation: `docs/供应商LLM-Wiki与AB测试.md`, `.trellis/spec/backend/supplier-wiki.md`.

## Practical limits
Real supplier materials, configured model instance/authentication and semantic A/B evaluation are not present in this new local database. Tests establish protocol, persistence, isolation and gates, not model quality. Old H2 business data remains on the other computer; migration tooling exists but those records have not been imported.
