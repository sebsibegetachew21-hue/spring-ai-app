package com.yourapp.ai.memory;

import java.util.Optional;

public interface MemoryStore {
  Optional<ConversationMemory> get(String conversationId);
  void put(String conversationId, ConversationMemory memory);
  void remove(String conversationId);
}
