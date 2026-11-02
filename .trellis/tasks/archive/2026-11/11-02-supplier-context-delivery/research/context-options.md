# Context management research

Sources reviewed:
- https://github.com/agentskills/agentskills/blob/main/docs/home.mdx — progressive disclosure of procedural knowledge.
- https://github.com/openai/openai-agents-python/blob/main/docs/context.md — local application data is distinct from model-visible context; deliver it through instructions, input or tools.
- https://github.com/google-gemini/gemini-cli/blob/main/docs/hooks/reference.md — runtime hooks can inject per-turn context.

Decision: retain the current supplier-scoped text store instead of introducing a vector database. Add deterministic application-side text delivery and readiness checks at dispatch. Existing document parsing, manual chat capture and source paths remain useful. Skills offer discovery but do not themselves guarantee retrieval; a prompt containing only paths is weaker than delivering text. Full-text search/RAG may later help when libraries exceed the explicit input budget, but cannot replace required-material checks.

Acceptance: actual text appears in dispatched node prompts; custom templates cannot omit the supplier index; missing/unparsed/oversized text fails visibly; confirmed artifacts re-enter the same supplier library. Delivery does not claim comprehension, Postman provenance, or tool enforcement.


Additional sources and implementation decisions:
- https://github.com/postmanlabs/postman-mcp-server — official execution tools and runtime requirement. Full MCP configuration is needed for execution tools. We capture actual CLI tool events instead of trusting model prose.
- https://github.com/anthropics/claude-agent-sdk-python/blob/main/src/claude_agent_sdk/types.py — hook/context integration surface. Runtime-specific hooks can be useful but do not replace application dispatch checks.

Compared approaches: vector retrieval helps semantic discovery but does not guarantee mandatory material delivery; skills package procedure but require activation; runtime hooks inject context at tool boundaries but depend on runtime support; deterministic server-side stage selection and preflight provide an auditable baseline independent of semantic ranking. Chosen combination: supplier-scoped Markdown, explicit stages, full company entrypoint text, immutable request/task snapshots, actual Postman events and application completion gates.

Compatibility finding from official source: runCollection delegates to Newman but formatUserOutput returns text summary, not the raw execution JSON. Sources: https://github.com/postmanlabs/postman-mcp-server/blob/main/dist/src/tools/runner/executor.js and https://github.com/postmanlabs/postman-mcp-server/blob/main/dist/src/tools/runner/parsers.js . The executor includes test assertion names in output. The dispatch protocol therefore supplies a per-request Postman Tests script using actual pm.request/pm.response data as a structured SUPPLIER_EVIDENCE assertion name. The backend accepts that record only inside a successful observed execution-tool result; a plain passed-tests summary remains insufficient.
