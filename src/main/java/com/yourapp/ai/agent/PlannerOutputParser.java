package com.yourapp.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;

public class PlannerOutputParser {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Set<String> ALLOWED_FIELDS =
            Set.of("needsRetrieval", "needsTool", "toolName", "toolArgument");

    public static AgentPlan parse(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            if (!node.isObject()) {
                throw new IllegalArgumentException("Planner JSON must be an object");
            }

            node.fieldNames().forEachRemaining(name -> {
                if (!ALLOWED_FIELDS.contains(name)) {
                    throw new IllegalArgumentException("Invalid planner JSON: unknown field " + name);
                }
            });

            JsonNode needsRetrievalNode = node.get("needsRetrieval");
            JsonNode needsToolNode = node.get("needsTool");
            JsonNode toolNameNode = node.get("toolName");
            JsonNode toolArgumentNode = node.get("toolArgument");

            if (needsRetrievalNode == null || !needsRetrievalNode.isBoolean()) {
                throw new IllegalArgumentException("Invalid planner JSON: needsRetrieval");
            }
            if (needsToolNode == null || !needsToolNode.isBoolean()) {
                throw new IllegalArgumentException("Invalid planner JSON: needsTool");
            }
            if (toolNameNode == null || !(toolNameNode.isTextual() || toolNameNode.isNull())) {
                throw new IllegalArgumentException("Invalid planner JSON: toolName");
            }
            if (toolArgumentNode == null || !(toolArgumentNode.isTextual() || toolArgumentNode.isNull())) {
                throw new IllegalArgumentException("Invalid planner JSON: toolArgument");
            }

            boolean needsRetrieval = needsRetrievalNode.asBoolean();
            boolean needsTool = needsToolNode.asBoolean();
            String toolName = toolNameNode.isNull() ? null : toolNameNode.asText();
            String toolArgument = toolArgumentNode.isNull() ? null : toolArgumentNode.asText();

            if (needsTool) {
                if (toolName == null || toolName.isBlank()) {
                    throw new IllegalArgumentException("Invalid planner JSON: toolName required");
                }
                if (!"getOrderStatus".equals(toolName)) {
                    throw new IllegalArgumentException("Invalid planner JSON: unsupported toolName");
                }
                if (toolArgument == null || toolArgument.isBlank()) {
                    throw new IllegalArgumentException("Invalid planner JSON: toolArgument required");
                }
            } else {
                if (toolName != null || toolArgument != null) {
                    throw new IllegalArgumentException("Invalid planner JSON: tool fields must be null");
                }
            }

            return new AgentPlan(needsRetrieval, needsTool, toolName, toolArgument);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid planner JSON", e);
        }
    }
}
