package com.yourapp.ai.retreival;

import com.yourapp.ai.retreival.RetrievalResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class RetrieverService {

    private final VectorStore vectorStore;
    private final Counter retrievalCounter;

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
                vectorStore.similaritySearch(question);
        retrievalCounter.increment();

        String context =
                docs.stream()
                        .map(Document::getText) // Updated method name
                        .collect(Collectors.joining("\n\n"));

        List<String> citations =
                docs.stream()
                        .map(doc -> doc.getMetadata().getOrDefault("source", "unknown").toString())
                        .collect(Collectors.toList());

        return new RetrievalResult(context, citations);
    }
}
