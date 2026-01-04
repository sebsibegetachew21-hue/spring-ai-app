package com.yourapp.ai.api;

import com.yourapp.ai.agent.AgentAnswer;
import com.yourapp.ai.agent.AgentOrchestrator;
import com.yourapp.ai.rag.DocIngestor;
import java.io.IOException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ChatController {

  private final AgentOrchestrator agent;
  private final DocIngestor ingestor;

  public ChatController(AgentOrchestrator agent, DocIngestor ingestor) {
    this.agent = agent;
    this.ingestor = ingestor;
  }

  @PostMapping("/ingest")
  public String ingest() throws IOException {
    int chunks = ingestor.ingestClasspathDocs("classpath:/docs/*.txt");
    return "Ingested chunks: " + chunks;
  }

  @PostMapping("/chat")
  public AgentAnswer chat(@RequestBody ChatRequest req) throws Exception {
    return agent.run(req.question());
  }

  public record ChatRequest(String question) {}
}
