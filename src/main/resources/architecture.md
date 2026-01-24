# Architecture Diagram

```mermaid
flowchart TD
    A[Client] -->|POST /chat| B[ChatController]
    B -->|conversationId| C[MemoryStore]
    C -->|ConversationMemory| D[AgentOrchestrator]

    D -->|PLAN (llama3.2:3b)| E[Planner LLM]
    D -->|RETRIEVE| F[RetrieverService]
    F -->|similaritySearch| G[pgvector]
    G --> F
    F -->|context + citations| D

    D -->|TOOL| H[OrderTools.getOrderStatus]
    H -->|writes| C

    D -->|ANSWER (mistral:7b-instruct)| I[Answer LLM]
    I --> D

    D -->|AgentAnswer| B
    B -->|JSON response| A
```

## Notes
- Planner and answer models are routed separately via `app.models.planner` and `app.models.answer`.
- Memory is stored per `conversationId` (Redis or in-memory).
- Retrieval is only for policy/FAQ docs; tools are only for system data.

## Deployment Diagram

```mermaid
flowchart LR
    subgraph Client
        A[User / API Client]
    end

    subgraph SpringAI["Spring AI App"]
        B[Spring Boot App]
        C[AgentOrchestrator]
    end

    subgraph Infra["Local Infra"]
        D[Ollama :11434]
        E[Postgres + pgvector :5432]
        F[Redis :6379]
        G[Jaeger UI :16686]
        H[OTLP Collector :4318]
    end

    A -->|HTTP POST /chat| B
    B --> C
    C -->|planner/answer| D
    C -->|vector search| E
    C -->|memory store| F
    B -->|traces| H
    H --> G
```

## Sequence Diagram

```mermaid
sequenceDiagram
    participant Client
    participant Controller as ChatController
    participant Memory as MemoryStore
    participant Orchestrator as AgentOrchestrator
    participant Planner as Planner LLM
    participant Retriever as RetrieverService
    participant Vector as pgvector
    participant Tool as OrderTools
    participant Answer as Answer LLM

    Client->>Controller: POST /chat
    Controller->>Memory: get(conversationId)
    Controller->>Orchestrator: run(question, memory)
    Orchestrator->>Planner: PLAN (llama3.2:3b)
    Planner-->>Orchestrator: AgentPlan JSON
    opt needsRetrieval
        Orchestrator->>Retriever: retrieve(question)
        Retriever->>Vector: similaritySearch
        Vector-->>Retriever: docs + metadata
        Retriever-->>Orchestrator: context + citations
    end
    opt needsTool
        Orchestrator->>Tool: getOrderStatus(orderId)
        Tool-->>Orchestrator: TOOL_RESULT
        Orchestrator->>Memory: put(lastOrderId, lastOrderStatus)
    end
    Orchestrator->>Answer: ANSWER (mistral:7b-instruct)
    Answer-->>Orchestrator: final answer
    Orchestrator-->>Controller: AgentAnswer
    Controller-->>Client: JSON response
```

## Data Flow Diagram

```mermaid
flowchart TD
    A[Docs: policy.txt, faq.txt] --> B[DocIngestor]
    B -->|chunks + metadata| C[Vector Store: pgvector]
    D[User Question] --> E[RetrieverService]
    E -->|similaritySearch| C
    C -->|context + citations| E
    E --> F[AgentOrchestrator]
    G[OrderTools] --> F
    H[ConversationMemory] <--> F
    F --> I[Answer LLM]
    I --> J[AgentAnswer]
```
