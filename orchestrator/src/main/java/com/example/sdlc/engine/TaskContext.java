package com.example.sdlc.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.example.sdlc.Json;
import com.example.sdlc.artifact.Artifact;
import com.example.sdlc.artifact.ArtifactRef;
import com.example.sdlc.plan.TaskSpec;
import com.example.sdlc.workspace.Workspace;

/**
 * What a handler sees for one attempt: an immutable view of the run's artifacts plus the live workspace.
 * Every read is recorded, so the engine - not the handler - establishes which artifact versions (and which
 * change sets, via workspace file reads) a result was derived from. Only artifacts from graph ancestors,
 * humans and the engine are visible. Workspace reads are live; they are safe because only the coordinator
 * writes, and plan validation orders every workspace-wide check after all changes.
 */
public final class TaskContext {

    private final String requirement;
    private final TaskSpec task;
    private final int attempt;
    private final int invocation;
    private final Map<String, Artifact> visible;
    private final Map<String, ArtifactRef> fileWriters;
    private final Workspace workspace;
    private final List<Feedback> feedback;
    private final Set<ArtifactRef> consumed = new LinkedHashSet<>();

    TaskContext(String requirement, TaskSpec task, int attempt, int invocation, Map<String, Artifact> visible,
                Map<String, ArtifactRef> fileWriters, Workspace workspace, List<Feedback> feedback) {
        this.requirement = requirement;
        this.task = task;
        this.attempt = attempt;
        this.invocation = invocation;
        this.visible = Map.copyOf(visible);
        this.fileWriters = Map.copyOf(fileWriters);
        this.workspace = workspace;
        this.feedback = List.copyOf(feedback);
    }

    /** A context outside a run, for testing handlers in isolation. */
    public static TaskContext forTest(TaskSpec task, Map<String, Artifact> visible, Workspace workspace, List<Feedback> feedback) {
        return new TaskContext("", task, 1, 1, visible, Map.of(), workspace, feedback);
    }

    public String requirement() {
        return requirement;
    }

    public TaskSpec task() {
        return task;
    }

    public int attempt() {
        return attempt;
    }

    /** Total number of executions of this task in the run (1-based), stable across process restarts. */
    public int invocation() {
        return invocation;
    }

    public List<Feedback> feedback() {
        return feedback;
    }

    public Workspace workspace() {
        return workspace;
    }

    public <T> T read(String key, Class<T> type) {
        return find(key, type).orElseThrow(() ->
                new TaskFailure(FailureKind.FATAL, "required input '" + key + "' is not available to task " + task.id()));
    }

    public <T> Optional<T> find(String key, Class<T> type) {
        Artifact artifact = visible.get(key);
        if (artifact == null) {
            return Optional.empty();
        }
        consumed.add(artifact.ref());
        return Optional.of(Json.convert(artifact.content(), type));
    }

    /** All visible artifacts whose key starts with {@code prefix}, recorded as inputs. */
    public List<Artifact> readAll(String prefix) {
        List<Artifact> matches = new ArrayList<>();
        visible.values().stream()
                .filter(a -> a.key().startsWith(prefix))
                .sorted(Comparator.comparing(Artifact::key))
                .forEach(a -> {
                    consumed.add(a.ref());
                    matches.add(a);
                });
        return matches;
    }

    /** Reads a workspace file; if a change set of this run wrote it, that change set becomes an input. */
    public Optional<String> readFile(String path) {
        ArtifactRef writer = fileWriters.get(path);
        if (writer != null) {
            consumed.add(writer);
        }
        return workspace.read(path);
    }

    List<ArtifactRef> consumed() {
        return List.copyOf(consumed);
    }
}
