package com.example.sdlc.engine;

import com.example.sdlc.Json;

import tools.jackson.databind.JsonNode;

/** A draft artifact returned by a handler; it becomes a versioned {@code Artifact} only if the attempt commits. */
public record OutputArtifact(String kind, JsonNode content) {

    public static OutputArtifact of(String kind, Object value) {
        return new OutputArtifact(kind, Json.tree(value));
    }

    public <T> T as(Class<T> type) {
        return Json.convert(content, type);
    }
}
