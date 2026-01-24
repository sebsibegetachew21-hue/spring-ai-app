package com.yourapp.ai.agent;

import com.yourapp.ai.memory.ConversationMemory;
import com.yourapp.ai.retreival.RetrievalResult;
import com.yourapp.ai.retreival.RetrieverService;
import com.yourapp.ai.tools.OrderTools;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private static final String ANSWER_SYSTEM_PROMPT = """
            You are a backend assistant.

            Rules:
            - Use CONTEXT only for policies and documentation
            - Use TOOL_RESULT as authoritative system data
            - Use MEMORY only as reference
            - If TOOL_RESULT is present, you MUST include it in the answer
            - Do NOT guess
            - Do NOT describe tool results as simulated or hypothetical
            - If information is missing, say so
            - Do NOT suggest contacting customer service or checking a website unless asked
            - Do NOT ask for more details unless the question cannot be answered with provided CONTEXT/TOOL_RESULT
            - Never downgrade or hedge authoritative TOOL_RESULT

            Output format:
            You will be told:
            - HAS_CONTEXT: true|false
            - HAS_TOOL_RESULT: true|false

            If HAS_CONTEXT=true and HAS_TOOL_RESULT=true:
            Policy:
            <policy answer>
            System:
            <tool result summary>

            If HAS_CONTEXT=true and HAS_TOOL_RESULT=false:
            Policy:
            <policy answer>

            If HAS_CONTEXT=false and HAS_TOOL_RESULT=true:
            System:
            <tool result summary>

            If HAS_CONTEXT=false and HAS_TOOL_RESULT=false:
            <single sentence: "I don't have enough information to answer.">

            You MUST follow the format exactly and only include the allowed sections.
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
               → BOTH retrieval AND tool usage are required when any operational intent is present

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


    private final ChatClient plannerChatClient;
    private final ChatClient answerChatClient;
    private final OrderTools orderTools;
    private final RetrieverService retriever;
    private final Timer plannerTimer;
    private final Timer answerTimer;
    private final Timer toolTimer;
    private final Timer retrievalTimer;

    public AgentOrchestrator(
            @Qualifier("plannerChatClient") ChatClient plannerChatClient,
            @Qualifier("answerChatClient") ChatClient answerChatClient,
            OrderTools orderTools,
            RetrieverService retriever,
            MeterRegistry meterRegistry
    ) {
        this.plannerChatClient = plannerChatClient;
        this.answerChatClient = answerChatClient;
        this.orderTools = orderTools;
        this.retriever = retriever;
        this.plannerTimer = Timer.builder("agent.planner.duration")
                .description("Planner LLM call duration")
                .register(meterRegistry);
        this.answerTimer = Timer.builder("agent.answer.duration")
                .description("Answer LLM call duration")
                .register(meterRegistry);
        this.toolTimer = Timer.builder("agent.tool.duration")
                .description("Tool execution duration")
                .register(meterRegistry);
        this.retrievalTimer = Timer.builder("agent.retrieval.duration")
                .description("Retrieval duration")
                .register(meterRegistry);
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
        plan = applyDeterministicOverrides(plan, question);
        AgentPlan finalPlan = plan;
        

        log.info("Agent plan {}", plan);

        if (plan.needsTool() && plan.toolArgument() == null) {
            return new AgentAnswer(
                    "Missing required orderId for tool execution.",
                    List.of(),
                    "low"
            );
        }
        if (plan.needsTool() && plan.toolArgument() != null
                && !plan.toolArgument().matches("\\d+")) {
            return new AgentAnswer(
                    "Invalid orderId for tool execution.",
                    List.of(),
                    "low"
            );
        }

        /* -------------------------------------------------
         * 3. RETRIEVE (RAG)
         * ------------------------------------------------- */
        String contextBlock = "";
        List<String> citations = List.of();

        if (plan.needsRetrieval()) {
            long startNanos = System.nanoTime();
            RetrievalResult retrieval = retriever.retrieve(question);
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            contextBlock = "CONTEXT:\n" + retrieval.context() + "\n\n";
            citations = retrieval.citations();
            retrievalTimer.record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            log.info("Retrieval completed durationMs={} citations={}", durationMs, citations.size());
            if (retrieval.context() == null || retrieval.context().isBlank()) {
                return new AgentAnswer(
                        "No relevant policy documents were found for this question.",
                        List.of(),
                        "low"
                );
            }
        }

        /* -------------------------------------------------
         * 4. TOOL EXECUTION (deterministic, code-owned)
         * ------------------------------------------------- */
        String toolResultBlock = "";

        if (plan.needsTool() && plan.toolArgument() != null) {
            String orderId = plan.toolArgument();

            log.info("Tool invocation name=getOrderStatus orderId={}", orderId);
            Map<String, Object> result;
            try {
                long startNanos = System.nanoTime();
                result = orderTools.getOrderStatus(orderId);
                long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                toolTimer.record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                log.warn("Tool invocation failed name=getOrderStatus orderId={}", orderId, e);
                return new AgentAnswer(
                        "Tool execution failed for orderId " + orderId + ".",
                        List.of(),
                        "low"
                );
            }

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

        String hasContext = plan.needsRetrieval() ? "true" : "false";
        String hasToolResult = toolResultBlock.isBlank() ? "false" : "true";
        String finalToolResultBlock = toolResultBlock;
       

        String finalPrompt =
                memoryBlock +
                        "QUESTION:\n" + question + "\n\n" +
                        "HAS_CONTEXT: " + hasContext + "\n" +
                        "HAS_TOOL_RESULT: " + hasToolResult + "\n\n" +
                        contextBlock +
                        toolResultBlock +
                        """
                                Answer the question using:
                                - CONTEXT for policies and documentation
                                - TOOL_RESULT for system data
                                - MEMORY only as reference
                                                
                                Rules:
                                - If TOOL_RESULT is present, you MUST include it
                                - Do NOT guess
                                - Do NOT describe tool results as simulated
                                - If information is missing, say so
                                - Separate policy info and system data clearly when both are present
                                - Do NOT suggest contacting customer service or checking a website unless asked
                                - Do NOT ask for more details unless the question cannot be answered with provided CONTEXT/TOOL_RESULT
                                - Output must match the required format exactly and contain only those sections
                                """;

        String answer =
                callAnswerModel(finalPrompt);
        answer = sanitizeAnswer(answer, plan.needsRetrieval(), !toolResultBlock.isBlank());


//        String answer =
//                response.getResult().getOutput().getText();

        List<String> finalCitations =
                plan.needsRetrieval() ? citations : List.of();

        return new AgentAnswer(answer, finalCitations, "medium");
    }

    public Flux<String> runStream(String question, ConversationMemory memory) {
        String enrichedQuestion = question;

        if (memory.contains("lastOrderId")) {
            enrichedQuestion +=
                    "\n\nPrevious context: lastOrderId=" + memory.get("lastOrderId");
        }

        AgentPlan plan = plan(enrichedQuestion);
        plan = applyDeterministicOverrides(plan, question);
        log.info("Agent plan {}", plan);

        if (plan.needsTool() && plan.toolArgument() == null) {
            return Flux.just("Missing required orderId for tool execution.");
        }
        if (plan.needsTool() && plan.toolArgument() != null
                && !plan.toolArgument().matches("\\d+")) {
            return Flux.just("Invalid orderId for tool execution.");
        }

        String contextBlock = "";
        if (plan.needsRetrieval()) {
            long startNanos = System.nanoTime();
            RetrievalResult retrieval = retriever.retrieve(question);
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            contextBlock = "CONTEXT:\n" + retrieval.context() + "\n\n";
            retrievalTimer.record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            log.info("Retrieval completed durationMs={} citations={}", durationMs, retrieval.citations().size());
            if (retrieval.context() == null || retrieval.context().isBlank()) {
                return Flux.just("No relevant policy documents were found for this question.");
            }
        }

        String toolResultBlock = "";
        if (plan.needsTool() && plan.toolArgument() != null) {
            String orderId = plan.toolArgument();
            log.info("Tool invocation name=getOrderStatus orderId={}", orderId);
            try {
                long startNanos = System.nanoTime();
                Map<String, Object> result = orderTools.getOrderStatus(orderId);
                long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                toolTimer.record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);

                memory.put("lastOrderId", orderId);
                memory.put("lastOrderStatus", result.get("status"));

                toolResultBlock = "TOOL_RESULT:\n" + result + "\n\n";
            } catch (Exception e) {
                log.warn("Tool invocation failed name=getOrderStatus orderId={}", orderId, e);
                return Flux.just("Tool execution failed for orderId " + orderId + ".");
            }
        }

        String memoryBlock = "";
        if (!memory.snapshot().isEmpty()) {
            memoryBlock =
                    "MEMORY (read-only):\n" +
                            memory.snapshot() +
                            "\n\n";
        }

        String hasContext = plan.needsRetrieval() ? "true" : "false";
        String hasToolResult = toolResultBlock.isBlank() ? "false" : "true";

        String finalPrompt =
                memoryBlock +
                        "QUESTION:\n" + question + "\n\n" +
                        "HAS_CONTEXT: " + hasContext + "\n" +
                        "HAS_TOOL_RESULT: " + hasToolResult + "\n\n" +
                        contextBlock +
                        toolResultBlock +
                        """
                                Answer the question using:
                                - CONTEXT for policies and documentation
                                - TOOL_RESULT for system data
                                - MEMORY only as reference
                                                
                                Rules:
                                - If TOOL_RESULT is present, you MUST include it
                                - Do NOT guess
                                - Do NOT describe tool results as simulated
                                - If information is missing, say so
                                - Separate policy info and system data clearly when both are present
                                - Do NOT suggest contacting customer service or checking a website unless asked
                                - Do NOT ask for more details unless the question cannot be answered with provided CONTEXT/TOOL_RESULT
                                - Output must match the required format exactly and contain only those sections
                                """;

        long startNanos = System.nanoTime();
        String content = answerChatClient.prompt()
                .system(system -> system.text(ANSWER_SYSTEM_PROMPT))
                .user(user -> user.text(finalPrompt))
                .call()
                .content();
        String sanitized = sanitizeAnswer(content, plan.needsRetrieval(), !toolResultBlock.isBlank());
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        log.info("LLM answer stream completed durationMs={}", durationMs);
        return Flux.just(sanitized);
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
            - ANY numeric identifier (treat as orderId if question mentions order/status/shipment)
                                
            Rules:
            - If policy intent → needsRetrieval = true
            - If operational intent → needsTool = true
            - If a number is present AND operational intent is present, extract it as toolArgument
            - If both intents are present, set needsRetrieval=true AND needsTool=true
            - toolName must be "getOrderStatus" when needsTool=true

            Return ONLY valid JSON:
            {
              "needsRetrieval": true|false,
              "needsTool": true|false,
              "toolName": "getOrderStatus" | null,
              "toolArgument": "string" | null
            }
                        """;

        long startNanos = System.nanoTime();
        String json =
                plannerChatClient.prompt()
                        .system(system -> system.text(PLANNER_SYSTEM_PROMPT))
                        .user(user -> user.text(enrichedQuestion))
                        .call()
                        .content();
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        plannerTimer.record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        log.info("LLM planner call completed durationMs={}", durationMs);

        return PlannerOutputParser.parse(json);
    }

    private AgentPlan applyDeterministicOverrides(AgentPlan plan, String question) {
        String lower = question.toLowerCase();
        boolean operational =
                lower.contains("order status")
                        || lower.contains("status of order")
                        || lower.contains("tracking")
                        || lower.contains("delivery")
                        || lower.contains("shipment")
                        || lower.contains("order ");

        String orderId = question.replaceAll(".*?(\\d+).*", "$1");
        if (orderId.equals(question)) {
            return plan;
        }

        if (!operational) {
            return plan;
        }

        boolean needsTool = plan.needsTool() || operational;
        String toolName = plan.toolName();
        String toolArgument = plan.toolArgument();

        if (needsTool && (toolName == null || toolName.isBlank())) {
            toolName = "getOrderStatus";
        }
        if (needsTool && (toolArgument == null || toolArgument.isBlank())) {
            toolArgument = orderId;
        }

        return new AgentPlan(plan.needsRetrieval(), needsTool, toolName, toolArgument);
    }

    private String callAnswerModel(String finalPrompt) {
        long startNanos = System.nanoTime();
        String content =
                answerChatClient.prompt()
                        .system(system -> system.text(ANSWER_SYSTEM_PROMPT))
                        .user(user -> user.text(finalPrompt))
                        .call()
                        .content();
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        answerTimer.record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        log.info("LLM answer call completed durationMs={}", durationMs);
        return content;
    }

    private String sanitizeAnswer(String answer, boolean hasContext, boolean hasToolResult) {
        String[] lines = answer.split("\\R");
        StringBuilder out = new StringBuilder();
        boolean includePolicy = false;
        boolean includeSystem = false;
        boolean keep = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.toLowerCase().contains("no tool_result")
                    || trimmed.toLowerCase().contains("no tool result")) {
                continue;
            }
            if (trimmed.toLowerCase().startsWith("however, i don't have")
                    || trimmed.toLowerCase().startsWith("however, i do not have")
                    || trimmed.toLowerCase().startsWith("i don't have information")
                    || trimmed.toLowerCase().startsWith("i do not have information")
                    || trimmed.toLowerCase().startsWith("however, since we don't have")
                    || trimmed.toLowerCase().startsWith("however, since we do not have")
                    || trimmed.toLowerCase().startsWith("since we don't have information")
                    || trimmed.toLowerCase().startsWith("since we do not have information")) {
                continue;
            }
            if (trimmed.equalsIgnoreCase("Policy:")) {
                includePolicy = hasContext;
                includeSystem = false;
                keep = includePolicy;
            } else if (trimmed.equalsIgnoreCase("System:")) {
                includeSystem = hasToolResult;
                includePolicy = false;
                keep = includeSystem;
            } else if (trimmed.startsWith("Policy:")) {
                keep = hasContext;
            } else if (trimmed.startsWith("System:")) {
                keep = hasToolResult;
            }

            if (keep) {
                out.append(line).append("\n");
            }
        }

        String result = out.toString().trim();
        if (result.isBlank()) {
            return "I don't have enough information to answer.";
        }
        return result;
    }
}
