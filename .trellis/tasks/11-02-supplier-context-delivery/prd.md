# Supplier context delivery

User authorizes autonomous implementation, testing in OrbStack, commits and push.

## Delivered scope
- Preserve existing per-supplier Markdown knowledge store and manual chat/note ingestion.
- Attach knowledge to every custom agent node even without a docs placeholder.
- Read actual parsed text before dispatch and codegen prompt generation. Reject unresolved, unreadable, empty or oversized entries rather than silently dropping them.
- Refresh knowledge when resuming a blocked node.
- Archive confirmed node artifacts and manual corrections into the supplier knowledge store, maintaining one source per job/node.
- Verify supplier isolation and failure behavior using real temporary files and repository mocks.

## Limits
Text delivery proves inclusion in the request, not model comprehension. Skill/spec references remain governed by the existing workflow instructions. Postman execution still depends on the configured agent tools; this change does not verify external HTTP calls. Large libraries must be curated to the explicit 120,000 character budget. Versioned artifacts and tool-level enforcement are future work.
