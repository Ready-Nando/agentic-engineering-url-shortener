package com.example.sdlc.codebase;

import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record CodebaseModel(List<SourceUnit> units, List<Endpoint> endpoints, List<Table> tables) {

    public CodebaseModel {
        units = List.copyOf(units);
        endpoints = List.copyOf(endpoints);
        tables = List.copyOf(tables);
    }

    /**
     * Looks a unit up by fully qualified name, falling back to the simple name. An ambiguous simple name
     * resolves to its only main-source match, or to nothing.
     */
    public Optional<SourceUnit> unit(String fqnOrSimpleName) {
        Optional<SourceUnit> exact = units.stream().filter(u -> u.typeName().equals(fqnOrSimpleName)).findFirst();
        if (exact.isPresent()) {
            return exact;
        }
        List<SourceUnit> bySimpleName = units.stream().filter(u -> u.simpleName().equals(fqnOrSimpleName)).toList();
        if (bySimpleName.size() == 1) {
            return Optional.of(bySimpleName.getFirst());
        }
        List<SourceUnit> main = bySimpleName.stream().filter(u -> !u.test()).toList();
        return main.size() == 1 ? Optional.of(main.getFirst()) : Optional.empty();
    }

    /** Units that reference {@code fqn} directly, test units included. */
    public List<SourceUnit> dependentsOf(String fqn) {
        return units.stream()
                .filter(u -> u.dependsOn().contains(fqn))
                .sorted(Comparator.comparing(SourceUnit::typeName))
                .toList();
    }

    /** A few lines suitable for a log or a prompt: unit counts per layer, then endpoints and tables. */
    public String summary() {
        Map<Layer, Long> perLayer = units.stream()
                .collect(groupingBy(SourceUnit::layer, () -> new EnumMap<>(Layer.class), counting()));
        StringBuilder out = new StringBuilder()
                .append(units.size()).append(" units (")
                .append(perLayer.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).collect(joining(", ")))
                .append("), ").append(endpoints.size()).append(" endpoints, ")
                .append(tables.size()).append(" tables");
        for (Endpoint endpoint : endpoints) {
            String handler = endpoint.handlerType().substring(endpoint.handlerType().lastIndexOf('.') + 1);
            out.append("\n  ").append(endpoint.route()).append(" -> ").append(handler).append('#').append(endpoint.handlerMethod());
        }
        for (Table table : tables) {
            out.append("\n  ").append(table.name()).append('(').append(String.join(", ", table.columns())).append(')');
        }
        return out.toString();
    }
}
