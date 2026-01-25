package com.yourapp.ai.retreival;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RetrieverService {

    private final VectorStore vectorStore;
    private final Counter retrievalCounter;
    private static final Pattern POLICY_REF =
            Pattern.compile("Policy:\\s*([A-Za-z][A-Za-z\\s]{1,60})");

    public RetrieverService(VectorStore vectorStore, MeterRegistry meterRegistry) {
        this.vectorStore = vectorStore;
        this.retrievalCounter = Counter.builder("agent.retrieval.count")
                .description("Number of retrieval calls")
                .register(meterRegistry);
    }

    /**
     * Retrieve relevant documents for RAG
     */
    public RetrievalResult retrieve(String question) {

        List<Document> docs =
                vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query(question)
                                .similarityThreshold(0.60)
                                .build()
                );
        retrievalCounter.increment();

        Map<String, Document> deduped = new LinkedHashMap<>();
        for (Document doc : docs) {
            deduped.putIfAbsent(doc.getText(), doc);
        }

        Set<String> referencedPolicies = extractPolicyReferences(docs);
        int maxReferences = 3;
        int count = 0;
        for (String policy : referencedPolicies) {
            if (count >= maxReferences) {
                break;
            }
            List<Document> related =
                    vectorStore.similaritySearch(
                            SearchRequest.builder()
                                    .query("Policy: " + policy)
                                    .similarityThreshold(0.60)
                                    .build()
                    );
            for (Document doc : related) {
                deduped.putIfAbsent(doc.getText(), doc);
            }
            count++;
        }

        String context =
                deduped.values().stream()
                        .map(Document::getText)
                        .collect(Collectors.joining("\n\n"));

        List<String> citations =
                deduped.values().stream()
                        .map(doc -> {
                            Object policyId = doc.getMetadata().get("policyId");
                            Object chunkIndex = doc.getMetadata().get("chunkIndex");
                            if (policyId != null && chunkIndex != null) {
                                return policyId + "#chunk" + chunkIndex;
                            }
                            Object path = doc.getMetadata().get("path");
                            if (path != null) {
                                return path.toString();
                            }
                            return doc.getMetadata().getOrDefault("source", "unknown").toString();
                        })
                        .collect(Collectors.toList());

        return new RetrievalResult(context, citations);
    }

    private Set<String> extractPolicyReferences(List<Document> docs) {
        Set<String> policies = new HashSet<>();
        for (Document doc : docs) {
            Matcher matcher = POLICY_REF.matcher(doc.getText());
            while (matcher.find()) {
                policies.add(matcher.group(1).trim());
            }
        }
        return policies;
    }
}
