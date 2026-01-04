package com.yourapp.ai.retreival;

import java.util.List;

public record RetrievalResult(
        String context,
        List<String> citations
) {}
