# spring-ai-app

Production-style agentic RAG system built with Spring Boot + Spring AI, following the official Spring AI concepts:
https://docs.spring.io/spring-ai/reference/concepts.html

This is not a demo chatbot. It is an explicitly orchestrated agent with tools, retrieval, and memory.

## Stack
- Java + Spring Boot
- Spring AI
- Ollama (Docker)
- Chat model: `mistral:7b-instruct`
- Embeddings: `nomic-embed-text`
- Vector DB: pgvector (Postgres)
- REST API: `POST /chat`

## Architecture (Bounded Agent Loop)
PLAN -> RETRIEVE -> TOOL -> ANSWER

Rules:
- Maximum 2 LLM calls per request
- Planner returns strict JSON only (no prose)
- Memory is owned by application code, not the LLM
- Tools are deterministic Java methods
- Retrieval is used only for policies / documentation
- Tool calls are used only for system data (for example, order status)

## Planner Contract
Planner output JSON shape:

```json
{
  "needsRetrieval": true,
  "needsTool": false,
  "toolName": "getOrderStatus",
  "toolArgument": "12345"
}
```

Planner rules:
- If question mentions return / refund / damaged / policy -> retrieval required
- If question asks about order status -> tool required
- Multi-intent questions may require both
- Memory may resolve references like "it"
- Memory never replaces retrieval for policies

## Memory Design
- In-code ConversationMemory
- Tools write memory (for example, lastOrderId, lastOrderStatus)
- Planner reads memory to resolve references ("it", "the order")
- LLM receives a read-only snapshot of memory
- Memory is currently in-memory (testing mode)

Example memory snapshot:

```json
{
  "lastOrderId": "12345",
  "lastOrderStatus": "IN_TRANSIT"
}
```

## Tools
Example tool signature:

```java
Map<String, Object> getOrderStatus(String orderId)
```

Rules:
- Tools are deterministic
- Tools write to memory
- LLM never mutates memory

## RAG (Retrieval-Augmented Generation)
- Used only for policies, FAQs, and documentation
- Vector store: pgvector (Postgres)
- Retriever returns chunks + citations
- Answer prompt must not introduce policy info unless retrieval occurred

## Current Status
- Tool calling works
- RAG works
- Memory works (resolves "it" correctly)
- Multi-intent questions work after planner prompt fix
- Citations currently show "classpath" (acceptable for now)
- Answer tone slightly verbose (prompt polish optional)

Verified interaction:
1. "What is the status of order 12345?"
2. "Can I return it if it arrives damaged?"
-> Correct tool + memory + retrieval behavior

## Current Behavior
- API: `POST /chat` accepts `{"question":"..."}` and returns `{"answer","citations","confidence"}`.
- Planner: strict JSON with `needsRetrieval`, `needsTool`, `toolName`, `toolArgument`.
- Planner validation: strict schema and tool allowlist enforced in parser.
- Deterministic override: if operational intent + orderId detected, tool is forced to run or backfilled.
- Retrieval: pgvector similarity search over ingested docs with citations.
- Tools: deterministic Java methods (for example, `getOrderStatus`) that write to in-memory `ConversationMemory`.
- Memory: in-memory only, shared in `ChatController` (single conversation).
- Observability: SLF4J phase logs, Micrometer tracing (OTLP), request ID propagation, and custom timers/counters.

## Planned Work
Core:
1. Add planner unit tests
2. Make memory production-safe (conversationId, MemoryStore, eviction)
3. Improve answer prompt discipline

Stability / quality:
4. Normalize citations
5. Add agent phase logging with timing
6. Add negative-path handling

Extensibility (optional):
7. Split planner and answer models
8. Add Kafka as a tool
9. Persist memory externally (Redis)

Documentation:
10. Add architecture diagram
11. Expand README with local run/test steps

Cleanup:
12. Remove temporary testing shortcuts and enforce conversationId usage

## Enterprise Readiness Plan (Next)
Phase 1: Reliability and Contracts
1. Add request validation and typed error responses (400/422/500) with structured error payloads.
2. Harden planner contract: schema versioning, allowlist, and explicit rejection paths.
3. Add negative-path handling: missing orderId, tool failures/timeouts, retrieval empty, model errors.

Phase 2: Memory and Scaling
4. Add TTL/eviction for in-memory MemoryStore to prevent unbounded growth.
5. Implement RedisMemoryStore with per-conversation isolation, TTL, and health checks.
6. Propagate conversationId into logs/metrics/traces for multi-tenant debugging.

Phase 3: Model and Performance
7. Split planner vs answer models (smaller planner model, larger answer model).
8. Add timeouts, retries, and circuit breakers around model/tool calls.
9. Tune prompt length, context limits, and retrieval topK for latency control.

Phase 4: Governance and Ops
10. Normalize citations with stable doc IDs and versioned ingestion.
11. Add authn/authz and rate limiting on `/chat`.
12. Add dashboards/alerts for latency, error rate, and token usage.

## Important Guidelines
- Do not suggest "just let the LLM handle it"
- Do not hide logic inside prompts
- Do not turn memory into chat history
- Do not exceed the bounded agent loop
- Prefer explicit Java code over clever prompt tricks
- Assume an experienced backend engineer audience
