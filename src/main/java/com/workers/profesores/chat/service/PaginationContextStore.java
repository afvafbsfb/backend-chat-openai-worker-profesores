package com.workers.profesores.chat.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory pagination context store keyed by user/session.
 * Keeps the last executed paginated context to support navigation when the client only sends the latest message.
 * TTL-based eviction; lightweight and node-local (not clustered). Suitable as a pragmatic default.
 */
@Service
public class PaginationContextStore {
    private static final long DEFAULT_TTL_MILLIS = 30 * 60 * 1000L; // 30 minutes
    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();
    // ObjectMapper not needed after refactor; keep code minimal

    private static class Entry {
        final ObjectNode context;
        final long storedAt;
        final long ttlMs;
        Entry(ObjectNode context, long ttlMs) {
            this.context = context;
            this.storedAt = System.currentTimeMillis();
            this.ttlMs = ttlMs <= 0 ? DEFAULT_TTL_MILLIS : ttlMs;
        }
        boolean expired() {
            return (System.currentTimeMillis() - storedAt) > ttlMs;
        }
    }

    public void save(String userKey, ObjectNode context) {
        if (userKey == null || userKey.isBlank() || context == null) return;
        // Normalize minimal shape: ensure at least endpoint and page/size if present
        ObjectNode copy = context.deepCopy();
        store.put(userKey, new Entry(copy, DEFAULT_TTL_MILLIS));
        // Opportunistic cleanup
        cleanupIfNeeded();
    }

    public ObjectNode get(String userKey) {
        if (userKey == null || userKey.isBlank()) return null;
        Entry e = store.get(userKey);
        if (e == null) return null;
        if (e.expired()) { store.remove(userKey); return null; }
        try {
            return e.context.deepCopy();
        } catch (Exception ex) {
            return null;
        }
    }

    private void cleanupIfNeeded() {
        // Simple time-based sweep to prevent unbounded growth; cheap heuristic
        if (store.size() > 1000) {
            for (Map.Entry<String, Entry> it : store.entrySet()) {
                Entry e = it.getValue();
                if (e == null || e.expired()) store.remove(it.getKey());
            }
        }
    }
}
