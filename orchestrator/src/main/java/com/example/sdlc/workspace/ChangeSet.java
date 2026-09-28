package com.example.sdlc.workspace;

import java.util.List;
import java.util.Map;

/**
 * A reasoning component's proposed modification of the workspace.
 *
 * @param declaredRisk the proposer's own risk claim; recorded for audit but never used to relax policy
 * @param baseHashes   content hashes of the files the proposal was based on (filled in by the engine-side
 *                     handler, not by the proposer); used for optimistic concurrency control on apply
 */
public record ChangeSet(String summary, String declaredRisk, List<FileChange> changes, Map<String, String> baseHashes) {

    public ChangeSet {
        summary = summary == null ? "" : summary;
        changes = changes == null ? List.of() : List.copyOf(changes);
        baseHashes = baseHashes == null ? Map.of() : Map.copyOf(baseHashes);
    }

    public ChangeSet withBaseHashes(Map<String, String> hashes) {
        return new ChangeSet(summary, declaredRisk, changes, hashes);
    }
}
