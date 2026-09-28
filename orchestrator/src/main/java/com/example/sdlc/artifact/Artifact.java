package com.example.sdlc.artifact;

import java.time.Instant;
import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * An immutable, versioned engineering output. {@code producer} is a task id, {@code human:<name>} for human
 * input, or {@code engine}. {@code inputs} are the exact artifact versions the producing attempt read, which
 * is what makes lineage and selective invalidation possible.
 */
public record Artifact(
        String key,
        int version,
        String kind,
        JsonNode content,
        String contentHash,
        String producer,
        int producerAttempt,
        List<ArtifactRef> inputs,
        Instant createdAt,
        Status status,
        String statusReason) {

    /**
     * REJECTED marks output of an attempt that did not pass its exit gates: never visible to other tasks,
     * kept only as audit evidence (e.g. the readiness checklist behind a NOT READY verdict).
     */
    public enum Status { CURRENT, SUPERSEDED, RETRACTED, REJECTED }

    public Artifact {
        inputs = List.copyOf(inputs);
    }

    public ArtifactRef ref() {
        return new ArtifactRef(key, version);
    }

    public boolean isCurrent() {
        return status == Status.CURRENT;
    }

    Artifact rederived(int attempt, List<ArtifactRef> newInputs) {
        return new Artifact(key, version, kind, content, contentHash, producer, attempt, newInputs, createdAt, status, statusReason);
    }

    Artifact withStatus(Status newStatus, String reason) {
        return new Artifact(key, version, kind, content, contentHash, producer, producerAttempt, inputs, createdAt, newStatus, reason);
    }
}
