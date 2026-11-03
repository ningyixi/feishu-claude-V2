# Windows migration handoff and Wiki storage explanation

## Request
Write and push a reviewable migration document for the user's primary Windows machine, which has legacy H2 data and no Docker. Explain the implemented LLM Wiki architecture and its middleware requirements.

## Scope
Documentation only. Treat Windows legacy data as authoritative, preserve source data and external knowledge files, separate current tools from pending native Windows support, document verification/rollback and cross-platform paths/table-name case. Explain actual Java compiler + MySQL metadata + filesystem Wiki + existing model execution; no vector DB is used.

## Completion
Cross-check all statements against code/configuration and official MySQL platform documentation, validate Markdown links and Trellis context, commit and push without further permission. Do not run an actual migration or claim Windows/source-data verification without that machine and its files.

## Delivered
- Windows handoff document with prerequisites, backup inventory, authoritative data, native-tool gaps, ordered migration, file/path mapping, Flowable case sensitivity, startup example and acceptance/rollback.
- Wiki storage explanation cross-checked against compiler, contract, snapshot, knowledge and workflow services.
- Cross-links from existing deployment and Wiki usage docs; database spec records native/case limitations.
- Documentation-only: no migration executed and no actual Windows compatibility claim.
