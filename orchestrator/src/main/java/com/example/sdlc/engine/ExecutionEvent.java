package com.example.sdlc.engine;

import java.time.Instant;
import java.util.Map;

/**
 * One line of the append-only audit log. {@code data} carries machine-readable facts (metrics are derived
 * from it); {@code message} is the human-readable explanation of why the transition happened.
 */
public record ExecutionEvent(
        long seq,
        Instant at,
        String runId,
        EventType type,
        String taskId,
        Integer attempt,
        String message,
        Map<String, Object> data) {

    public ExecutionEvent {
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public String text(String key) {
        Object value = data.get(key);
        return value == null ? null : value.toString();
    }
}
