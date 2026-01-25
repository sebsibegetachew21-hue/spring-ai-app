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

See detailed diagram: `src/main/resources/architecture.md`.

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
- Memory is stored per `conversationId` (Redis or in-memory)

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
- Retrieval enforces a similarity threshold and can expand referenced policies
- Answer prompt must not introduce policy info unless retrieval occurred

## Current Status
- Tool calling works
- RAG works
- Memory works (resolves "it" correctly) with Redis or in-memory store
- Multi-intent questions work after planner prompt fix
- Citations include policy metadata (for example, `policy_refunds#chunk0`)
- Streaming endpoint is available (buffered SSE)
- JWT security is enabled (Auth0 issuer + audience)

Verified interaction:
1. "What is the status of order 12345?"
2. "Can I return it if it arrives damaged?"
-> Correct tool + memory + retrieval behavior

## Current Behavior
- API: `POST /chat` accepts `{"conversationId":"...","question":"..."}` and returns `{"answer","citations","confidence"}`.
- Streaming: `POST /chat/stream` returns SSE with buffered response chunks.
- Planner: strict JSON with `needsRetrieval`, `needsTool`, `toolName`, `toolArgument`.
- Planner validation: strict schema and tool allowlist enforced in parser.
- Deterministic override: if operational intent + orderId detected, tool is forced to run or backfilled.
- Model routing: separate planner vs answer models via `app.models.planner` and `app.models.answer`.
- Cross-policy retrieval: referenced policies are expanded during retrieval.
- Timing extraction: policy answers must include numeric timing when asked.
- Retrieval: pgvector similarity search over ingested docs with a relevance threshold.
- Policy metadata: `policyId`, `title`, and `chunkIndex` used for traceable citations.
- Tools: deterministic Java methods (for example, `getOrderStatus`) that write to `ConversationMemory`.
- Memory: Redis or in-memory `MemoryStore` with per-conversation isolation and TTL.
- Observability: SLF4J phase logs, Micrometer tracing (OTLP), request ID propagation, and custom timers/counters.
- Security: JWT validation against Auth0 issuer + audience with scope `chat:access` on `/chat/**`.

## Sample Request
```bash
curl -s -X POST http://localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <access_token>' \
  -d '{"conversationId":"demo1","question":"Can I return a damaged item and what is the status of order 12345?"}'
```

Expected response (shape):
```json
{
  "answer": "Policy: ...\\n\\nSystem: ...",
  "citations": ["policy_returns#chunk0"],
  "confidence": "medium"
}
```

Policy-only example:
```bash
TOKEN=$(./scripts/get-token.sh | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p') && \
curl -s -X POST http://localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"conversationId":"demo2","question":"What is the refund timing for damaged items?"}'
```

## Streaming Request
```bash
curl -N -s -X POST http://localhost:8080/chat/stream \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <access_token>' \
  -d '{"conversationId":"demo1","question":"Can I return a damaged item and what is the status of order 12345?"}'
```

Expected response:
- SSE stream of text chunks (Policy/System sections).

## Spring AI Overview Feature Mapping (Text Only)
Implemented:
- Chat model support (Ollama).
- Embeddings (Ollama).
- Vector store (pgvector).
- RAG pipeline (retrieve context + citations).
- Tools/function calling (deterministic Java tools).
- Observability (tracing + logs + metrics).
- ChatClient API usage.
- Conversation memory (app-owned, Redis/in-memory).
- Spring Boot auto-configuration for model + vector store.
- Streaming responses (buffered SSE, not token streaming).

Not implemented (by design or pending):
- Structured outputs (POJO mapping) for answers.
- Advisors API (not used; explicit orchestration instead).
- ETL framework for ingestion (manual DocIngestor used).
- AI model evaluation utilities.

## Planned Work
Core:
1. Add planner unit tests
2. Improve answer prompt discipline

Stability / quality:
3. Normalize citations with stable, versioned doc IDs
4. Add structured error payloads (400/422/500)

Extensibility (optional):
5. Add Kafka as a tool
6. Add streaming token support (true token streaming)

Documentation:
10. Add architecture diagram
11. Expand README with local run/test steps

Cleanup:
12. Remove temporary testing shortcuts and enforce conversationId usage

## Enterprise Readiness Plan (Status)
Completed:
1. Harden planner contract: schema validation and tool allowlist.
2. Add negative-path handling: missing orderId, tool failures/timeouts, retrieval empty.
3. Implement RedisMemoryStore with per-conversation isolation and TTL.
4. Propagate conversationId into logs/metrics/traces for multi-tenant debugging.
5. Split planner vs answer models (smaller planner model, larger answer model).
6. Add authn/authz on `/chat` with JWT + scope.

Next:
1. Add request validation and typed error responses (400/422/500) with structured payloads.
2. Add timeouts, retries, and circuit breakers around model/tool calls.
3. Tune prompt length, context limits, and retrieval topK for latency control.
4. Normalize citations with stable doc IDs and versioned ingestion.
5. Add dashboards/alerts for latency, error rate, and token usage.

## Priority Order (Value + Learning)
1. Negative-path handling (robustness and user experience). ✅
2. RedisMemoryStore with TTL (scaling and durability). ✅
3. Model routing (cost/latency optimization).

## Handoff / Resume
Last updated: 2026-01-25

Current state:
- Planner/Answer split is live: planner uses `llama3.2:3b`, answer uses `mistral:7b-instruct`.
- RedisMemoryStore is enabled via `app.memory.store: redis` with TTL config.
- Negative-path handling covers missing orderId, tool failures, and empty retrieval.
- Observability (logs + metrics + traces) is enabled with Jaeger/OTLP.
- Architecture diagrams live at `src/main/resources/architecture.md`.
- JWT security enabled (Auth0 issuer + audience).
- Buffered streaming is available at `/chat/stream`.

Next steps:
1. Optional sanitizer tightening to eliminate remaining hedging.
2. TTL/eviction for in-memory MemoryStore (if needed for local fallback).
3. Add planner unit tests to lock routing behavior.

Quick sanity checks:
- `redis-cli -h localhost -p 6379 get 'memory:r1'` shows stored memory.
- Planner spans use `llama3.2:3b`; answer spans use `mistral:7b-instruct`.

## Important Guidelines
- Do not suggest "just let the LLM handle it"
- Do not hide logic inside prompts
- Do not turn memory into chat history
- Do not exceed the bounded agent loop
- Prefer explicit Java code over clever prompt tricks
- Assume an experienced backend engineer audience
