package com.example.sdlc.artifact;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.example.sdlc.Json;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;

import tools.jackson.databind.JsonNode;

/**
 * Version history of every artifact in a run. Only the engine's coordinator thread mutates it; workers read
 * immutable {@link Artifact} values handed to them in their task context.
 */
@JsonAutoDetect(fieldVisibility = Visibility.ANY, getterVisibility = Visibility.NONE, isGetterVisibility = Visibility.NONE)
public final class ArtifactStore {

    private Map<String, List<Artifact>> versions = new TreeMap<>();

    public enum Change { CREATED, CHANGED, UNCHANGED }

    public record Publication(Artifact artifact, Change change, Artifact previous) {
    }

    /**
     * Publishes new content under {@code key}. Content identical to the current version does not create a new
     * version ("early cutoff"): downstream work that consumed it stays valid. Its provenance is updated to the
     * latest derivation, so lineage never points at inputs that have since been retracted.
     */
    public Publication publish(String key, String kind, JsonNode content, String producer, int attempt,
                               List<ArtifactRef> inputs, Instant at) {
        String hash = Json.contentHash(content);
        Optional<Artifact> current = current(key);
        if (current.isPresent() && current.get().contentHash().equals(hash)) {
            Artifact rederived = current.get().rederived(attempt, inputs);
            replace(rederived);
            return new Publication(rederived, Change.UNCHANGED, current.get());
        }
        List<Artifact> history = versions.computeIfAbsent(key, k -> new ArrayList<>());
        current.ifPresent(previous -> replace(previous.withStatus(Artifact.Status.SUPERSEDED, "superseded by v" + (history.size() + 1))));
        Artifact created = new Artifact(key, history.size() + 1, kind, content, hash, producer, attempt, inputs, at,
                Artifact.Status.CURRENT, null);
        history.add(created);
        return new Publication(created, current.isPresent() ? Change.CHANGED : Change.CREATED, current.orElse(null));
    }

    /** Keeps the output of a rejected attempt as evidence without making it current. */
    public Artifact recordRejected(String key, String kind, JsonNode content, String producer, int attempt,
                                   List<ArtifactRef> inputs, Instant at, String reason) {
        List<Artifact> history = versions.computeIfAbsent(key, k -> new ArrayList<>());
        Artifact rejected = new Artifact(key, history.size() + 1, kind, content, Json.contentHash(content), producer, attempt,
                inputs, at, Artifact.Status.REJECTED, reason);
        history.add(rejected);
        return rejected;
    }

    /** Withdraws the current version without a replacement, e.g. when the change set it describes was rolled back. */
    public Optional<Artifact> retract(String key, String reason) {
        Optional<Artifact> current = current(key);
        current.ifPresent(artifact -> replace(artifact.withStatus(Artifact.Status.RETRACTED, reason)));
        return current;
    }

    public Optional<Artifact> current(String key) {
        List<Artifact> history = versions.getOrDefault(key, List.of());
        for (Artifact artifact : history.reversed()) {
            if (artifact.status() != Artifact.Status.REJECTED) {
                return artifact.isCurrent() ? Optional.of(artifact) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    public Optional<Artifact> get(ArtifactRef ref) {
        List<Artifact> history = versions.getOrDefault(ref.key(), List.of());
        return ref.version() >= 1 && ref.version() <= history.size() ? Optional.of(history.get(ref.version() - 1)) : Optional.empty();
    }

    public boolean isCurrent(ArtifactRef ref) {
        return get(ref).map(Artifact::isCurrent).orElse(false);
    }

    public List<Artifact> history(String key) {
        return List.copyOf(versions.getOrDefault(key, List.of()));
    }

    public List<Artifact> currentArtifacts() {
        return versions.keySet().stream().map(this::current).flatMap(Optional::stream).toList();
    }

    public List<Artifact> all() {
        return versions.values().stream().flatMap(Collection::stream).toList();
    }

    public List<Artifact> producedBy(String producer) {
        return currentArtifacts().stream().filter(a -> a.producer().equals(producer)).toList();
    }

    private void replace(Artifact artifact) {
        versions.get(artifact.key()).set(artifact.version() - 1, artifact);
    }
}
