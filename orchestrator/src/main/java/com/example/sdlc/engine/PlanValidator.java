package com.example.sdlc.engine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.example.sdlc.plan.PlanRejectedException;
import com.example.sdlc.plan.TaskSpec;
import com.example.sdlc.plan.WorkflowPlan;

/**
 * Turns a planner's proposal into the next plan version, enforcing structure and governance the planner
 * cannot opt out of: known capabilities only; change scopes limited to what each capability may touch; every
 * change verified and security reviewed; workspace-wide checks ordered after all changes; a single release
 * assessment that depends on everything; bounded retries; mandatory gates; default fallbacks only.
 */
public final class PlanValidator {

    private final Map<String, Capability> capabilities;
    private final Set<String> knownGates;
    private final int maxAttemptsCap;

    public PlanValidator(Map<String, Capability> capabilities, Set<String> knownGates, int maxAttemptsCap) {
        this.capabilities = capabilities;
        this.knownGates = knownGates;
        this.maxAttemptsCap = maxAttemptsCap;
    }

    public record Validated(WorkflowPlan plan, List<String> notes) {
    }

    /**
     * @param plannerTaskId the task that produced the proposal; every proposed task is made to depend on it
     * @param baselineTaskId optional bootstrap task that must precede any workspace change (may be null)
     */
    public Validated validate(WorkflowPlan current, List<TaskSpec> proposed, String rationale,
                              String plannerTaskId, String baselineTaskId) {
        List<String> violations = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Set<String> fixedIds = current.tasks().stream()
                .filter(t -> !isPlanned(current, t, plannerTaskId))
                .map(TaskSpec::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> proposedIds = proposed.stream().map(TaskSpec::id).collect(Collectors.toSet());

        List<TaskSpec> normalised = new ArrayList<>();
        for (TaskSpec task : proposed) {
            Capability capability = capabilities.get(task.capability());
            if (fixedIds.contains(task.id())) {
                violations.add("task id '" + task.id() + "' collides with a bootstrap task");
                continue;
            }
            if (capability == null || !capability.plannable()) {
                violations.add("task '" + task.id() + "' uses unknown or non-plannable capability '" + task.capability() + "'");
                continue;
            }
            normalised.add(normalise(task, capability, proposedIds, plannerTaskId, baselineTaskId, fixedIds, violations, notes));
        }

        List<TaskSpec> all = new ArrayList<>(current.tasks().stream().filter(t -> fixedIds.contains(t.id())).toList());
        all.addAll(normalised);
        violations.addAll(WorkflowPlan.structuralProblems(all));
        if (violations.isEmpty()) {
            checkGovernanceStructure(new WorkflowPlan(0, all, "", ""), normalised, violations);
        }
        if (!violations.isEmpty()) {
            throw new PlanRejectedException(violations);
        }
        return new Validated(WorkflowPlan.of(current.version() + 1, all, rationale, "proposed by " + plannerTaskId), notes);
    }

    private static boolean isPlanned(WorkflowPlan plan, TaskSpec task, String plannerTaskId) {
        return plan.contains(plannerTaskId) && plan.ancestors(task.id()).contains(plannerTaskId);
    }

    private TaskSpec normalise(TaskSpec task, Capability capability, Set<String> proposedIds, String plannerTaskId,
                               String baselineTaskId, Set<String> fixedIds, List<String> violations, List<String> notes) {
        Set<String> dependsOn = new LinkedHashSet<>(task.dependsOn());
        if (dependsOn.stream().noneMatch(proposedIds::contains)) {
            dependsOn.add(plannerTaskId);
        }
        if (capability.proposesChanges() && baselineTaskId != null && fixedIds.contains(baselineTaskId)) {
            dependsOn.add(baselineTaskId);
        }
        if (capability.proposesChanges()) {
            if (task.scope().isEmpty()) {
                violations.add("change task '" + task.id() + "' declares no scope");
            }
            for (String pattern : task.scope()) {
                if (!withinRoots(pattern, capability.scopeRoots())) {
                    violations.add("task '" + task.id() + "' scope '" + pattern + "' is outside what '" + capability.name()
                            + "' may change " + capability.scopeRoots());
                }
            }
        } else if (!task.scope().isEmpty()) {
            violations.add("task '" + task.id() + "' declares a scope but capability '" + capability.name() + "' cannot change files");
        }

        int attempts = task.maxAttempts() <= 0 ? capability.defaultMaxAttempts() : task.maxAttempts();
        if (attempts > maxAttemptsCap) {
            notes.add("task '" + task.id() + "' requested " + attempts + " attempts; capped at " + maxAttemptsCap);
            attempts = maxAttemptsCap;
        }

        List<String> entry = union(capability.entryGates(), task.entryGates());
        List<String> exit = union(capability.exitGates(), task.exitGates());
        for (String gate : union(entry, exit)) {
            if (!knownGates.contains(gate)) {
                violations.add("task '" + task.id() + "' references unknown gate '" + gate + "'");
            }
        }
        if (task.fallback() != null && !task.fallback().equals(capability.defaultFallback())) {
            violations.add("task '" + task.id() + "' may not choose fallback '" + task.fallback() + "' (only '"
                    + capability.defaultFallback() + "' is allowed for " + capability.name() + ")");
        }
        return task.withStage(task.stage() == null ? capability.stage() : task.stage())
                .withDependsOn(List.copyOf(dependsOn)).withGates(entry, exit).withMaxAttempts(attempts)
                .withFallback(capability.defaultFallback());
    }

    /** A scope must name a path under one of the capability's roots; wildcards may only appear below the root. */
    private static boolean withinRoots(String pattern, List<String> roots) {
        if (pattern.startsWith("/") || pattern.contains("..") || pattern.contains("\\")) {
            return false;
        }
        return roots.stream().anyMatch(root -> root.endsWith("/")
                ? pattern.startsWith(root) && pattern.length() > root.length()
                : pattern.equals(root));
    }

    private void checkGovernanceStructure(WorkflowPlan graph, List<TaskSpec> planned, List<String> violations) {
        List<TaskSpec> changeTasks = planned.stream().filter(t -> capability(t).proposesChanges()).toList();
        List<TaskSpec> releases = withRole(planned, Capability.Role.RELEASE);
        if (releases.size() != 1) {
            violations.add("plan must contain exactly one release assessment task (found " + releases.size() + ")");
        } else {
            TaskSpec release = releases.getFirst();
            Set<String> ancestors = graph.ancestors(release.id());
            for (TaskSpec task : planned) {
                if (!task.id().equals(release.id()) && !ancestors.contains(task.id())) {
                    violations.add("release task must depend (transitively) on '" + task.id() + "'");
                }
            }
        }
        if (changeTasks.isEmpty()) {
            return;
        }
        for (Capability.Role required : List.of(Capability.Role.VERIFICATION, Capability.Role.SECURITY_REVIEW)) {
            if (withRole(planned, required).isEmpty()) {
                violations.add("plan changes code but has no " + required + " task");
            }
        }
        // Workspace-wide checks read the whole tree, so they must see every change and never overlap one.
        for (TaskSpec check : planned.stream().filter(t -> capability(t).role().inspectsWholeWorkspace()).toList()) {
            Set<String> ancestors = graph.ancestors(check.id());
            for (TaskSpec change : changeTasks) {
                if (!ancestors.contains(change.id())) {
                    violations.add("'" + check.id() + "' (" + capability(check).role() + ") must run after change task '" + change.id() + "'");
                }
            }
        }
        Set<String> changeIds = changeTasks.stream().map(TaskSpec::id).collect(Collectors.toSet());
        for (TaskSpec verify : withRole(planned, Capability.Role.VERIFICATION)) {
            if (verify.verifies().isEmpty()) {
                violations.add("'" + verify.id() + "' must list the change tasks it verifies");
            }
            for (String target : verify.verifies()) {
                if (!changeIds.contains(target)) {
                    violations.add("'" + verify.id() + "' verifies '" + target + "', which is not a change task of this plan");
                }
            }
        }
    }

    private List<TaskSpec> withRole(List<TaskSpec> tasks, Capability.Role role) {
        return tasks.stream().filter(t -> capability(t).role() == role).toList();
    }

    private Capability capability(TaskSpec task) {
        return capabilities.get(task.capability());
    }

    private static List<String> union(List<String> first, List<String> second) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(first);
        merged.addAll(second);
        return List.copyOf(merged);
    }
}
