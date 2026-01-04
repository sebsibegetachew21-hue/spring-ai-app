package com.yourapp.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourapp.ai.rag.Retriever;
import com.yourapp.ai.tools.OrderTools;
import java.util.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

@Service
public class AgentOrchestrator {

  private final ChatClient chatClient;
  private final Retriever retriever;
  private final OrderTools orderTools;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public AgentOrchestrator(
          ChatClient.Builder builder,
          Retriever retriever,
          OrderTools orderTools
  ) {
    this.chatClient = builder.build();
    this.retriever = retriever;
    this.orderTools = orderTools;
  }

  /**
   * Entry point for the agent.
   * This method enforces a strict 2-call agent loop:
   * 1) PLAN
   * 2) ANSWER
   */
  public AgentAnswer run(String question) {
    try {
      // 1️⃣ PLAN (LLM Call #1)
      AgentPlan plan = plan(question);

      // 2️⃣ RETRIEVE (optional)
      String context = "";
      List<String> citations = new ArrayList<>();

      if (plan.needsRetrieval()) {
        List<Document> docs = retriever.topK(question, 6);

        StringBuilder ctx = new StringBuilder();
        for (Document d : docs) {
          String path = String.valueOf(d.getMetadata().getOrDefault("path", "unknown"));
          String chunkStart = String.valueOf(d.getMetadata().getOrDefault("chunkStart", "?"));

          citations.add(path + "#chunkStart=" + chunkStart);

          ctx.append("\n[DOC] ")
                  .append(path)
                  .append(" (chunkStart=")
                  .append(chunkStart)
                  .append(")\n")
                  .append(d.getText())
                  .append("\n");
        }
        context = ctx.toString();
      }

      // 3️⃣ TOOL CALL (optional)
      String toolResult = "";

      if (plan.needsTool()) {
        if ("getOrderStatus".equals(plan.toolName())) {
          Map<String, Object> result =
                  orderTools.getOrderStatus(plan.toolArgument());
          toolResult = result.toString();
        }
      }

      // 4️⃣ ANSWER (LLM Call #2)
      String answer = chatClient.prompt()
              .system("""
You are a backend assistant.

Rules:
- Use CONTEXT only for company policies or documents.
- Use TOOL_RESULT only if it is provided.
- NEVER guess or simulate live data.
- If information is missing, say so clearly.
""")
              .user("""
QUESTION:
%s

CONTEXT:
%s

TOOL_RESULT:
%s
""".formatted(question, context, toolResult))
              .call()
              .content();

      return new AgentAnswer(answer, citations, "medium");

    } catch (Exception e) {
      throw new RuntimeException("Agent execution failed", e);
    }
  }

  /**
   * Planner step (LLM Call #1).
   * Decides whether retrieval and/or tools are required.
   */
  private AgentPlan plan(String question) throws Exception {

    String json = chatClient.prompt()
            .system("""
You are an AI planner.

Your job is to decide what actions are required.
You MUST NOT answer the question.

Rules:
- Return ONLY valid JSON.
- If company documents are required, set needsRetrieval=true.
- If live order data is required, set needsTool=true.
- Use toolName=getOrderStatus for order status queries.
- Extract orderId if present.
- If no tool is needed, set needsTool=false.

JSON schema:
{
  "needsRetrieval": true|false,
  "needsTool": true|false,
  "toolName": "getOrderStatus" | null,
  "toolArgument": "string" | null
}
""")
            .user(question)
            .call()
            .content();

    return objectMapper.readValue(json, AgentPlan.class);
  }
}
