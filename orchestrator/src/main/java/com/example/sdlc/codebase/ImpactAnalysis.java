package com.example.sdlc.codebase;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * @param seeds           seeds that matched something in the model
 * @param unresolvedSeeds seeds that matched nothing, e.g. components an agent made up
 * @param tests           test types that reference an impacted type, touch an impacted table or contain a path
 *                        literal matching an impacted endpoint
 * @param testReasons     why each of {@code tests} is affected, keyed by test type name
 */
public record ImpactAnalysis(
        List<String> seeds,
        List<String> unresolvedSeeds,
        List<ImpactedComponent> components,
        List<Endpoint> endpoints,
        List<String> tables,
        List<String> tests,
        List<DataFlow> dataFlows,
        Map<String, String> testReasons) {

    public ImpactAnalysis {
        seeds = List.copyOf(seeds);
        unresolvedSeeds = List.copyOf(unresolvedSeeds);
        components = List.copyOf(components);
        endpoints = List.copyOf(endpoints);
        tables = List.copyOf(tables);
        tests = List.copyOf(tests);
        dataFlows = List.copyOf(dataFlows);
        // Sorted rather than Map.copyOf, whose iteration order changes from one JVM run to the next.
        testReasons = Collections.unmodifiableSortedMap(new TreeMap<>(testReasons));
    }
}
