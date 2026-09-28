package com.example.sdlc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** Shared, thread-safe mapper configuration. Artifacts are hashed over a canonical (sorted) JSON form. */
public final class Json {

    // Omitted numeric fields in structured reasoning output (e.g. maxAttempts) mean "use the default".
    public static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    public static final YAMLMapper YAML = YAMLMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    private static final JsonMapper CANONICAL = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private Json() {
    }

    public static JsonNode tree(Object value) {
        return MAPPER.valueToTree(value);
    }

    public static <T> T convert(JsonNode node, Class<T> type) {
        return MAPPER.treeToValue(node, type);
    }

    public static String contentHash(JsonNode node) {
        // Round-trip through a map so object keys are sorted regardless of producer field order.
        Object canonical = CANONICAL.treeToValue(node, Object.class);
        return sha256(CANONICAL.writeValueAsString(canonical));
    }

    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
