package com.example.sdlc.engine;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Append-only event log for one run. The coordinator thread is the only writer, which gives a total order
 * that matches the order in which state transitions were actually applied.
 */
public final class EventLog {

    private final String runId;
    private final Clock clock;
    private final List<ExecutionEvent> events = new ArrayList<>();
    private final List<Consumer<ExecutionEvent>> listeners = new CopyOnWriteArrayList<>();
    private long nextSeq;

    public EventLog(String runId, Clock clock, List<ExecutionEvent> previous) {
        this.runId = runId;
        this.clock = clock;
        this.events.addAll(previous);
        this.nextSeq = previous.isEmpty() ? 1 : previous.getLast().seq() + 1;
    }

    public void subscribe(Consumer<ExecutionEvent> listener) {
        listeners.add(listener);
    }

    public ExecutionEvent append(EventType type, String taskId, Integer attempt, String message, Object... keyValues) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                data.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
            }
        }
        ExecutionEvent event = new ExecutionEvent(nextSeq++, clock.instant(), runId, type, taskId, attempt, message, data);
        events.add(event);
        listeners.forEach(listener -> listener.accept(event));
        return event;
    }

    public List<ExecutionEvent> events() {
        return List.copyOf(events);
    }
}
