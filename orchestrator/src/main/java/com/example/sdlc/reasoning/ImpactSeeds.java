package com.example.sdlc.reasoning;

import java.util.List;

/**
 * The reasoning component's view of where a change starts. The blast radius is then computed
 * deterministically from the real code, and seeds that do not exist in the codebase are rejected.
 */
public record ImpactSeeds(List<String> seeds, String rationale) {

    public ImpactSeeds {
        seeds = seeds == null ? List.of() : List.copyOf(seeds);
    }
}
