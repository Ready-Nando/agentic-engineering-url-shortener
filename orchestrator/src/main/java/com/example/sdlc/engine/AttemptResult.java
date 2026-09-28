package com.example.sdlc.engine;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.example.sdlc.artifact.ArtifactRef;
import com.example.sdlc.workspace.ChangeSet;

/**
 * A handler result plus the engine-recorded facts of the attempt (inputs actually read, applied change set,
 * worker thread). Outputs are kept sorted so publication and event order are reproducible.
 */
public record AttemptResult(
        String taskId,
        int generation,
        int attempt,
        Map<String, OutputArtifact> outputs,
        ChangeSet changes,
        List<ArtifactRef> inputs,
        String summary,
        String appliedChangeSetId,
        Instant startedAt,
        String thread) {

    public AttemptResult {
        outputs = outputs == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(outputs));
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
    }

    /** Outcome of an attempt whose handler failed before producing anything. */
    static AttemptResult failed(String taskId, int generation, int attempt, List<ArtifactRef> inputs, Instant startedAt, String thread) {
        return new AttemptResult(taskId, generation, attempt, Map.of(), null, inputs, "", null, startedAt, thread);
    }

    public AttemptResult withApplied(String changeSetId) {
        return new AttemptResult(taskId, generation, attempt, outputs, changes, inputs, summary, changeSetId, startedAt, thread);
    }

    public AttemptResult onThread(String newThread) {
        return new AttemptResult(taskId, generation, attempt, outputs, changes, inputs, summary, appliedChangeSetId, startedAt, newThread);
    }
}
