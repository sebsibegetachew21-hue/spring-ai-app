package com.yourapp.ai.memory;

import java.util.HashMap;
import java.util.Map;

public class ConversationMemory {

    private final Map<String, Object> memory = new HashMap<>();

    public ConversationMemory() {}

    public ConversationMemory(Map<String, Object> initial) {
        if (initial != null) {
            memory.putAll(initial);
        }
    }

    public void put(String key, Object value) {
        memory.put(key, value);
    }

    public Object get(String key) {
        return memory.get(key);
    }

    public boolean contains(String key) {
        return memory.containsKey(key);
    }

    public Map<String, Object> snapshot() {
        return Map.copyOf(memory);
    }
}
