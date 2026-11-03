# Durable Supplier Workflow Contract

## 1. Scope / Trigger
Explicitly starting the engine on an untouched supplier job. Legacy jobs continue manual operation until started. Read `supplier-context.md` and `supplier-wiki.md`; their evidence, company instructions and freshness gates still apply.

## 2. Signatures
- `POST /api/supplier-jobs/{id}/workflow/start {recipient?, notifyFeishu?:true}`. Recipient defaults to the bound instance's authorized open ID. Returns the persisted run.
- `GET .../workflow`: NOT_STARTED or `{status,run,tasks,history,notices,attempts}`. Tasks include ID, kind, node ID, description, question, artifact and uploaded file metadata.
- `POST .../workflow/control {action:"PAUSE"|"RESUME"|"CANCEL"}`.
- `POST .../workflow/tasks/{taskId}/respond {action:"SUBMIT"|"APPROVE"|"REJECT", answer?:string}`.
- `POST .../workflow/tasks/{taskId}/files`: multipart `file`, PDF or supported UTF-8 text, maximum 10 MiB. Returns attachment metadata and supplier KB entry ID.
- `POST .../workflow/notices/{id}/retry`: retry a FAILED notice belonging to this run.
- Feishu command: `/供应商 <taskId> 同意`, `驳回 <feedback>`, or `答复 <answer>`. Program handling precedes general chat/shell routing. It verifies both bound instance and configured recipient; no LLM interpretation.

## 3. Contracts
Embedded `org.flowable:flowable-spring:7.2.0`, SpringProcessEngineConfiguration sharing DataSource and transaction manager. Do not auto-upgrade to Flowable 8: its Spring/Boot major differs. Process/history/runtime/task data lives in Flowable's ACT_* tables in the same MySQL schema. No additional runtime container is required. Always set Flowable databaseCatalog from the actual JDBC connection catalog; Connector/J metadata may otherwise discover ACT_* tables in a different accessible database and attempt an invalid upgrade in a fresh store. Integration tests run while the main store already contains engine tables to cover this boundary.

Application tables: `supplier_workflow_run` (unique job_id, process/definition IDs, recipient/instance, state/version), `supplier_workflow_attempt` (unique dispatch_key), `supplier_workflow_notice` (unique event_key, outbox status/retry data), `supplier_workflow_attachment` (run/task/KB entry/content hash/original file). Original uploads live under `${feishu.kb-dir}/.workflow-uploads/<run>/<task>/`; UUID filenames, not user paths. Existing backup scripts include these tables and files.

BPMN generated from the node snapshot: manual/reminder -> user task; agent/agent_check -> receive task waiting for execution, exclusive gateway -> input or review user task. Input returns to execution; rejection loops to execution with feedback; approval completes the original node and advances. Existing completion validation executes within the transaction before completing the Flowable task. Definition is pinned per run; external arbitrary BPMN/scripts/delegates cannot be imported.

A dedicated timer polls every 2 seconds by default, separate from other application schedulers. Feishu delivery uses a bounded two-thread pool so a slow provider does not block process progression. Claim commits before asynchronous model dispatch. Attempts use execution ID **plus visit counter**: Flowable may reuse an execution token on a rework loop or the next node. WIKI jobs automatically compile/wait before dispatch if material is stale or absent; compilation failure creates input task. WAITING_WIKI can safely resume polling after restart. DISPATCHED/RUNNING attempts become a human recovery task on restart; uncertain external effects are never replayed automatically.

Flow mutations use READ_COMMITTED and a database row lock on the run. Acquire native FOR UPDATE then refresh loaded JPA entities. Under MySQL REPEATABLE_READ, an earlier lookup can retain an old task snapshot after the lock and produce optimistic conflicts. Do not replace database coordination with JVM synchronization.

Notification outbox is written with workflow state. UUID derives deterministically from event key; acknowledge SENT only on provider success. Failures retry exponentially up to 6 attempts; then FAILED with manual retry. Obsolete task notices are skipped, paused notices deferred. This is at-least-once delivery, not a guarantee of one visible message across all provider failure windows.

`FEISHU_WORKFLOW_PUBLIC_URL` configures reachable task links (default http://localhost:18080). `feishu.workflow.poll-ms` and `feishu.workflow.worker-enabled` tune/disable worker; tests disable automatic polling and stub model and Feishu calls.

## 4. Validation & Error Matrix
| Condition | Result |
| --- | --- |
| Already started or previously executed job; empty/>80/invalid node snapshot | 400 |
| Agent without enabled instance; notification without instance/open ID | 400 |
| Manual run/answer/complete/artifact editing, job edits/deletion after managed start | 400; use engine tasks |
| Foreign, closed or duplicate task submission | 400, no second transition |
| Approve input task; submit review; empty rejection/input without files | 400 |
| Answer >20000 characters | 400, upload instead |
| Invalid type, invalid UTF-8, binary text, >10 MiB, >500000 extracted characters, textless PDF | Reject upload; do not pass bytes to model |
| Probe/spec/skill/Trellis gate fails during approval | Transaction rollback; task remains open |
| Paused response/upload | 400; restore first |
| Wrong recipient or robot in Feishu command | Reject before action |

## 5. Good/Base/Bad Cases
Good: file uploaded to current task -> associated supplier KB entry -> SUBMIT -> automatic agent dispatch -> explicit approval -> next task notification.
Base: no Feishu configuration -> select in-app-only at start; durable tasks remain actionable.
Bad: process interrupted after an external API may have run; silently retrying could create duplicate orders. Ask the human to inspect the external result before retry.

## 6. Tests Required
`FEISHU_IT_TEST=SupplierWorkflowMysqlIT python3 scripts/tests/run_wiki_mysql.py` uses an isolated MySQL schema and real Flowable/JPA/files/HTTP. Assert full input/file/blocker/review/rejection loop, feedback reaches model, concurrent response advances once, provider failure is retained, paused/recovery/cancel behavior, another engine instance reads persisted tasks, provenance gates keep approval open, and WIKI compiles before agent dispatch. Model and outbound Feishu boundaries must be stubbed; tests do not send real notifications.

`FEISHU_IT_TEST=SupplierWorkflowTimerMysqlIT python3 scripts/tests/run_wiki_mysql.py` additionally checks real HTTP multipart upload, pause/resume and completion by the actual background timer without manual worker calls.

Default Maven suite checks existing TEXT/WIKI and supplier gates. Build frontend and production Docker images; perform in-app-only HTTP smoke through running Nginx using uniquely named fixtures.

## 7. Wrong vs Correct
Wrong: increment a node index in a virtual thread, call best-effort `notifyUser()` and mark delivery SENT, or accept the same human response twice.
Correct: complete the scoped Flowable task in a shared DB transaction, persist the attempt/outbox, claim once after commit, retain provider errors, and keep an explicit recovery task for interrupted external work. Pause/cancel stops subsequent progression; it cannot undo already dispatched external operations.
