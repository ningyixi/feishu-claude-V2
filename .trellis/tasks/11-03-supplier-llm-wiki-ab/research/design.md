# Research direction

Primary source: https://gist.github.com/karpathy/442a6bf555914893e9891c11519de94f
Use raw/wiki/schema separation, ingest/query/lint operations, explicit index and provenance rather than semantic chunk retrieval.

Project-specific requirements: supplier isolation, TEXT/WIKI A/B mode persisted on job creation, lifecycle stage entrypoints, source-version invalidation, validated publication of generated pages, compact runtime context with links to complete raw evidence, visible compilation errors and no silent text fallback.

## GitHub MCP review (before implementation)
- atomicstrata/llm-wiki-compiler, README and src/compiler/candidate-publication.ts + src/context/build-reads.ts, reviewed at commit 0f8ff86ef0a28f4c15627a2ea5e94a96cadd834e. Useful patterns: derived knowledge, bounded publication and explicit provenance/read boundaries. Its hybrid semantic retrieval is not needed for this A/B branch.
- AgriciDaniel/claude-obsidian, README and claude_obsidian/transaction.py, commit 2e545563172f97f02ecaaaab376ceea1246a0583. Useful patterns: model returns drafts, application controls writes; precondition hashes and recoverable publication. Adopt immutable revision directories plus a database publication state instead of cloning its Python transaction system.
- Astro-Han/karpathy-llm-wiki, README and scripts/check_evidence.py, commit eafcc77001e496cc43499e4923b663aec722c813. Useful pattern: mechanical provenance checks separate from semantic judgment. Unlike its immutable input assumption, current application knowledge entries can be edited, so explicit source digests are required here.

## Chosen architecture
Keep current TEXT mode unchanged. Persist TEXT/WIKI on job creation; reject mutation after creation. WIKI uses supplier-scoped immutable raw snapshots, cached per-source extraction, synthesized linked pages, schema/index/log and declared phase entrypoints. Compile through existing isolated Claude lanes, so no additional model service or vector middleware is necessary. Validate page IDs, source coverage, verbatim evidence quotes, links and stage entrypoints before publishing a revision. Changed inputs make old revisions stale. Runtime always delivers the index, critical constraints and current-stage entry pages within a fixed budget; complete pages/raw evidence remain addressable by revision and ID. No silent fallback to text. Record mode, source revision, context characters and observed model usage for A/B analysis. Structural checks do not prove semantic completeness; citations and raw originals remain available for review.
