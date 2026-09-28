package com.example.sdlc.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * An immutable dependency graph. Construction (including deserialisation) rejects duplicate ids, unknown
 * dependencies and cycles, so every other component may rely on the graph being a DAG.
 */
public record WorkflowPlan(int version, List<TaskSpec> tasks, String rationale, String trigger) {

    public WorkflowPlan {
        tasks = List.copyOf(tasks);
        List<String> problems = structuralProblems(tasks);
        if (!problems.isEmpty()) {
            throw new PlanRejectedException(problems);
        }
    }

    public static WorkflowPlan of(int version, List<TaskSpec> tasks, String rationale, String trigger) {
        return new WorkflowPlan(version, tasks, rationale, trigger);
    }

    /** Duplicate ids, dangling dependencies and cycles; empty when the graph is a well-formed DAG. */
    public static List<String> structuralProblems(List<TaskSpec> tasks) {
        List<String> problems = new ArrayList<>();
        Map<String, TaskSpec> byId = new HashMap<>();
        for (TaskSpec task : tasks) {
            if (byId.put(task.id(), task) != null) {
                problems.add("duplicate task id '" + task.id() + "'");
            }
        }
        for (TaskSpec task : tasks) {
            for (String dependency : task.dependsOn()) {
                if (!byId.containsKey(dependency)) {
                    problems.add("task '" + task.id() + "' depends on unknown task '" + dependency + "'");
                }
            }
        }
        if (problems.isEmpty() && topologicalOrder(tasks).size() != tasks.size()) {
            problems.add("dependency cycle detected");
        }
        return problems;
    }

    public Optional<TaskSpec> find(String id) {
        return tasks.stream().filter(t -> t.id().equals(id)).findFirst();
    }

    public TaskSpec task(String id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException("unknown task " + id));
    }

    public boolean contains(String id) {
        return find(id).isPresent();
    }

    /** Kahn's algorithm; ties are broken by declaration order so scheduling and reports are deterministic. */
    public List<TaskSpec> topologicalOrder() {
        return topologicalOrder(tasks);
    }

    private static List<TaskSpec> topologicalOrder(List<TaskSpec> tasks) {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        Map<String, TaskSpec> byId = new LinkedHashMap<>();
        for (TaskSpec task : tasks) {
            byId.put(task.id(), task);
            indegree.put(task.id(), task.dependsOn().size());
            for (String dependency : task.dependsOn()) {
                dependents.computeIfAbsent(dependency, k -> new ArrayList<>()).add(task.id());
            }
        }
        List<TaskSpec> ordered = new ArrayList<>();
        Deque<String> ready = new ArrayDeque<>();
        indegree.forEach((id, degree) -> {
            if (degree == 0) {
                ready.add(id);
            }
        });
        while (!ready.isEmpty()) {
            String id = ready.poll();
            ordered.add(byId.get(id));
            for (String dependent : dependents.getOrDefault(id, List.of())) {
                if (indegree.merge(dependent, -1, Integer::sum) == 0) {
                    ready.add(dependent);
                }
            }
        }
        return ordered;
    }

    public Set<String> ancestors(String id) {
        Set<String> result = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>(task(id).dependsOn());
        while (!pending.isEmpty()) {
            String next = pending.pop();
            if (result.add(next)) {
                pending.addAll(task(next).dependsOn());
            }
        }
        return result;
    }
}
