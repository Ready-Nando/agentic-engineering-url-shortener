package com.example.sdlc.artifact;

public record ArtifactRef(String key, int version) {

    @Override
    public String toString() {
        return key + "@v" + version;
    }
}
