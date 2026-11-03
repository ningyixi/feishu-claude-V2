# Supplier context delivery

User authorizes autonomous implementation, testing in OrbStack, commits and push.

## Requirements and implementation
- Preserve existing per-supplier Markdown knowledge store and manual chat/note ingestion.
- Attach knowledge to every custom agent node even without a docs placeholder.
- Read actual parsed text before dispatch and codegen prompt generation. Reject unresolved, unreadable, empty or oversized entries rather than silently dropping them.
- Refresh knowledge when resuming a blocked node.
- Archive confirmed node artifacts and manual corrections into the supplier knowledge store, maintaining one source per job/node.
- Verify supplier isolation and failure behavior using real temporary files and repository mocks.

## Additional requirements delivered
- Company integration Skill, module spec and prompt-generation Skill entrypoints are resolved to text and fingerprinted before development.
- Actual prompt generation is delegated to the bound instance using the company Skill instructions; original required context is attached by code to its result.
- Explicit per-material and per-node stages route context without relying on retrieval ranking; legacy templates retain auto inference.
- Successful observed Postman execution events must contain actual request and HTTP response structure. Prose-only reports cannot complete probe nodes.
- Company development generation requires completed probe evidence and a valid Trellis project. Program creates task files and immutable context snapshots while preserving manual PRDs.
- Exact node request snapshots and fingerprints support inspection. UI exposes stage selection, prompt Skill configuration, delivery receipts and probe evidence.

## Verification
- OrbStack Java 21 Maven verify and Node 22 production build.
- Unit and workflow tests cover scope, source loading, immutable task snapshots, model omission resistance, proof gates and cross-supplier isolation.
- Live isolated H2 smoke covers new schema, ingestion/readback, persisted stage and prep fields, audit fields and error gates.

## Honest operational boundaries
Company filesystem paths and Postman tools must exist on the execution host. Skill-linked files are loaded by the execution instance with missing-file standblocks; code delivers the configured entrypoint text. Tool events prove observed calls, not exhaustive endpoint coverage or business success. Live supplier credentials and company files were not provided in this repository; validation uses isolated fixtures and live local application APIs. This does not claim a live integration with an unspecified external supplier.
