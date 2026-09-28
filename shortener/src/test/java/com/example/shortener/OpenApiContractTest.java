package com.example.shortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Keeps the hand-written OpenAPI document and the code in sync: adding, removing or moving an endpoint,
 * or adding, removing or renaming a field of a JSON body, on either side without the other fails this test.
 */
class OpenApiContractTest extends AbstractIntegrationTest {

    private static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

    // "{code:[A-Za-z0-9_-]{4,32}}" -> "{code}"; allows one level of braces inside the regex for quantifiers.
    private static final String PATH_VARIABLE_REGEX = "\\{(\\w+):(?:[^{}]|\\{[^{}]*})*}";

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Test
    void specDocumentsExactlyTheEndpointsTheApplicationExposes() throws IOException {
        Set<String> exposed = exposedOperations().stream()
                .map(Operation::toString)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(documentedOperations()).isEqualTo(exposed);
    }

    /**
     * The bodies are read off the handler methods, so a new endpoint or a changed body type is checked
     * without touching this test: a record {@code @RequestBody} against the operation's request body, and a
     * returned record (or the body type of a returned {@code ResponseEntity}) against every documented 2xx
     * response. Records nested in them, as a component or as the elements of a list, are checked against the
     * property's schema.
     */
    @Test
    void specDocumentsExactlyTheFieldsOfEveryJsonBody() throws IOException {
        Map<String, Object> spec = loadSpec();
        List<String> mismatches = new ArrayList<>();
        for (Operation operation : exposedOperations()) {
            List<String> location = List.of("paths", operation.path(), operation.method().toLowerCase(Locale.ROOT));
            for (MethodParameter parameter : operation.handler().getMethodParameters()) {
                ResolvableType type = ResolvableType.forMethodParameter(parameter);
                if (parameter.hasParameterAnnotation(RequestBody.class) && isRecordBody(type)) {
                    compareJsonBody(spec, operation + " request", append(location, "requestBody"), type, mismatches);
                }
            }

            ResolvableType returned = ResolvableType.forMethodReturnType(operation.handler().getMethod());
            if (HttpEntity.class.isAssignableFrom(returned.toClass())) {
                returned = returned.as(HttpEntity.class).getGeneric(0);
            }
            if (isRecordBody(returned)) {
                Object responses = at(spec, append(location, "responses"));
                List<String> statuses = responses instanceof Map<?, ?> map
                        ? map.keySet().stream().map(String::valueOf).filter(status -> status.startsWith("2")).toList()
                        : List.of();
                if (statuses.isEmpty()) {
                    mismatches.add(operation + ": the spec documents no 2xx response");
                }
                for (String status : statuses) {
                    compareJsonBody(spec, operation + " " + status + " response", append(location, "responses", status),
                            returned, mismatches);
                }
            }
        }
        assertThat(mismatches).withFailMessage(() -> String.join(System.lineSeparator(), mismatches)).isEmpty();
    }

    @Test
    void specIsServedAsAStaticResource() throws Exception {
        mockMvc.perform(get("/openapi.yaml"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("openapi: 3.0.3")));
    }

    private List<Operation> exposedOperations() {
        List<Operation> operations = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            if (!handler.getBeanType().getPackageName().startsWith("com.example.shortener")) {
                continue;
            }
            RequestMappingInfo info = entry.getKey();
            for (String pattern : info.getPatternValues()) {
                String path = pattern.replaceAll(PATH_VARIABLE_REGEX, "{$1}");
                if (info.getMethodsCondition().getMethods().isEmpty()) {
                    operations.add(new Operation("ANY", path, handler));
                }
                info.getMethodsCondition().getMethods()
                        .forEach(method -> operations.add(new Operation(method.name(), path, handler)));
            }
        }
        return operations;
    }

    @SuppressWarnings("unchecked")
    private Set<String> documentedOperations() throws IOException {
        Set<String> operations = new TreeSet<>();
        Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) loadSpec().get("paths");
        paths.forEach((path, item) -> item.keySet().stream()
                .filter(HTTP_METHODS::contains)
                .forEach(method -> operations.add(method.toUpperCase(Locale.ROOT) + " " + path)));
        return operations;
    }

    /**
     * Only records (and lists of them) have fields this test can compare; other body types, such as
     * {@code Void}, are skipped.
     */
    private static boolean isRecordBody(ResolvableType type) {
        return type.toClass().isRecord() || elementType(type).toClass().isRecord();
    }

    private static ResolvableType elementType(ResolvableType type) {
        if (type.isArray()) {
            return type.getComponentType();
        }
        return Collection.class.isAssignableFrom(type.toClass()) ? type.asCollection().getGeneric(0) : ResolvableType.NONE;
    }

    /**
     * Jackson names every JSON property after its record component, so the schema's property names must
     * be exactly the component names.
     */
    private static void compare(Map<String, Object> spec, String where, Object schema, ResolvableType type,
                                List<String> mismatches) {
        Class<?> raw = type.toClass();
        if (!raw.isRecord()) {
            ResolvableType element = elementType(type);
            if (element != ResolvableType.NONE) {
                compare(spec, where + "[]", resolve(spec, schema).get("items"), element, mismatches);
            }
            return;
        }

        Map<String, Object> properties = properties(spec, resolve(spec, schema));
        Set<String> components = Arrays.stream(raw.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> missingInSpec = new TreeSet<>(components);
        missingInSpec.removeAll(properties.keySet());
        Set<String> missingInRecord = new TreeSet<>(properties.keySet());
        missingInRecord.removeAll(components);
        if (!missingInSpec.isEmpty() || !missingInRecord.isEmpty()) {
            mismatches.add("%s <-> %s: missing in the spec %s, missing in the record %s"
                    .formatted(where, recordName(raw), missingInSpec, missingInRecord));
        }

        for (RecordComponent component : raw.getRecordComponents()) {
            Object property = properties.get(component.getName());
            if (property != null) {
                compare(spec, where + " > " + component.getName(), property,
                        ResolvableType.forType(component.getGenericType(), type), mismatches);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> spec, Map<String, Object> schema) {
        Map<String, Object> properties = new LinkedHashMap<>();
        // allOf is how OpenAPI 3.0 composes schemas, for example to make a referenced schema nullable.
        for (Object part : (List<Object>) schema.getOrDefault("allOf", List.of())) {
            properties.putAll(properties(spec, resolve(spec, part)));
        }
        properties.putAll((Map<String, Object>) schema.getOrDefault("properties", Map.of()));
        return properties;
    }

    private static void compareJsonBody(Map<String, Object> spec, String where, List<String> requestOrResponse,
                                        ResolvableType type, List<String> mismatches) {
        Object schema = at(spec, append(requestOrResponse, "content", "application/json", "schema"));
        if (schema == null) {
            mismatches.add(where + ": the spec defines no application/json schema");
        } else {
            compare(spec, where, schema, type, mismatches);
        }
    }

    /**
     * A missing schema resolves to an empty one, which then fails the comparison with every record
     * component reported as missing in the spec.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> resolve(Map<String, Object> spec, Object schema) {
        return deref(spec, schema) instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /**
     * Follows local {@code $ref}s such as {@code #/components/schemas/Link} or
     * {@code #/components/responses/LinkNotFound}, including chained ones.
     */
    private static Object deref(Map<String, Object> spec, Object node) {
        Object resolved = node;
        while (resolved instanceof Map<?, ?> map && map.get("$ref") instanceof String ref) {
            assertThat(ref).as("only local references are supported").startsWith("#/");
            resolved = at(spec, Arrays.stream(ref.substring(2).split("/"))
                    .map(token -> token.replace("~1", "/").replace("~0", "~"))
                    .toList());
        }
        return resolved;
    }

    /**
     * The node at the location, following references on the way; {@code null} if there is none.
     */
    private static Object at(Map<String, Object> spec, List<String> location) {
        Object node = spec;
        for (String key : location) {
            if (!(deref(spec, node) instanceof Map<?, ?> map)) {
                return null;
            }
            node = map.get(key);
        }
        return deref(spec, node);
    }

    private static List<String> append(List<String> location, String... keys) {
        return Stream.concat(location.stream(), Stream.of(keys)).toList();
    }

    private static String recordName(Class<?> type) {
        return type.getName().substring(type.getPackageName().length() + 1).replace('$', '.');
    }

    private static Map<String, Object> loadSpec() throws IOException {
        try (InputStream in = new ClassPathResource("static/openapi.yaml").getInputStream()) {
            return new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        }
    }

    private record Operation(String method, String path, HandlerMethod handler) {

        @Override
        public String toString() {
            return method + " " + path;
        }
    }
}
