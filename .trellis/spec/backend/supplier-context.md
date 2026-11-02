# Supplier Context Contract

## 1. Scope / Trigger
Supplier task dispatch, blocked-node resume, evidence capture and codegen prompt generation.

## 2. Signatures
- `SupplierKbService.contextText(kbCode, stage)` resolves supplier-scoped text.
- `PUT /api/supplier-kb/entries/{id}/stage` accepts `{stage}`.
- `PUT /api/supplier-jobs/{id}/prep` additionally accepts `promptSkillPath`.
- `POST /api/supplier-jobs/{id}/codegen-prompt/generate` calls the bound instance using company instructions and returns `{prompt}`.
- New nullable-compatible JPA fields: job.prompt_skill_path, generated_prompt_digest; node.contextStage, deliveryManifest, toolEvidence; kb.context_stage. Existing `ddl-auto:update` creates columns.

## 3. Contracts
Material stages: all/analysis/probe/codegen/archive. Nodes additionally accept auto (legacy title inference); explicitly selected probe nodes require evidence regardless of title. Only archive entries are excluded from unknown/all-stage dispatch. Snapshot files are retained under the supplier KB .deliveries directory. Model-produced prompt archives use archive stage to prevent recursive context growth.

Skill/spec entry files are read on the service host; URL references resolve only through already parsed entries in the same supplier KB. Codegen requires company spec, integration skills, prompt skill, meeting/config preparation, completed probe evidence and an existing target Trellis workflow. Task metadata and immutable snapshots are created under `.trellis/tasks/supplier-<jobId>`; existing PRDs remain intact. JSONL keeps human context references while replacing generated snapshot references.

## 4. Validation & Error Matrix
- Selected RAW/FAILED/unreadable/empty entry -> 400 before RUNNING.
- Knowledge >120000 chars / company instructions >60000 / codegen >240000 -> 400.
- Missing upstream artifact -> 400; never silently truncate.
- Missing spec/skills/Trellis/probe evidence -> 400 before codegen.
- Development execution/completion without a current generated-prompt fingerprint -> 400; source changes invalidate generation.
- GET codegen-prompt previews context without creating task files; POST generate prepares task files and invokes the bound instance.
- Probe completion without observed execution evidence -> 400.
- MCP SUCCESS alone is insufficient: evidence must contain request method+URL and HTTP status 100..599 with body/stream. Capture limits are 500000 chars per input/output and 1000000 chars aggregate; oversized events do not qualify.

## 5. Good/Base/Bad Cases
Good: codegen stage receives common plus codegen documents, full company instructions and observed probe results.
Base: legacy node auto-infers scope from title, unknown titles receive all active material.
Bad: a prose-only “tests passed” report cannot complete a probe; a Postman collection listing cannot create execution evidence.

## 6. Tests Required
SupplierContextTest: actual text, cross-supplier isolation, stage exclusion, unresolved/missing/oversized failures and delivery snapshots.
SupplierReferenceContextTest: company entrypoint resolution, fingerprints and missing-source failures.
SupplierProbeEvidenceTest: executable tool names and actual request/response structure.
SupplierWorkflowGateTest: prose-only probe cannot complete; verified evidence is archived; source changes require prompt regeneration.
SupplierTrellisContextTest: task creation, immutable snapshots, PRD preservation, latest JSONL references.
Live smoke: fresh H2 schema, entry CRUD/readback, persisted stage/prep values, node audit fields, bad stage and missing-evidence errors.

## 7. Wrong vs Correct
Wrong: include only file paths and ask the agent to remember to read them; accept its prose as test evidence.
Correct: resolve configured text before dispatch, retain an exact request snapshot, and use observed tool execution to gate probe completion. Do not claim that text delivery proves comprehension or that one observed request proves complete endpoint coverage.
