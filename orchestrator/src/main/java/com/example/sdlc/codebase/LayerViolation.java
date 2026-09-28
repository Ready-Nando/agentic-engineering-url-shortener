package com.example.sdlc.codebase;

public record LayerViolation(String fromType, Layer fromLayer, String toType, Layer toLayer, String rule) {
}
