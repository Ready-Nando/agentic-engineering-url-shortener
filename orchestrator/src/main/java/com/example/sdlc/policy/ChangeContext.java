package com.example.sdlc.policy;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import com.example.sdlc.workspace.FileDelta;

/**
 * Facts about a proposed change that policy decides on. Everything here is computed by the engine from the
 * concrete change and the codebase; the proposer's {@code declaredRisk} is carried only so it can be audited.
 *
 * @param anticipatedPaths files the impact analysis expected to change; {@code null} when no analysis exists
 */
public record ChangeContext(
        String taskId,
        List<String> scope,
        List<FileDelta> deltas,
        Set<String> anticipatedPaths,
        String declaredRisk,
        Predicate<String> inBaseline) {

    public ChangeContext {
        scope = List.copyOf(scope);
        deltas = List.copyOf(deltas);
    }
}
