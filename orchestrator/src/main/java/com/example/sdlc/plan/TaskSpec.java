package com.example.sdlc.plan;

import java.util.List;
import java.util.Objects;

/**
 * One node of the workflow graph. Specs are pure data: they can come from the bootstrap plan or from a
 * reasoning component's plan proposal, and are bound to deterministic behaviour (handlers and gates) by name.
 *
 * @param inputs    artifact keys (or prefixes ending in '/') the task reads besides the requirement; empty means
 *                  "all design artifacts". Narrow inputs keep re-planning selective.
 * @param scope     path globs (relative to the workspace) that a change-producing task may touch
 * @param verifies  upstream tasks whose outputs this task validates; a code-attributable failure here
 *                  sends those tasks back for rework instead of retrying this task
 * @param fallback  capability to use once the primary capability's attempts are exhausted by
 *                  infrastructure-type failures; {@code null} when there is no sensible fallback
 */
public record TaskSpec(
        String id,
        String title,
        Stage stage,
        String capability,
        List<String> dependsOn,
        String goal,
        List<String> inputs,
        List<String> scope,
        List<String> verifies,
        List<String> entryGates,
        List<String> exitGates,
        int maxAttempts,
        String fallback) {

    public TaskSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(capability, "capability");
        title = title == null ? id : title;
        goal = goal == null ? "" : goal;
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        scope = scope == null ? List.of() : List.copyOf(scope);
        verifies = verifies == null ? List.of() : List.copyOf(verifies);
        entryGates = entryGates == null ? List.of() : List.copyOf(entryGates);
        exitGates = exitGates == null ? List.of() : List.copyOf(exitGates);
    }

    public static TaskSpec of(String id, Stage stage, String capability, List<String> dependsOn) {
        return new TaskSpec(id, id, stage, capability, dependsOn, "", List.of(), List.of(), List.of(), List.of(), List.of(), 1, null);
    }

    public TaskSpec withStage(Stage newStage) {
        return new TaskSpec(id, title, newStage, capability, dependsOn, goal, inputs, scope, verifies, entryGates, exitGates, maxAttempts, fallback);
    }

    public TaskSpec withDependsOn(List<String> newDependsOn) {
        return new TaskSpec(id, title, stage, capability, newDependsOn, goal, inputs, scope, verifies, entryGates, exitGates, maxAttempts, fallback);
    }

    public TaskSpec withGates(List<String> newEntryGates, List<String> newExitGates) {
        return new TaskSpec(id, title, stage, capability, dependsOn, goal, inputs, scope, verifies, newEntryGates, newExitGates, maxAttempts, fallback);
    }

    public TaskSpec withMaxAttempts(int attempts) {
        return new TaskSpec(id, title, stage, capability, dependsOn, goal, inputs, scope, verifies, entryGates, exitGates, attempts, fallback);
    }

    public TaskSpec withFallback(String newFallback) {
        return new TaskSpec(id, title, stage, capability, dependsOn, goal, inputs, scope, verifies, entryGates, exitGates, maxAttempts, newFallback);
    }
}
