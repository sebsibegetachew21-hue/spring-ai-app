package com.yourapp.ai.agent;

public record AgentPlan(
        boolean needsRetrieval,
        boolean needsTool,
        String toolName,
        String toolArgument
) {}
