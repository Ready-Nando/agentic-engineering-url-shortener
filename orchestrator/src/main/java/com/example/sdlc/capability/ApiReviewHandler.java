package com.example.sdlc.capability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.example.sdlc.Json;
import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.codebase.CodebaseIndexer;
import com.example.sdlc.codebase.Endpoint;
import com.example.sdlc.engine.OutputArtifact;
import com.example.sdlc.engine.TaskContext;
import com.example.sdlc.engine.TaskHandler;
import com.example.sdlc.engine.TaskResult;

import tools.jackson.databind.JsonNode;

/**
 * API change control: compares the published OpenAPI document before and after the change (removed operations,
 * removed response properties and newly required properties are breaking), and checks the designed contract is
 * both implemented (per static analysis of controllers) and documented.
 */
final class ApiReviewHandler implements TaskHandler {

    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete");

    private final CodebaseIndexer indexer;

    ApiReviewHandler(CodebaseIndexer indexer) {
        this.indexer = indexer;
    }

    @Override
    public TaskResult execute(TaskContext context) {
        context.readAll(ArtifactKeys.CHANGES_PREFIX);
        JsonNode contract = context.find(ArtifactKeys.API_CONTRACT, JsonNode.class).orElse(null);
        JsonNode before = context.workspace().readBaseline(WorkspaceFacts.OPENAPI).map(Json.YAML::readTree).orElse(null);
        JsonNode after = context.readFile(WorkspaceFacts.OPENAPI).map(Json.YAML::readTree).orElse(null);

        List<String> breaking = new ArrayList<>();
        Set<String> operationsBefore = operations(before);
        Set<String> operationsAfter = operations(after);
        operationsBefore.stream().filter(op -> !operationsAfter.contains(op)).forEach(op -> breaking.add("operation removed: " + op));
        compareSchemas(before, after, breaking);

        Set<String> implemented = new TreeSet<>();
        for (Endpoint endpoint : indexer.index(context.workspace().root()).endpoints()) {
            implemented.add(endpoint.method().toUpperCase() + " " + endpoint.path());
        }
        List<String> undocumented = new ArrayList<>();
        List<String> unimplemented = new ArrayList<>();
        if (contract != null) {
            for (JsonNode operation : contract.path("operations")) {
                String change = operation.path("change").asString("NEW");
                if (change.equals("UNCHANGED")) {
                    continue;
                }
                String key = operation.path("method").asString().toUpperCase() + " " + operation.path("path").asString();
                if (!operationsAfter.contains(key)) {
                    undocumented.add(key);
                }
                if (!implemented.contains(key)) {
                    unimplemented.add(key);
                }
            }
        }
        implemented.stream().filter(op -> !operationsAfter.contains(op)).forEach(op -> undocumented.add(op + " (implemented but not in OpenAPI)"));

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("status", !breaking.isEmpty() ? "BREAKING" : undocumented.isEmpty() && unimplemented.isEmpty() ? "COMPATIBLE" : "INCOMPLETE");
        content.put("breakingChanges", breaking);
        content.put("addedOperations", operationsAfter.stream().filter(op -> !operationsBefore.contains(op)).toList());
        content.put("undocumented", undocumented);
        content.put("unimplemented", unimplemented);
        content.put("operationsBefore", operationsBefore.size());
        content.put("operationsAfter", operationsAfter.size());
        return TaskResult.of("API review " + content.get("status") + ": " + breaking.size() + " breaking, "
                        + undocumented.size() + " undocumented, " + unimplemented.size() + " unimplemented",
                ArtifactKeys.API_COMPATIBILITY, OutputArtifact.of("api-review", content));
    }

    /** Names of component schemas referenced from request bodies. */
    private static Set<String> requestSchemas(JsonNode openApi) {
        Set<String> names = new TreeSet<>();
        for (Map.Entry<String, JsonNode> path : openApi.path("paths").properties()) {
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                operation.getValue().path("requestBody").findValues("$ref").forEach(ref ->
                        names.add(ref.asString().substring(ref.asString().lastIndexOf('/') + 1)));
            }
        }
        return names;
    }

    private static Set<String> operations(JsonNode openApi) {
        Set<String> operations = new TreeSet<>();
        if (openApi == null) {
            return operations;
        }
        for (Map.Entry<String, JsonNode> path : openApi.path("paths").properties()) {
            for (Map.Entry<String, JsonNode> method : path.getValue().properties()) {
                if (METHODS.contains(method.getKey())) {
                    operations.add(method.getKey().toUpperCase() + " " + path.getKey());
                }
            }
        }
        return operations;
    }

    private static void compareSchemas(JsonNode before, JsonNode after, List<String> breaking) {
        if (before == null || after == null) {
            return;
        }
        Set<String> requestSchemas = requestSchemas(after);
        JsonNode schemasAfter = after.path("components").path("schemas");
        for (Map.Entry<String, JsonNode> schema : before.path("components").path("schemas").properties()) {
            JsonNode updated = schemasAfter.path(schema.getKey());
            if (updated.isMissingNode()) {
                breaking.add("schema removed: " + schema.getKey());
                continue;
            }
            for (Map.Entry<String, JsonNode> property : schema.getValue().path("properties").properties()) {
                if (updated.path("properties").path(property.getKey()).isMissingNode()) {
                    breaking.add("property removed: " + schema.getKey() + "." + property.getKey());
                }
            }
            // A newly required property breaks clients only where they send it (request bodies); in a
            // response it merely promises an additional field.
            if (requestSchemas.contains(schema.getKey())) {
                Set<String> requiredBefore = new TreeSet<>();
                schema.getValue().path("required").forEach(r -> requiredBefore.add(r.asString()));
                updated.path("required").forEach(r -> {
                    if (!requiredBefore.contains(r.asString())) {
                        breaking.add("request property became required: " + schema.getKey() + "." + r.asString());
                    }
                });
            }
        }
    }
}
