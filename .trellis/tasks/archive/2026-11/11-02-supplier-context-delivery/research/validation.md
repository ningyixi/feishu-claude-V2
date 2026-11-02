# Validation record (2026-11-02)

- OrbStack `maven:3.9-eclipse-temurin-21`, `mvn verify -q`: 49 tests, 0 failures, 0 errors, 0 skipped. Includes stream-json event -> node evidence, stage/isolation checks, prompt retention and invalidation, and immutable Trellis task contexts.
- OrbStack `node:22-alpine`, `npm ci && npm run build`, followed by rebuilds after UI changes: passed. Production static assets regenerated. Build reports an existing CSS `maxWidth` spelling warning; not introduced by this task.
- Isolated packaged application + in-memory H2: startup successful. REST smoke passed for chat ingestion/readback, supplier separation, stage validation/persistence, prompt Skill setting persistence, audit fields, missing-evidence rejection, and explicit probe stage independent of node title. Smoke container stopped after checks.
- Actual HTTP fixture using Newman in OrbStack: the exact Postman Tests script extracted from ACTION_SPEC produced a request URL/method and the server's real response status/body in a SUPPLIER_EVIDENCE assertion. This verifies compatibility with the official MCP formatter, which surfaces assertion names.
- `git diff --check` and Trellis context validation passed.

No company Skill/spec paths, provider credentials or configured production Postman MCP instance were supplied. Tests cover the implemented workflows with local fixtures, actual Newman HTTP execution, and an isolated application. They do not claim a real external supplier was integrated or that every supplier endpoint has passed business validation.
