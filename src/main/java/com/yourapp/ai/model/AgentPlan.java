package com.yourapp.ai.model;

import java.util.List;

public record AgentPlan(
        boolean needsRetrieval,
        String toolName,
        String toolArgument
) {}
