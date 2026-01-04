package com.yourapp.ai.agent;

import java.util.List;

public record AgentAnswer(
    String answer,
    List<String> citations,
    String confidence
) {}
