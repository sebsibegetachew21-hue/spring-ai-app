package com.yourapp.ai.rag;

import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

@Component
public class Retriever {

    private final VectorStore vectorStore;

    public Retriever(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public List<Document> topK(String query, int k) {
        return vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(k).build()
        );
    }
}
