package com.yourapp.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;

public class PlannerOutputParser {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static AgentPlan parse(String json) {
        try {
            return mapper.readValue(json, AgentPlan.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid planner JSON", e);
        }
    }
}
