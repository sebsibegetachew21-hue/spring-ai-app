package com.yourapp.ai.model;

public record AgentPlan(
        boolean needsRetrieval,
        String toolName,
        String toolArgument
) {}
