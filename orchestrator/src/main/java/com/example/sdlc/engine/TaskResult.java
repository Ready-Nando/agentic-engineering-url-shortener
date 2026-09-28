package com.example.sdlc.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import com.example.sdlc.workspace.ChangeSet;

/**
 * What a handler returns. Handlers never write to the workspace themselves: a proposed {@link ChangeSet} is
 * evaluated by policy and, if allowed or approved, applied by the engine.
 */
public record TaskResult(Map<String, OutputArtifact> outputs, ChangeSet changes, String summary) {

    public TaskResult {
        outputs = outputs == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(outputs));
        summary = summary == null ? "" : summary;
    }

    public static TaskResult of(String summary, Map<String, OutputArtifact> outputs) {
        return new TaskResult(outputs, null, summary);
    }

    public static TaskResult of(String summary, String key, OutputArtifact output) {
        Map<String, OutputArtifact> outputs = new LinkedHashMap<>();
        outputs.put(key, output);
        return new TaskResult(outputs, null, summary);
    }

    public static TaskResult withChanges(String summary, ChangeSet changes, Map<String, OutputArtifact> outputs) {
        return new TaskResult(outputs, changes, summary);
    }
}
