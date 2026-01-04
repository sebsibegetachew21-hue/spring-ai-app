package com.yourapp.ai.agent;

import com.yourapp.ai.memory.ConversationMemory;
import com.yourapp.ai.retreival.RetrievalResult;
import com.yourapp.ai.retreival.RetrieverService;
import com.yourapp.ai.tools.OrderTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AgentOrchestrator {

    private static final String ANSWER_SYSTEM_PROMPT = """
            You are a backend assistant.

            Rules:
            - Use CONTEXT only for policies and documentation
            - Use TOOL_RESULT as authoritative system data
            - Use MEMORY only as reference
            - Do NOT guess
            - Do NOT describe tool results as simulated or hypothetical
            - If information is missing, say so
            """;


    private static final String PLANNER_SYSTEM_PROMPT = """
            You are an agent planner.

            Your job is to decide which actions are required to answer the user's question.

            You may choose:
            - retrieval (for policies, documentation, FAQs)
            - tool usage (for system data like order status)
            - both
            - or neither

            IMPORTANT RULES:

            1. If the question mentions ANY of:
               - return
               - refund
               - damaged
               - policy
               - eligibility
               - warranty
               → retrieval IS REQUIRED

            2. If the question asks about a specific order status
               → tool usage IS REQUIRED

            3. If the question contains multiple intents
               → BOTH retrieval AND tool usage may be required

            4. Memory may resolve references like "it" or "the order",
               but memory does NOT replace retrieval for policies.

            Respond ONLY in JSON with this exact shape:

            {
              "needsRetrieval": true|false,
              "needsTool": true|false,
              "toolName": "getOrderStatus" | null,
              "toolArgument": "<orderId>" | null
            }
            """;


    private final ChatClient chatClient;
    private final OrderTools orderTools;
    private final RetrieverService retriever;

    public AgentOrchestrator(
            ChatClient chatClient,
            OrderTools orderTools,
            RetrieverService retriever
    ) {
        this.chatClient = chatClient;
        this.orderTools = orderTools;
        this.retriever = retriever;
    }

    // use chatClient for BOTH planner and answer


    /**
     * Main agent entry point
     */
    public AgentAnswer run(String question, ConversationMemory memory) {

        /* -------------------------------------------------
         * 1. Enrich question with MEMORY (read-only)
         * ------------------------------------------------- */
        String enrichedQuestion = question;

        if (memory.contains("lastOrderId")) {
            enrichedQuestion +=
                    "\n\nPrevious context: lastOrderId=" + memory.get("lastOrderId");
        }

        /* -------------------------------------------------
         * 2. PLAN (LLM #1)
         * ------------------------------------------------- */
        AgentPlan plan = plan(enrichedQuestion);

        System.out.println("AGENT PLAN = " + plan);

        /* -------------------------------------------------
         * 3. RETRIEVE (RAG)
         * ------------------------------------------------- */
        String contextBlock = "";
        List<String> citations = List.of();

        if (plan.needsRetrieval()) {
            RetrievalResult retrieval = retriever.retrieve(question);
            contextBlock = "CONTEXT:\n" + retrieval.context() + "\n\n";
            citations = retrieval.citations();
        }

        /* -------------------------------------------------
         * 4. TOOL EXECUTION (deterministic, code-owned)
         * ------------------------------------------------- */
        String toolResultBlock = "";

        if (plan.needsTool() && plan.toolArgument() != null) {
            String orderId = plan.toolArgument();

            Map<String, Object> result =
                    orderTools.getOrderStatus(orderId);

            // Persist to MEMORY (write-only by code)
            memory.put("lastOrderId", orderId);
            memory.put("lastOrderStatus", result.get("status"));

            toolResultBlock =
                    "TOOL_RESULT:\n" + result + "\n\n";
        }

        /* -------------------------------------------------
         * 5. ANSWER (LLM #2)
         * ------------------------------------------------- */
        String memoryBlock = "";

        if (!memory.snapshot().isEmpty()) {
            memoryBlock =
                    "MEMORY (read-only):\n" +
                            memory.snapshot() +
                            "\n\n";
        }

        String finalPrompt =
                memoryBlock +
                        "QUESTION:\n" + question + "\n\n" +
                        contextBlock +
                        toolResultBlock +
                        """
                                Answer the question using:
                                - CONTEXT for policies and documentation
                                - TOOL_RESULT for system data
                                - MEMORY only as reference
                                                
                                Rules:
                                - Do NOT guess
                                - Do NOT describe tool results as simulated
                                - If information is missing, say so
                                """;

        String answer =
                chatClient.prompt()
                        .system(system -> system.text(ANSWER_SYSTEM_PROMPT))
                        .user(user -> user.text(finalPrompt))
                        .call()
                        .content();


//        String answer =
//                response.getResult().getOutput().getText();

        List<String> finalCitations =
                plan.needsRetrieval() ? citations : List.of();

        return new AgentAnswer(answer, finalCitations, "medium");
    }

    /* -------------------------------------------------
     * PLANNER (LLM-based, intent only)
     * ------------------------------------------------- */
    private AgentPlan plan(String enrichedQuestion) {

        String plannerPrompt =
                """
                        You are an AI planner.
                                
                        You MUST decompose the user's question into independent intents.
                        You MUST NOT answer the question.
                                
                        Policy Intent:
                        - refund
                        - return
                        - damaged item
                        - policy
                        - FAQ
                        - terms
                                
                        Operational Intent:
                        - order status
                        - status of order
                        - tracking
                        - delivery
                        - shipment
                        - ANY numeric identifier
                                
                        Rules:
                        - If policy intent → needsRetrieval = true
                        - If operational intent → needsTool = true
                        - If a number is present, extract it as toolArgument
                                
                        Return ONLY valid JSON:
                        {
                          "needsRetrieval": true|false,
                          "needsTool": true|false,
                          "toolArgument": "string" | null
                        }
                        """;

        String json =
                chatClient.prompt()
                        .system(system -> system.text(PLANNER_SYSTEM_PROMPT))
                        .user(user -> user.text(enrichedQuestion))
                        .call()
                        .content();

        return PlannerOutputParser.parse(json);
    }
}
