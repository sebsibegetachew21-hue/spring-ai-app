package com.yourapp.ai.model;

import java.util.List;

public record AgentPlan(
        boolean needsRetrieval,
        boolean needsTool,
        String toolName,
        String toolArgument
) {}
