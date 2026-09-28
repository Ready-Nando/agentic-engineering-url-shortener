package com.example.sdlc.reasoning;

import java.util.List;

import com.example.sdlc.workspace.FileChange;

/**
 * Proposed file operations for one task. {@code declaredRisk} is the proposer's own opinion; policy ignores it
 * when deciding.
 */
public record ChangeProposal(String summary, String rationale, String declaredRisk, List<FileChange> changes) {

    public ChangeProposal {
        changes = changes == null ? List.of() : List.copyOf(changes);
    }
}
