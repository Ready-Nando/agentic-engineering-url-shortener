package com.example.sdlc.codebase;

/**
 * @param distance hops from the nearest seed; 0 is a seed itself
 * @param reason   why the type is impacted, e.g. {@code "depends on LinkService"}
 */
public record ImpactedComponent(String typeName, String path, Layer layer, int distance, String reason) {
}
