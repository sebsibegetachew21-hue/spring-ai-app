package com.yourapp.ai.rag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

@Component
public class DocIngestor {

    private final VectorStore vectorStore;

    public DocIngestor(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public int ingestClasspathDocs(String pattern) throws IOException {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources(pattern);

        List<Document> docs = new ArrayList<>();
        for (Resource r : resources) {
            String text = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String filename = r.getFilename() == null ? "unknown" : r.getFilename();
            String policyId = filename.replaceFirst("\\.[^.]+$", "");
            String title = extractTitle(text, policyId);

            docs.addAll(chunk(text, Map.of(
                    "source", "classpath",
                    "path", filename,
                    "policyId", policyId,
                    "title", title
            )));
        }

        vectorStore.add(docs);
        return docs.size();
    }

    private List<Document> chunk(String text, Map<String, Object> baseMeta) {
        int size = 800;
        int overlap = 100;

        List<Document> out = new ArrayList<>();
        int chunkIndex = 0;
        for (int start = 0; start < text.length(); start += (size - overlap)) {
            int end = Math.min(text.length(), start + size);
            String chunk = text.substring(start, end);

            Map<String, Object> meta = new HashMap<>(baseMeta);
            meta.put("chunkStart", start);
            meta.put("chunkEnd", end);
            meta.put("chunkIndex", chunkIndex++);

            out.add(new Document(chunk, meta));
            if (end == text.length()) break;
        }
        return out;
    }

    private String extractTitle(String text, String fallback) {
        String[] lines = text.split("\\R", 2);
        if (lines.length > 0) {
            String first = lines[0].trim();
            if (first.toLowerCase().startsWith("policy:")) {
                return first.substring("policy:".length()).trim();
            }
        }
        return fallback;
    }
}
