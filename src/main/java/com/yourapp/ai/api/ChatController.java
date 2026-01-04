package com.yourapp.ai.api;

import com.yourapp.ai.agent.AgentAnswer;
import com.yourapp.ai.agent.AgentOrchestrator;
import com.yourapp.ai.memory.ConversationMemory;
import com.yourapp.ai.model.ChatRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/chat")
public class ChatController {

  private final AgentOrchestrator agent;

  // ⚠️ Shared memory for testing (single conversation)
  private final ConversationMemory memory = new ConversationMemory();

  public ChatController(AgentOrchestrator agent) {
    this.agent = agent;
  }

  @PostMapping
  public AgentAnswer chat(@RequestBody ChatRequest req) {
    return agent.run(req.question(), memory);
  }
}
