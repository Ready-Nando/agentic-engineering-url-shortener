package com.example.sdlc.capability;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.example.sdlc.Json;
import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.codebase.CodebaseIndexer;
import com.example.sdlc.codebase.Endpoint;
import com.example.sdlc.engine.OutputArtifact;
import com.example.sdlc.engine.TaskContext;
import com.example.sdlc.engine.TaskHandler;
import com.example.sdlc.engine.TaskResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

/**
 * API change control: compares the published OpenAPI document before and after the change (removed operations,
 * properties removed or retyped at any depth, newly required request properties and parameters, request alternatives
 * dropped, and success responses removed, re-pointed or no longer served as a documented media type are breaking), treats a documented operation whose baseline handler is gone as
 * breaking, and checks the designed contract and every newly documented operation are both implemented (per
 * static analysis of controllers) and documented.
 */
final class ApiReviewHandler implements TaskHandler {

    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete");
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{[^/}]*}");
    private static final int MAX_DEPTH = 32;

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

        // A change can surface in several places (an operation and the component it references): report it once.
        Set<String> breaking = new LinkedHashSet<>();
        Set<String> operationsBefore = operations(before);
        Set<String> operationsAfter = operations(after);
        Set<String> documentedBefore = canonical(operationsBefore);
        Set<String> documentedAfter = canonical(operationsAfter);
        operationsBefore.stream().filter(op -> !documentedAfter.contains(canonical(op))).forEach(op -> breaking.add("operation removed: " + op));
        if (before != null && after != null) {
            new SpecDiff(before, after, breaking).compare();
        }

        Set<String> implemented = implemented(context.workspace().root());
        Set<String> implementedNow = canonical(implemented);
        Set<String> implementedBefore = canonical(implemented(context.workspace().baselineRoot()));
        // A still-documented operation whose handler is gone fails its clients like a removed one. Judged against
        // the baseline's own index, so handlers the static analysis cannot see on either side do not count.
        operationsAfter.stream().filter(op -> implementedBefore.contains(canonical(op)) && !implementedNow.contains(canonical(op)))
                .forEach(op -> breaking.add("operation no longer implemented: " + op));
        List<String> undocumented = new ArrayList<>();
        List<String> unimplemented = new ArrayList<>();
        if (contract != null) {
            for (JsonNode operation : contract.path("operations")) {
                String change = operation.path("change").asString("NEW");
                String key = operation.path("method").asString().toUpperCase() + " " + operation.path("path").asString();
                // The designer may write the path the way the controller maps it ("/{code:[a-z]+}", "/{id}/"):
                // clients only see the route, so it is compared the way operations are.
                String route = canonical(key);
                // The label is the designer's claim: it only exempts an operation the baseline already documented.
                if (change.equals("UNCHANGED") && documentedBefore.contains(route)) {
                    continue;
                }
                if (!documentedAfter.contains(route)) {
                    undocumented.add(key);
                }
                if (!implementedNow.contains(route)) {
                    unimplemented.add(key);
                }
            }
        }
        implemented.stream().filter(op -> !documentedAfter.contains(canonical(op)))
                .forEach(op -> undocumented.add(op + " (implemented but not in OpenAPI)"));
        // Newly documented operations must exist whether or not the design mentions them.
        Set<String> reported = canonical(Set.copyOf(unimplemented));
        operationsAfter.stream().filter(op -> !documentedBefore.contains(canonical(op)) && !implementedNow.contains(canonical(op))
                        && !reported.contains(canonical(op)))
                .forEach(unimplemented::add);

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("status", !breaking.isEmpty() ? "BREAKING" : undocumented.isEmpty() && unimplemented.isEmpty() ? "COMPATIBLE" : "INCOMPLETE");
        content.put("breakingChanges", List.copyOf(breaking));
        content.put("addedOperations", operationsAfter.stream().filter(op -> !documentedBefore.contains(canonical(op))).toList());
        content.put("undocumented", undocumented);
        content.put("unimplemented", unimplemented);
        content.put("operationsBefore", operationsBefore.size());
        content.put("operationsAfter", operationsAfter.size());
        return TaskResult.of("API review " + content.get("status") + ": " + breaking.size() + " breaking, "
                        + undocumented.size() + " undocumented, " + unimplemented.size() + " unimplemented",
                ArtifactKeys.API_COMPATIBILITY, OutputArtifact.of("api-review", content));
    }

    private Set<String> implemented(Path module) {
        Set<String> implemented = new TreeSet<>();
        for (Endpoint endpoint : indexer.index(module).endpoints()) {
            implemented.add(endpoint.method().toUpperCase() + " " + endpoint.path());
        }
        return implemented;
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

    /** Operation keys as clients see them: path variables are positional, so their names and patterns do not matter. */
    private static Set<String> canonical(Set<String> operations) {
        Set<String> canonical = new TreeSet<>();
        operations.forEach(op -> canonical.add(canonical(op)));
        return canonical;
    }

    private static String canonical(String operation) {
        int space = operation.indexOf(' ');
        String method = space < 0 ? "" : operation.substring(0, space);
        String path = Endpoint.normalizePath(operation.substring(space + 1));
        return method.toUpperCase(Locale.ROOT) + " " + PATH_VARIABLE.matcher(path).replaceAll("{}");
    }

    /**
     * What an existing client relies on, compared between two OpenAPI documents: the parameters, request bodies and
     * success responses of every operation both document, and every component schema, walked through local
     * {@code $ref}s, nested properties, array items and {@code allOf}/{@code oneOf}/{@code anyOf} members. Removing a
     * property or changing its type anywhere breaks clients; a newly required property breaks only the requests
     * clients send, in a response it merely promises more.
     */
    private static final class SpecDiff {

        /** A schema pair already compared in one direction; also stops recursive schemas. */
        private record Visit(JsonNode before, JsonNode after, boolean request) {
        }

        private final JsonNode before;
        private final JsonNode after;
        private final Set<String> breaking;
        private final Set<Visit> visited = new HashSet<>();

        SpecDiff(JsonNode before, JsonNode after, Set<String> breaking) {
            this.before = before;
            this.after = after;
            this.breaking = breaking;
        }

        void compare() {
            Map<String, Operation> operationsAfter = operationsByRoute(after);
            operationsByRoute(before).forEach((route, operation) -> {
                Operation updated = operationsAfter.get(route);
                if (updated != null) {
                    compareParameters(operation, updated);
                    compareRequestBody(operation, updated);
                    compareResponses(operation, updated);
                }
            });
            JsonNode schemasAfter = after.path("components").path("schemas");
            for (Map.Entry<String, JsonNode> schema : before.path("components").path("schemas").properties()) {
                JsonNode updated = schemasAfter.path(schema.getKey());
                if (updated.isMissingNode()) {
                    breaking.add("schema removed: " + schema.getKey());
                } else {
                    compareResolved(schema.getKey(), resolve(before, schema.getValue()), resolve(after, updated), false);
                }
            }
        }

        private record Operation(String key, String path, JsonNode pathItem, JsonNode node) {
        }

        private static Map<String, Operation> operationsByRoute(JsonNode openApi) {
            Map<String, Operation> operations = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> path : openApi.path("paths").properties()) {
                for (Map.Entry<String, JsonNode> method : path.getValue().properties()) {
                    if (METHODS.contains(method.getKey())) {
                        String key = method.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey();
                        operations.put(canonical(key), new Operation(key, path.getKey(), path.getValue(), method.getValue()));
                    }
                }
            }
            return operations;
        }

        private void compareParameters(Operation operation, Operation updated) {
            // Path variables are part of the route: a renamed one is the same parameter under another name.
            boolean samePath = operation.path().equals(updated.path());
            Map<String, JsonNode> parametersBefore = parameters(before, operation, samePath);
            Map<String, JsonNode> parametersAfter = parameters(after, updated, samePath);
            parametersBefore.forEach((name, parameter) -> {
                JsonNode now = parametersAfter.get(name);
                if (now == null) {
                    breaking.add("parameter removed: " + operation.key() + " " + name);
                } else {
                    if (!required(parameter) && required(now)) {
                        breaking.add("parameter became required: " + operation.key() + " " + name);
                    }
                    compareSchema(operation.key() + " " + name, parameter.path("schema"), now.path("schema"), true);
                }
            });
            parametersAfter.forEach((name, parameter) -> {
                if (!parametersBefore.containsKey(name) && required(parameter)) {
                    breaking.add("required parameter added: " + operation.key() + " " + name);
                }
            });
        }

        /** Path-level and operation-level parameters by {@code "<in> <name>"}; the operation's own win. */
        private static Map<String, JsonNode> parameters(JsonNode openApi, Operation operation, boolean withPath) {
            Map<String, JsonNode> parameters = new LinkedHashMap<>();
            for (JsonNode declared : List.of(operation.pathItem().path("parameters"), operation.node().path("parameters"))) {
                for (JsonNode reference : declared) {
                    JsonNode parameter = resolve(openApi, reference);
                    String in = parameter.path("in").asString("");
                    if (withPath || !in.equals("path")) {
                        // Header names are case-insensitive.
                        String name = parameter.path("name").asString("");
                        parameters.put(in + " " + (in.equals("header") ? name.toLowerCase(Locale.ROOT) : name), parameter);
                    }
                }
            }
            return parameters;
        }

        private static boolean required(JsonNode parameter) {
            return parameter.path("required").asBoolean(false) || parameter.path("in").asString("").equals("path");
        }

        private void compareRequestBody(Operation operation, Operation updated) {
            JsonNode bodyAfter = resolve(after, updated.node().path("requestBody")).path("content");
            for (Map.Entry<String, JsonNode> media : resolve(before, operation.node().path("requestBody")).path("content").properties()) {
                JsonNode now = bodyAfter.path(media.getKey());
                if (!now.isMissingNode()) {
                    compareSchema(operation.key() + " request", media.getValue().path("schema"), now.path("schema"), true);
                }
            }
        }

        private void compareResponses(Operation operation, Operation updated) {
            for (Map.Entry<String, JsonNode> response : operation.node().path("responses").properties()) {
                if (!response.getKey().startsWith("2")) {
                    continue;
                }
                String where = operation.key() + " " + response.getKey() + " response";
                JsonNode now = updated.node().path("responses").path(response.getKey());
                if (now.isMissingNode()) {
                    breaking.add("response removed: " + operation.key() + " " + response.getKey());
                    continue;
                }
                JsonNode contentAfter = resolve(after, now).path("content");
                for (Map.Entry<String, JsonNode> media : resolve(before, response.getValue()).path("content").properties()) {
                    JsonNode schemaBefore = media.getValue().path("schema");
                    JsonNode schemaAfter = contentAfter.path(media.getKey()).path("schema");
                    String returnedBefore = returned(schemaBefore);
                    String returnedAfter = returned(schemaAfter);
                    if (!schemaBefore.isMissingNode() && schemaAfter.isMissingNode()) {
                        // A client that parses the body it used to get cannot parse another media type or none.
                        breaking.add("response content removed: " + operation.key() + " " + response.getKey() + " " + media.getKey());
                    } else if (returnedBefore != null && returnedAfter != null && !returnedBefore.equals(returnedAfter)) {
                        breaking.add("response schema changed: " + where + " " + name(returnedBefore) + " -> " + name(returnedAfter));
                    } else {
                        compareSchema(where, schemaBefore, schemaAfter, false);
                    }
                }
            }
        }

        /** Compares two (possibly referencing) schemas; a shared component is reported under its own name. */
        private void compareSchema(String where, JsonNode schemaBefore, JsonNode schemaAfter, boolean request) {
            String reference = reference(schemaBefore);
            compareResolved(reference != null && reference.equals(reference(schemaAfter)) ? name(reference) : where,
                    resolve(before, schemaBefore), resolve(after, schemaAfter), request);
        }

        private void compareResolved(String where, JsonNode schemaBefore, JsonNode schemaAfter, boolean request) {
            // A dangling reference is not this comparison's finding: a removed component is reported as such.
            if (schemaBefore.isMissingNode() || schemaAfter.isMissingNode() || !visited.add(new Visit(schemaBefore, schemaAfter, request))) {
                return;
            }
            JsonNode typeBefore = schemaBefore.path("type");
            JsonNode typeAfter = schemaAfter.path("type");
            if (!typeBefore.isMissingNode() && !typeAfter.isMissingNode() && !typeBefore.equals(typeAfter)) {
                breaking.add("type changed: " + where + " " + typeBefore.asString() + " -> " + typeAfter.asString());
            }
            JsonNode formatBefore = schemaBefore.path("format");
            if (!formatBefore.isMissingNode() && !formatBefore.equals(schemaAfter.path("format"))) {
                breaking.add("format changed: " + where + " " + formatBefore.asString() + " -> " + schemaAfter.path("format").asString("none"));
            }
            Map<String, JsonNode> propertiesAfter = properties(after, schemaAfter, 0);
            properties(before, schemaBefore, 0).forEach((name, property) -> {
                JsonNode now = propertiesAfter.get(name);
                if (now == null) {
                    breaking.add("property removed: " + where + "." + name);
                } else {
                    compareSchema(where + "." + name, property, now, request);
                }
            });
            if (request) {
                Set<String> requiredBefore = required(before, schemaBefore, 0);
                required(after, schemaAfter, 0).stream().filter(name -> !requiredBefore.contains(name))
                        .forEach(name -> breaking.add("request property became required: " + where + "." + name));
            }
            if (schemaBefore.has("items") && schemaAfter.has("items")) {
                compareSchema(where + "[]", schemaBefore.path("items"), schemaAfter.path("items"), request);
            }
            for (String alternatives : List.of("oneOf", "anyOf")) {
                // Alternatives are unordered: a referenced member is matched by its component, so inserting one is
                // additive; inline members can only be matched by their order among the inline ones.
                Map<String, JsonNode> referencedAfter = new HashMap<>();
                List<JsonNode> inlineAfter = new ArrayList<>();
                schemaAfter.path(alternatives).forEach(member -> {
                    if (reference(member) == null) {
                        inlineAfter.add(member);
                    } else {
                        referencedAfter.put(reference(member), member);
                    }
                });
                int inline = 0;
                for (JsonNode member : schemaBefore.path(alternatives)) {
                    String reference = reference(member);
                    if (reference == null) {
                        if (inline < inlineAfter.size()) {
                            compareSchema(where + " " + alternatives + "[" + inline + "]", member, inlineAfter.get(inline), request);
                        }
                        inline++;
                    } else if (referencedAfter.containsKey(reference)) {
                        compareSchema(where, member, referencedAfter.get(reference), request);
                    } else if (request) {
                        // A response may stop returning a variant; a request that stops accepting one rejects clients.
                        breaking.add("request alternative removed: " + where + " " + alternatives + " " + name(reference));
                    }
                }
            }
        }

        /** Own properties plus those every {@code allOf} member contributes, which clients see as one object. */
        private static Map<String, JsonNode> properties(JsonNode openApi, JsonNode schema, int depth) {
            Map<String, JsonNode> properties = new LinkedHashMap<>();
            if (depth < MAX_DEPTH) {
                schema.path("allOf").forEach(member -> properties.putAll(properties(openApi, resolve(openApi, member), depth + 1)));
            }
            schema.path("properties").properties().forEach(property -> properties.put(property.getKey(), property.getValue()));
            return properties;
        }

        private static Set<String> required(JsonNode openApi, JsonNode schema, int depth) {
            Set<String> required = new TreeSet<>();
            if (depth < MAX_DEPTH) {
                schema.path("allOf").forEach(member -> required.addAll(required(openApi, resolve(openApi, member), depth + 1)));
            }
            schema.path("required").forEach(name -> required.add(name.asString()));
            return required;
        }

        /** Follows local references; anything else (an external document, a dangling or cyclic chain) is missing. */
        private static JsonNode resolve(JsonNode openApi, JsonNode node) {
            JsonNode resolved = node;
            for (int hops = 0; hops < MAX_DEPTH && reference(resolved) != null; hops++) {
                String reference = reference(resolved);
                resolved = reference.startsWith("#/") ? openApi.at(reference.substring(1)) : MissingNode.getInstance();
            }
            return reference(resolved) == null ? resolved : MissingNode.getInstance();
        }

        /** The component a response returns, directly or as the items of inline arrays ({@code Link}, {@code Link[]}). */
        private static String returned(JsonNode schema) {
            JsonNode current = schema;
            String dimensions = "";
            for (int depth = 0; depth < MAX_DEPTH && reference(current) == null && current.has("items"); depth++) {
                current = current.path("items");
                dimensions += "[]";
            }
            return reference(current) == null ? null : reference(current) + dimensions;
        }

        private static String reference(JsonNode schema) {
            return schema.path("$ref").isString() ? schema.path("$ref").asString() : null;
        }

        private static String name(String reference) {
            return reference.substring(reference.lastIndexOf('/') + 1);
        }
    }
}
