package com.yourapp.ai.api;

import com.yourapp.ai.agent.AgentAnswer;
import com.yourapp.ai.agent.AgentOrchestrator;
import com.yourapp.ai.memory.ConversationMemory;
import com.yourapp.ai.memory.MemoryStore;
import com.yourapp.ai.model.ChatRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/chat")
public class ChatController {

  private final AgentOrchestrator agent;
  private final MemoryStore memoryStore;

  public ChatController(AgentOrchestrator agent, MemoryStore memoryStore) {
    this.agent = agent;
    this.memoryStore = memoryStore;
  }

  @PostMapping
  public AgentAnswer chat(@RequestBody ChatRequest req) {
    String conversationId = req.conversationId();
    if (conversationId == null || conversationId.isBlank()) {
      conversationId = "default";
    }

    String key = conversationId;
    ConversationMemory memory =
        memoryStore.get(key).orElseGet(() -> {
          ConversationMemory created = new ConversationMemory();
          memoryStore.put(key, created);
          return created;
        });

    AgentAnswer answer = agent.run(req.question(), memory);
    memoryStore.put(key, memory);
    return answer;
  }

  @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Flux<String> chatStream(@RequestBody ChatRequest req) {
    String conversationId = req.conversationId();
    if (conversationId == null || conversationId.isBlank()) {
      conversationId = "default";
    }

    String key = conversationId;
    ConversationMemory memory =
        memoryStore.get(key).orElseGet(() -> {
          ConversationMemory created = new ConversationMemory();
          memoryStore.put(key, created);
          return created;
        });

    Flux<String> stream = agent.runStream(req.question(), memory);
    memoryStore.put(key, memory);
    return stream;
  }
}
