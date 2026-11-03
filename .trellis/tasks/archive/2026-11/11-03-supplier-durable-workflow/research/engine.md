# Engine selection

Reviewed official Flowable sources/docs on 2026-11-03:
- https://github.com/flowable/flowable-engine/releases/tag/flowable-7.2.0 — 7.x Spring 6 / Boot 3 generation. Current project Boot 3.4.1; use flowable-spring directly, avoiding starter auto-config and unrelated engines.
- https://github.com/flowable/flowable-engine/releases — 8.0 requires Spring 7 / Boot 4, so do not select latest major blindly.
- https://www.flowable.com/open-source/docs/bpmn/ch04-API — TaskService and durable user tasks.
- https://www.flowable.com/open-source/docs/bpmn/ch07b-BPMN-Constructs/#receive-task — receive tasks wait for explicit runtime trigger; gateways model review/rework and input loops.

Options: Temporal supports durable external activities but adds services and operational burden; a custom state machine is smaller but repeats engine task/history semantics. Chosen: embedded BPMN with same MySQL, explicit worker claim/dispatch and interrupted-activity human recovery. Database persistence does not imply exactly-once external API effects. Outbox has bounded retries, deterministic Feishu message UUID and visible failure state. No proprietary Flowable UI or cloud service required.
