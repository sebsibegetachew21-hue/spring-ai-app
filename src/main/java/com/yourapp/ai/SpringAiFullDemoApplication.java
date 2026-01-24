package com.yourapp.ai;

import com.yourapp.ai.rag.DocIngestor;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootApplication
public class SpringAiFullDemoApplication {

  private static final Logger log = LoggerFactory.getLogger(SpringAiFullDemoApplication.class);

  public static void main(String[] args) {
    SpringApplication.run(SpringAiFullDemoApplication.class, args);
  }

  @Bean
  CommandLineRunner ingestDocs(
      DocIngestor ingestor,
      JdbcTemplate jdbcTemplate,
      @Value("${app.rag.ingest-on-startup:false}") boolean ingestOnStartup,
      @Value("${app.rag.clear-on-startup:false}") boolean clearOnStartup,
      @Value("${app.rag.docs-pattern:classpath:/docs/*.txt}") String docsPattern,
      @Value("${app.rag.vector-table:vector_store}") String vectorTable) {
    return args -> {
      if (!ingestOnStartup) {
        return;
      }

      if (clearOnStartup) {
        String table = vectorTable.replaceAll("[^A-Za-z0-9_]", "");
        if (!table.isBlank()) {
          jdbcTemplate.execute("TRUNCATE TABLE " + table);
        }
      }

      try {
        int count = ingestor.ingestClasspathDocs(docsPattern);
        log.info("Ingested {} document chunks from {}", count, docsPattern);
      } catch (IOException e) {
        throw new IllegalStateException("Failed to ingest docs from " + docsPattern, e);
      }
    };
  }
}
