package com.example.sdlc.codebase;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Layering guardrail: API must not reach PERSISTENCE directly, DOMAIN must not depend on API, SERVICE or
 * PERSISTENCE, and PERSISTENCE must not depend on API or SERVICE. Test units are exempt, and so are
 * dependencies on types that are not in the model.
 */
public final class ArchitectureRules {

    private static final Map<Layer, Set<Layer>> FORBIDDEN = Map.of(
            Layer.API, EnumSet.of(Layer.PERSISTENCE),
            Layer.DOMAIN, EnumSet.of(Layer.API, Layer.SERVICE, Layer.PERSISTENCE),
            Layer.PERSISTENCE, EnumSet.of(Layer.API, Layer.SERVICE));

    public List<LayerViolation> check(CodebaseModel model) {
        Map<String, SourceUnit> byName = model.units().stream()
                .collect(Collectors.toMap(SourceUnit::typeName, Function.identity(), (first, duplicate) -> first));
        List<LayerViolation> violations = new ArrayList<>();
        for (SourceUnit from : model.units()) {
            Set<Layer> forbidden = FORBIDDEN.getOrDefault(from.layer(), Set.of());
            if (from.test() || forbidden.isEmpty()) {
                continue;
            }
            for (String dependency : from.dependsOn()) {
                SourceUnit to = byName.get(dependency);
                if (to != null && !to.test() && forbidden.contains(to.layer())) {
                    violations.add(new LayerViolation(from.typeName(), from.layer(), to.typeName(), to.layer(),
                            from.layer() + " must not depend on " + to.layer()));
                }
            }
        }
        violations.sort(Comparator.comparing(LayerViolation::fromType).thenComparing(LayerViolation::toType));
        return List.copyOf(violations);
    }
}
