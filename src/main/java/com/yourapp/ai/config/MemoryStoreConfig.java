package com.yourapp.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourapp.ai.memory.InMemoryMemoryStore;
import com.yourapp.ai.memory.MemoryStore;
import com.yourapp.ai.memory.RedisMemoryStore;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class MemoryStoreConfig {

  @Bean
  @ConditionalOnProperty(name = "app.memory.store", havingValue = "redis")
  public MemoryStore redisMemoryStore(
      StringRedisTemplate redisTemplate,
      ObjectMapper mapper,
      @Value("${app.memory.redis-ttl:PT30M}") Duration ttl,
      @Value("${app.memory.redis-key-prefix:memory:}") String keyPrefix) {
    return new RedisMemoryStore(redisTemplate, mapper, ttl, keyPrefix);
  }

  @Bean
  @ConditionalOnMissingBean(MemoryStore.class)
  public MemoryStore inMemoryMemoryStore() {
    return new InMemoryMemoryStore();
  }
}
