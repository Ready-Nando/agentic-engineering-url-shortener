package com.example.sdlc.engine;

import java.util.List;

import com.example.sdlc.plan.Stage;

/**
 * A named unit of work the planner may use, bound to deterministic behaviour. Mandatory gates are attached
 * by the engine whatever the plan says, so a plan (or a reasoning component writing it) cannot drop them.
 *
 * @param role             what plan governance relies on this capability for (see {@link Role})
 * @param plannable        whether a plan proposal may use it (bootstrap-only capabilities are not)
 * @param proposesChanges  whether results may carry a workspace change set (subject to change policy)
 * @param scopeRoots       for change capabilities: the only places a planned scope may point at
 *                         (a path, or a directory ending in '/')
 * @param producesPlan     whether its {@code plan/proposal} output revises the workflow graph
 * @param defaultFallback  capability used after infrastructure-type exhaustion, or {@code null}; plans cannot
 *                         choose a different one
 */
public record Capability(
        String name,
        Stage stage,
        Role role,
        boolean plannable,
        boolean proposesChanges,
        List<String> scopeRoots,
        boolean producesPlan,
        int defaultMaxAttempts,
        List<String> entryGates,
        List<String> exitGates,
        String defaultFallback,
        TaskHandler handler) {

    /**
     * Roles the plan validator enforces: every change must be verified and security reviewed, workspace-wide
     * checks must run after all changes, and exactly one release assessment closes the plan.
     */
    public enum Role {
        WORK,
        VERIFICATION,
        SECURITY_REVIEW,
        COMPATIBILITY_REVIEW,
        RELEASE;

        /** Checks that read the whole workspace and therefore must not overlap any change. */
        public boolean inspectsWholeWorkspace() {
            return this != WORK;
        }
    }

    public Capability {
        scopeRoots = List.copyOf(scopeRoots);
        entryGates = List.copyOf(entryGates);
        exitGates = List.copyOf(exitGates);
    }
}
