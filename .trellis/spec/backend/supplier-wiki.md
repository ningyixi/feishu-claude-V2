# Supplier Wiki / Text Experiment Contract

## 1. Scope / Trigger
Supplier job creation, knowledge compilation, workflow dispatch, follow-up chat and prompt generation. Read together with `supplier-context.md`. Wiki is a derived view of the existing supplier KB; raw material remains available in both modes.

## 2. Signatures
- `POST /api/supplier-jobs`: optional `knowledgeMode: "TEXT" | "WIKI"`, default TEXT. Job updates cannot change mode.
- `GET /api/supplier-jobs/{id}/wiki`: state, revision, source digest, progress, observed compilation usage; READY also includes page catalog, critical IDs and stage entrypoints.
- `POST .../wiki/build`: 202 with revision/status; requires a bound, enabled instance and WIKI job.
- `GET .../wiki/schema`; `PUT .../wiki/schema {instructions}`: supplier-scoped optional compilation instructions, maximum 8000 characters.
- `GET .../wiki/revisions/{revision}/pages/{pageId}` and `/sources/{sourceId}`: `{content}` from registered immutable files.
- `GET .../knowledge-runs`: latest 200 execution receipts for this job.
- JPA: `supplier_job.knowledge_mode` (legacy NULL reads TEXT), `supplier_job_node.knowledge_run_id`, `supplier_wiki_revision`, `supplier_knowledge_run`. Large strings use LONGTEXT-compatible column length, not default MySQL TINYTEXT.

## 3. Contracts
`SupplierKnowledgeService.context(job, stage)` selects one strategy. TEXT preserves existing stage filtering. WIKI resolves a published, current revision and delivers critical pages plus that stage's declared entry pages directly, together with a compact category map and evidence paths. All/analysis/probe/codegen are explicit entrypoint keys. A job+mode+instance lane prevents conversational history from crossing experiment arms. Prompt generation uses an additional fresh lane.

Compilation snapshots all non-archive, DONE source material, including loaded site child pages. Each complete source is extracted with evidence quotes; cached extraction keys include source metadata/content, model/instance and schema. Synthesis produces linked pages. Application code validates the draft and writes files; the model does not publish files. Every source must appear in page evidence. Quotes must occur verbatim in that source. Page IDs, internal links, critical IDs, stage entries and delivery budget are checked.

Storage under `${feishu.kb-dir}/.wiki/<sha256(kbCode)[0:24]>/`: `instructions.md`, `extractions/`, `log.md`, and `revisions/<id>/{raw/,wiki/,schema.md,manifest.json}`. DB READY transition publishes an immutable directory. Hashes verify page/source reads. Source or schema changes make a revision STALE for new execution; old fixed-version evidence remains readable. Build is explicit, with source cache reuse; source edits do not silently trigger model calls. Interrupted BUILDING rows become FAILED on restart.

Receipts retain mode, phase, source digest, revision, selected pages, knowledge/request character counts, request hash, status, duration and observed CLI token/cache/cost fields. Unknown usage is -1, never inferred from characters. `onResultAvailable` must run before releasing the response future; existing completion callbacks remain after completion.

## 4. Validation & Error Matrix
| Condition | Result |
| --- | --- |
| Invalid mode or attempt to change existing mode | 400 |
| TEXT build/custom instruction write | 400 |
| Missing/disabled bound instance, unfinished/unreadable source | 400 before compilation |
| Duplicate concurrent supplier build | 400 |
| Fake quote, unknown link/source, missing source coverage, invalid phase entry | FAILED, no publication |
| Raw source > 2 MB / 500000 characters | Explicit failure; no truncation |
| Required Wiki runtime context > 32000 characters | Explicit failure; no truncation |
| Empty/building/failed/stale Wiki during execution | Block; never fall back to TEXT |
| Source/schema changes while compiling | FAILED before READY |
| Cross-supplier revision, unknown ID, modified published file | 400 |

## 5. Good/Base/Bad Cases
Good: two jobs share the same source digest, use different persisted modes and isolated sessions, and record comparable phase receipts.
Base: an existing job with NULL mode continues using TEXT without requiring Wiki compilation.
Bad: a compact page omits a business constraint despite valid citations. Structural validation cannot establish semantic completeness; review original evidence and evaluate output quality separately.

## 6. Tests Required
- `SupplierWikiTest`: compilation/cache, immutable evidence, stale source/schema, invalid publication, tamper/scoping checks and no fallback.
- `SupplierWikiSourcesTest`: full site-page bodies, changed digest, missing source errors.
- `SupplierKnowledgeServiceTest`, `SupplierKnowledgeModeTest`: mode persistence, isolation, measured usage and TEXT compatibility.
- `ResultHandoffTest`: result metadata available before response future releases.
- `python3 scripts/tests/run_wiki_mysql.py`: isolated real MySQL/JPA/files/HTTP test; only model execution is stubbed.
- `python3 -m unittest scripts.tests.test_stack_smoke -v`: running Nginx/backend/MySQL routes, long Unicode sources, mode immutability and unbound-instance preflight. Removes only its uniquely named fixtures.

## 7. Wrong vs Correct
Wrong: ask the model to remember which documents to open, silently reuse a stale Wiki, or treat a fixture's character reduction as a quality benchmark.
Correct: `Pack pack = knowledge.context(job, stage);` then deliver its required page contents, persist the receipt, block stale execution and retain fixed-version raw evidence. Additional reads remain agent actions; API scoping does not sandbox arbitrary filesystem tools. Citation checks prove provenance structure, not complete or correct reasoning.
