package com.example.sdlc.capability;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import com.example.sdlc.Json;
import com.example.sdlc.artifact.Artifact;
import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.artifact.ArtifactStore;
import com.example.sdlc.engine.TaskContext;
import com.example.sdlc.engine.TaskResult;
import com.example.sdlc.plan.Stage;
import com.example.sdlc.plan.TaskSpec;
import com.example.sdlc.workspace.Workspace;

import tools.jackson.databind.JsonNode;

/** Artifacts, workspaces and contexts for exercising capability handlers outside a run. */
final class CapabilityFixtures {

    private CapabilityFixtures() {
    }

    /** A workspace (plus baseline) created from {@code files}; changes are then made directly in {@link Workspace#root()}. */
    static Workspace workspace(Path temp, Map<String, String> files) throws IOException {
        Path module = temp.resolve("module");
        Files.createDirectories(module);
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path target = module.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue());
        }
        return Workspace.create(module, Map.of(), temp.resolve("run"));
    }

    static void write(Workspace workspace, String path, String content) throws IOException {
        Path target = workspace.root().resolve(path);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    static Artifact artifact(String key, Object content, String producer) {
        return new ArtifactStore().publish(key, "test", Json.tree(content), producer, 1, List.of(), Instant.now()).artifact();
    }

    /** A {@code changes/<task>} artifact as the engine publishes it on commit. */
    static Artifact changes(String taskId, int sequence, String... paths) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("changeSet", "cs-" + sequence);
        content.put("sequence", sequence);
        content.put("summary", taskId);
        content.put("files", Arrays.stream(paths).map(p -> Map.of("path", p, "op", "CREATE", "postHash", "h")).toList());
        return artifact(ArtifactKeys.changesOf(taskId), content, taskId);
    }

    /** An {@code approval/<request>} artifact as the engine publishes it for a human decision. */
    static Artifact approval(String requestId, String taskId, String... rules) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("requestId", requestId);
        content.put("taskId", taskId);
        content.put("kind", "CHANGE_APPROVAL");
        content.put("rules", List.of(rules));
        content.put("reviewer", "ann");
        return artifact("approval/" + requestId, content, "human:ann");
    }

    static Map<String, Artifact> visible(Artifact... artifacts) {
        Map<String, Artifact> visible = new LinkedHashMap<>();
        for (Artifact artifact : artifacts) {
            visible.put(artifact.key(), artifact);
        }
        return visible;
    }

    static TaskContext context(String taskId, String capability, Workspace workspace, Map<String, Artifact> visible) {
        return TaskContext.forTest(TaskSpec.of(taskId, Stage.VALIDATION, capability, List.of()), visible, workspace, List.of());
    }

    static JsonNode output(TaskResult result, String key) {
        return result.outputs().get(key).content();
    }

    static List<String> strings(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asString).toList();
    }
}
