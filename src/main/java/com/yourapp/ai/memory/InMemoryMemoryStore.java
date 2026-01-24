package com.yourapp.ai.memory;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

@Component
public class InMemoryMemoryStore implements MemoryStore {

  private final ConcurrentMap<String, ConversationMemory> store = new ConcurrentHashMap<>();

  @Override
  public Optional<ConversationMemory> get(String conversationId) {
    return Optional.ofNullable(store.get(conversationId));
  }

  @Override
  public void put(String conversationId, ConversationMemory memory) {
    store.put(conversationId, memory);
  }

  @Override
  public void remove(String conversationId) {
    store.remove(conversationId);
  }
}
