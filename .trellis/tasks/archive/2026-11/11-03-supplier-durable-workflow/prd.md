# Durable supplier workflow with human tasks

## Goal
Replace manual node-by-node orchestration with a persisted workflow engine that advances automatically, requests approvals/answers/files, notifies via Feishu, and resumes after human response.

## Requirements and defaults
- Explicitly start a managed workflow on an untouched existing/new supplier job. Existing manual jobs remain compatible until started.
- Embedded Flowable BPMN engine backed by the same OrbStack MySQL. Generate and pin a process definition from the job's node snapshot.
- Manual/reminder nodes become human tasks. Agent nodes run automatically, route blockers/errors to input tasks, and route successful results to approval tasks. Approval advances; rejection returns the same node for revision with feedback.
- Persistent tasks, execution attempts, history and notification outbox. Duplicate responses do not execute twice. Pause/resume/cancel; interrupted external execution requires explicit human retry rather than blind replay.
- Feishu notification to explicitly selected recipient, with task URL and deterministic reply command. Workbench has equivalent approval/answer/file upload forms and shows delivery failures.
- Upload PDF or UTF-8 text materials into this supplier KB, retain original file and evidence association; reject invalid/oversized or foreign task input. Existing TEXT/WIKI, spec/skill/Trellis/probe gates remain mandatory.
- No live outbound messages during implementation/testing; model and Feishu boundaries stubbed in integration tests.

## Acceptance
Tests must exercise approval, rejection/rework, missing input and files, duplicate/racing submissions, source isolation, restart recovery, pause/cancel and notification retry. MySQL integration and running OrbStack UI/API build/health checks before automatic commit/push.

## Decision
Use embedded Flowable 7.2 BPMN user tasks, receive tasks and gateways, with application worker dispatch and durable attempt receipts. Compared with Temporal, avoids another runtime service for this local-only project. Compared with another handwritten status chain, actual process/task/history state belongs to a proven engine. No raw user-authored executable BPMN import in this iteration.

## Out of scope
Arbitrary BPMN graphical designer, parallel approvals, cloud deployment, organization-wide identity/SSO. Existing application's local trusted-admin API boundary remains; Feishu commands additionally check bound instance and configured recipient.

## Research
See research/engine.md. User has already authorized autonomous implementation/commit/push, so inferred product defaults do not require another confirmation.

## Delivery and validation
- Embedded Flowable 7.2 with pinned BPMN definitions, task/history persistence, dedicated worker and durable execution claims.
- Approval, rejection feedback, input/file tasks, automatic Wiki preparation, pause/resume/cancel and uncertain-execution recovery.
- Feishu deterministic reply commands and retryable outbox; no live notifications sent during tests.
- 80 default tests passed; 5 real MySQL workflow integration scenarios and a real HTTP/background-timer scenario passed (model/Feishu boundaries stubbed); existing Wiki/MySQL regression passed. Frontend and production Docker builds passed, running Nginx/backend/MySQL smoke verified.
- Corrected lost rejection feedback in custom prompts, MySQL snapshot/locking behavior found by concurrency tests, and cross-catalog engine schema discovery when starting a second local database.
- Docs: docs/供应商持续工作流.md and backend/supplier-workflow.md.
- Real provider delivery and real-model quality still require a configured bound instance; this local store currently has no such instance.
