package com.yourapp.ai.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

public class RedisMemoryStore implements MemoryStore {

  private static final TypeReference<Map<String, Object>> MAP_TYPE =
      new TypeReference<>() {};

  private final StringRedisTemplate redis;
  private final ObjectMapper mapper;
  private final Duration ttl;
  private final String keyPrefix;

  public RedisMemoryStore(
      StringRedisTemplate redis,
      ObjectMapper mapper,
      Duration ttl,
      String keyPrefix) {
    this.redis = redis;
    this.mapper = mapper;
    this.ttl = ttl;
    this.keyPrefix = keyPrefix;
  }

  @Override
  public Optional<ConversationMemory> get(String conversationId) {
    String json = redis.opsForValue().get(key(conversationId));
    if (json == null || json.isBlank()) {
      return Optional.empty();
    }

    try {
      Map<String, Object> map = mapper.readValue(json, MAP_TYPE);
      return Optional.of(new ConversationMemory(map));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to deserialize memory for " + conversationId, e);
    }
  }

  @Override
  public void put(String conversationId, ConversationMemory memory) {
    try {
      String json = mapper.writeValueAsString(memory.snapshot());
      redis.opsForValue().set(key(conversationId), json, ttl);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize memory for " + conversationId, e);
    }
  }

  @Override
  public void remove(String conversationId) {
    redis.delete(key(conversationId));
  }

  private String key(String conversationId) {
    return keyPrefix + conversationId;
  }
}
