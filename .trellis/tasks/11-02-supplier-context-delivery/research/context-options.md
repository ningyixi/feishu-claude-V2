# Context management research

Sources reviewed:
- https://github.com/agentskills/agentskills/blob/main/docs/home.mdx — progressive disclosure of procedural knowledge.
- https://github.com/openai/openai-agents-python/blob/main/docs/context.md — local application data is distinct from model-visible context; deliver it through instructions, input or tools.
- https://github.com/google-gemini/gemini-cli/blob/main/docs/hooks/reference.md — runtime hooks can inject per-turn context.

Decision: retain the current supplier-scoped text store instead of introducing a vector database. Add deterministic application-side text delivery and readiness checks at dispatch. Existing document parsing, manual chat capture and source paths remain useful. Skills offer discovery but do not themselves guarantee retrieval; a prompt containing only paths is weaker than delivering text. Full-text search/RAG may later help when libraries exceed the explicit input budget, but cannot replace required-material checks.

Acceptance: actual text appears in dispatched node prompts; custom templates cannot omit the supplier index; missing/unparsed/oversized text fails visibly; confirmed artifacts re-enter the same supplier library. Delivery does not claim comprehension, Postman provenance, or tool enforcement.
