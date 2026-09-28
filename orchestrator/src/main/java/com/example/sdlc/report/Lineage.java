package com.example.sdlc.report;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.example.sdlc.artifact.Artifact;
import com.example.sdlc.artifact.ArtifactRef;
import com.example.sdlc.artifact.ArtifactStore;

/**
 * Answers provenance questions from recorded inputs: what produced an artifact, what it was derived from
 * (including human decisions), what was derived from it, and whether it has been superseded.
 */
public final class Lineage {

    private final ArtifactStore artifacts;

    public Lineage(ArtifactStore artifacts) {
        this.artifacts = artifacts;
    }

    /** Resolves "key" (current or latest version) or "key@vN". */
    public Optional<Artifact> resolve(String reference) {
        int at = reference.lastIndexOf("@v");
        if (at > 0) {
            return artifacts.get(new ArtifactRef(reference.substring(0, at), Integer.parseInt(reference.substring(at + 2))));
        }
        List<Artifact> history = artifacts.history(reference);
        return history.isEmpty() ? Optional.empty() : Optional.of(history.getLast());
    }

    /** Everything the artifact was (transitively) derived from, depth-first, one line per node. */
    public List<String> upstream(Artifact artifact) {
        List<String> lines = new ArrayList<>();
        walkUp(artifact, 0, new LinkedHashSet<>(), lines);
        return lines;
    }

    /** Every artifact version (transitively) derived from this one. */
    public List<String> downstream(Artifact artifact) {
        List<String> lines = new ArrayList<>();
        walkDown(artifact.ref(), 0, new LinkedHashSet<>(), lines);
        return lines;
    }

    public List<Artifact> derivedFrom(ArtifactRef ref) {
        return artifacts.all().stream().filter(a -> a.inputs().contains(ref)).toList();
    }

    public static String describe(Artifact artifact) {
        return artifact.ref() + " [" + artifact.kind() + "] by " + artifact.producer()
                + (artifact.producerAttempt() > 0 ? " (attempt " + artifact.producerAttempt() + ")" : "")
                + " " + artifact.status() + (artifact.statusReason() == null ? "" : " - " + artifact.statusReason());
    }

    private void walkUp(Artifact artifact, int depth, Set<ArtifactRef> seen, List<String> lines) {
        lines.add("  ".repeat(depth) + (depth == 0 ? "" : "<- ") + describe(artifact));
        if (!seen.add(artifact.ref())) {
            return;
        }
        for (ArtifactRef input : artifact.inputs()) {
            artifacts.get(input).ifPresent(parent -> walkUp(parent, depth + 1, seen, lines));
        }
    }

    private void walkDown(ArtifactRef ref, int depth, Set<ArtifactRef> seen, List<String> lines) {
        for (Artifact child : derivedFrom(ref)) {
            lines.add("  ".repeat(depth + 1) + "-> " + describe(child));
            if (seen.add(child.ref())) {
                walkDown(child.ref(), depth + 1, seen, lines);
            }
        }
    }
}
