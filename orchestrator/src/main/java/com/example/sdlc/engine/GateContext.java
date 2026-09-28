package com.example.sdlc.engine;

import java.util.Optional;

import com.example.sdlc.Json;
import com.example.sdlc.plan.TaskSpec;
import com.example.sdlc.workspace.Workspace;

/**
 * What a gate may inspect. {@code result} is null for entry gates. Gates run on the coordinator thread and
 * must treat the run as read-only.
 */
public record GateContext(WorkflowRun run, TaskSpec task, TaskState state, AttemptResult result, Workspace workspace) {

    public <T> Optional<T> output(String key, Class<T> type) {
        if (result == null || !result.outputs().containsKey(key)) {
            return Optional.empty();
        }
        return Optional.of(result.outputs().get(key).as(type));
    }

    public <T> Optional<T> current(String key, Class<T> type) {
        return run.artifacts().current(key).map(artifact -> Json.convert(artifact.content(), type));
    }
}
