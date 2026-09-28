package com.example.sdlc.codebase;

import static java.util.stream.Collectors.toCollection;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Computes what a change to some seeds touches. Seeds are fully qualified or simple type names, source
 * paths, table names or {@code "METHOD /path"} endpoints; seeds matching none of these are reported as
 * unresolved, which is how made-up component names get rejected.
 *
 * <p>Impact spreads backwards along dependencies between main-source types (whoever uses a changed type
 * may break). Endpoints and tables follow from the impacted types, tests from those and from the impacted
 * endpoints' paths; data flows go forwards from each impacted endpoint to the types that issue SQL, at type
 * level (see {@link DataFlow}).
 */
public final class ImpactAnalyzer {

    private static final Pattern ENDPOINT_SEED = Pattern.compile("(?i)(GET|HEAD|POST|PUT|PATCH|DELETE|OPTIONS|TRACE|ANY)\\s+(\\S+)");
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{[^/{}]*}");
    private static final Comparator<ImpactedComponent> BY_DISTANCE =
            Comparator.comparingInt(ImpactedComponent::distance).thenComparing(ImpactedComponent::typeName);

    public ImpactAnalysis analyze(CodebaseModel model, List<String> seeds) {
        Graph graph = new Graph(model);
        SortedSet<String> resolved = new TreeSet<>();
        SortedSet<String> unresolved = new TreeSet<>();
        SortedSet<String> seedTables = new TreeSet<>();
        Map<String, ImpactedComponent> impacted = new HashMap<>();

        // Sorted, so that neither the result nor the reasons depend on the order the caller used.
        SortedSet<String> distinctSeeds = seeds.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .collect(toCollection(TreeSet::new));
        for (String seed : distinctSeeds) {
            (resolveSeed(seed, model, graph, impacted, seedTables) ? resolved : unresolved).add(seed);
        }
        for (SourceUnit unit : graph.mainUnits()) {
            for (String table : seedTables) {
                String access = accessTo(unit, table);
                if (access != null) {
                    impacted.putIfAbsent(unit.typeName(), component(graph, unit.typeName(), 1, access + " " + table));
                }
            }
        }
        addDependents(graph, impacted);

        List<ImpactedComponent> components = impacted.values().stream().sorted(BY_DISTANCE).toList();
        List<Endpoint> endpoints = model.endpoints().stream()
                .filter(e -> impacted.containsKey(e.handlerType()))
                .sorted(Endpoint.ORDER)
                .toList();
        SortedSet<String> tables = new TreeSet<>(seedTables);
        for (ImpactedComponent component : components) {
            SourceUnit unit = graph.unit(component.typeName());
            if (unit != null) {
                tables.addAll(unit.tablesRead());
                tables.addAll(unit.tablesWritten());
            }
        }
        List<Route> routes = endpoints.stream().map(e -> new Route(e, segments(e.path()))).toList();
        Map<String, String> testReasons = new TreeMap<>();
        for (SourceUnit unit : graph.units()) {
            if (unit.test()) {
                testReason(unit, impacted, tables, routes).ifPresent(reason -> testReasons.put(unit.typeName(), reason));
            }
        }
        List<DataFlow> dataFlows = endpoints.stream().flatMap(e -> dataFlows(e, graph).stream()).toList();
        return new ImpactAnalysis(List.copyOf(resolved), List.copyOf(unresolved), components, endpoints,
                List.copyOf(tables), List.copyOf(testReasons.keySet()), dataFlows, testReasons);
    }

    /**
     * Why a test is affected, if it is. Tests that exercise an endpoint over HTTP (MockMvc, a web client) need
     * not reference any impacted type, so a path literal matching an impacted endpoint counts as well.
     */
    private static Optional<String> testReason(SourceUnit test, Map<String, ImpactedComponent> impacted,
                                               SortedSet<String> tables, List<Route> routes) {
        if (impacted.containsKey(test.typeName())) {
            return Optional.of(impacted.get(test.typeName()).reason());
        }
        Optional<ImpactedComponent> referenced = test.dependsOn().stream().map(impacted::get).filter(Objects::nonNull)
                .min(BY_DISTANCE);
        if (referenced.isPresent()) {
            return Optional.of("references " + simpleName(referenced.get().typeName()));
        }
        for (String table : tables) {
            String access = accessTo(test, table);
            if (access != null) {
                return Optional.of(access + " " + table);
            }
        }
        for (String literal : test.pathLiterals()) {
            String[] segments = segments(literal);
            for (Route route : routes) {
                if (segmentsMatch(segments, route.segments())) {
                    return Optional.of("path " + literal + " matches " + route.endpoint().route());
                }
            }
        }
        return Optional.empty();
    }

    private static boolean resolveSeed(String seed, CodebaseModel model, Graph graph,
                                       Map<String, ImpactedComponent> impacted, Set<String> seedTables) {
        Matcher endpointSeed = ENDPOINT_SEED.matcher(seed);
        if (endpointSeed.matches()) {
            String method = endpointSeed.group(1).toUpperCase(Locale.ROOT);
            String route = routeShape(endpointSeed.group(2));
            List<Endpoint> matches = model.endpoints().stream()
                    .filter(e -> methodsMatch(method, e.method()) && routeShape(e.path()).equals(route))
                    .sorted(Endpoint.ORDER)
                    .toList();
            for (Endpoint endpoint : matches) {
                impacted.putIfAbsent(endpoint.handlerType(), component(graph, endpoint.handlerType(), 0, "handles " + endpoint.route()));
            }
            return !matches.isEmpty();
        }
        List<SourceUnit> units = graph.matching(seed);
        if (!units.isEmpty()) {
            units.forEach(u -> impacted.putIfAbsent(u.typeName(), component(graph, u.typeName(), 0, "seed")));
            return true;
        }
        String table = seed.toLowerCase(Locale.ROOT);
        if (graph.isTable(table)) {
            seedTables.add(table);
            return true;
        }
        return false;
    }

    /** Breadth-first over reverse dependencies, so every component gets its smallest distance. */
    private static void addDependents(Graph graph, Map<String, ImpactedComponent> impacted) {
        ArrayDeque<ImpactedComponent> queue = impacted.values().stream().sorted(BY_DISTANCE).collect(toCollection(ArrayDeque::new));
        while (!queue.isEmpty()) {
            ImpactedComponent current = queue.poll();
            String name = simpleName(current.typeName());
            int distance = current.distance() + 1;
            List<ImpactedComponent> next = new ArrayList<>();
            for (String dependent : graph.dependents(current.typeName())) {
                next.add(component(graph, dependent, distance, "depends on " + name));
            }
            for (String port : graph.portsImplementedBy(current.typeName())) {
                next.add(component(graph, port, distance, "implemented by " + name));
            }
            for (ImpactedComponent component : next) {
                if (impacted.putIfAbsent(component.typeName(), component) == null) {
                    queue.add(component);
                }
            }
        }
    }

    /**
     * Breadth-first from the handler type, so the first path reaching a (table, access) pair is a shortest
     * one; ties go to the alphabetically first dependency because neighbours are visited in sorted order.
     */
    private static List<DataFlow> dataFlows(Endpoint endpoint, Graph graph) {
        Map<String, String> reachedFrom = new HashMap<>();
        reachedFrom.put(endpoint.handlerType(), endpoint.handlerType());
        ArrayDeque<String> queue = new ArrayDeque<>(List.of(endpoint.handlerType()));
        Map<String, DataFlow> flows = new TreeMap<>();
        while (!queue.isEmpty()) {
            String typeName = queue.poll();
            SourceUnit unit = graph.unit(typeName);
            if (unit == null) {
                continue;
            }
            for (String table : unit.tablesRead()) {
                flows.computeIfAbsent(table + " READ", k -> new DataFlow(endpoint, pathTo(typeName, reachedFrom), table, "READ"));
            }
            for (String table : unit.tablesWritten()) {
                flows.computeIfAbsent(table + " WRITE", k -> new DataFlow(endpoint, pathTo(typeName, reachedFrom), table, "WRITE"));
            }
            for (String next : graph.dependencies(typeName)) {
                if (reachedFrom.putIfAbsent(next, typeName) == null) {
                    queue.add(next);
                }
            }
        }
        return List.copyOf(flows.values());
    }

    /** The handler type is its own predecessor, which marks the start of the path. */
    private static List<String> pathTo(String typeName, Map<String, String> reachedFrom) {
        List<String> path = new ArrayList<>();
        String step = typeName;
        path.add(step);
        while (!reachedFrom.get(step).equals(step)) {
            step = reachedFrom.get(step);
            path.add(step);
        }
        return path.reversed();
    }

    private static String accessTo(SourceUnit unit, String table) {
        boolean reads = unit.tablesRead().contains(table);
        boolean writes = unit.tablesWritten().contains(table);
        if (reads && writes) {
            return "reads and writes";
        }
        return writes ? "writes" : reads ? "reads" : null;
    }

    private static boolean methodsMatch(String seedMethod, String endpointMethod) {
        return seedMethod.equals(endpointMethod) || seedMethod.equals("ANY") || endpointMethod.equals("ANY");
    }

    /** Path with variables anonymised, so {@code /links/{id}} matches {@code /links/{code:[a-z]+}}. */
    private static String routeShape(String path) {
        return PATH_VARIABLE.matcher(Endpoint.normalizePath(path)).replaceAll("{}");
    }

    /** Split drops trailing empty strings, so the root path "/" has no segment for a variable to match. */
    private static String[] segments(String path) {
        return routeShape(path).split("/");
    }

    /**
     * Whether a concrete or templated path addresses the endpoint path: segment by segment, where a segment
     * holding a variable on either side matches any segment, so {@code /links/abc} and {@code /links/{id}}
     * both match {@code /links/{code}}.
     */
    private static boolean segmentsMatch(String[] segments, String[] expected) {
        if (segments.length != expected.length) {
            return false;
        }
        for (int i = 0; i < segments.length; i++) {
            if (!segments[i].equals(expected[i]) && !segments[i].contains("{}") && !expected[i].contains("{}")) {
                return false;
            }
        }
        return true;
    }

    /** An impacted endpoint with its path split for matching, computed once per analysis. */
    private record Route(Endpoint endpoint, String[] segments) {
    }

    private static ImpactedComponent component(Graph graph, String typeName, int distance, String reason) {
        SourceUnit unit = graph.unit(typeName);
        return unit == null
                ? new ImpactedComponent(typeName, "", Layer.OTHER, distance, reason)
                : new ImpactedComponent(typeName, unit.path(), unit.layer(), distance, reason);
    }

    private static String simpleName(String typeName) {
        return typeName.substring(typeName.lastIndexOf('.') + 1);
    }

    /**
     * Lookups over the model. Edges out of test units are ignored: tests never propagate impact.
     *
     * <p>SourceUnit carries no implements/extends information, so interface-based repositories are
     * approximated: a PERSISTENCE type without SQL that is referenced by a PERSISTENCE type with SQL is
     * taken to be a port implemented by the latter (ClickRepository and JdbcClickRepository). Without
     * this, impact would stop at the adapter, which nobody references, and data flows would stop at the
     * port, which issues no SQL.
     */
    private static final class Graph {

        private final Map<String, SourceUnit> units = new LinkedHashMap<>();
        private final Map<String, List<String>> dependents = new HashMap<>();
        private final Map<String, List<String>> portsByAdapter = new HashMap<>();
        private final Map<String, List<String>> adaptersByPort = new HashMap<>();
        private final Set<String> tables = new HashSet<>();

        Graph(CodebaseModel model) {
            model.units().stream().sorted(Comparator.comparing(SourceUnit::typeName)).forEach(u -> units.putIfAbsent(u.typeName(), u));
            model.tables().forEach(t -> tables.add(t.name()));
            for (SourceUnit unit : units.values()) {
                tables.addAll(unit.tablesRead());
                tables.addAll(unit.tablesWritten());
                if (unit.test()) {
                    continue;
                }
                for (String dependency : unit.dependsOn()) {
                    dependents.computeIfAbsent(dependency, k -> new ArrayList<>()).add(unit.typeName());
                    SourceUnit target = units.get(dependency);
                    if (target != null && isPortAdapter(target, unit)) {
                        portsByAdapter.computeIfAbsent(unit.typeName(), k -> new ArrayList<>()).add(dependency);
                        adaptersByPort.computeIfAbsent(dependency, k -> new ArrayList<>()).add(unit.typeName());
                    }
                }
            }
        }

        private static boolean isPortAdapter(SourceUnit port, SourceUnit adapter) {
            return port.layer() == Layer.PERSISTENCE && adapter.layer() == Layer.PERSISTENCE
                    && !port.test() && !port.touchesTables() && adapter.touchesTables();
        }

        SourceUnit unit(String typeName) {
            return units.get(typeName);
        }

        List<SourceUnit> units() {
            return List.copyOf(units.values());
        }

        List<SourceUnit> mainUnits() {
            return units.values().stream().filter(u -> !u.test()).toList();
        }

        boolean isTable(String name) {
            return tables.contains(name);
        }

        /** Main units referencing the type, sorted. */
        List<String> dependents(String typeName) {
            return dependents.getOrDefault(typeName, List.of());
        }

        List<String> portsImplementedBy(String typeName) {
            return portsByAdapter.getOrDefault(typeName, List.of());
        }

        /** Main units the type references plus the adapters implementing it if it is a port, sorted. */
        SortedSet<String> dependencies(String typeName) {
            SortedSet<String> next = new TreeSet<>(adaptersByPort.getOrDefault(typeName, List.of()));
            for (String dependency : units.get(typeName).dependsOn()) {
                SourceUnit target = units.get(dependency);
                if (target != null && !target.test()) {
                    next.add(dependency);
                }
            }
            return next;
        }

        /** Units named by a fully qualified name, else by simple name (all of them), else by source path. */
        List<SourceUnit> matching(String seed) {
            SourceUnit exact = units.get(seed);
            if (exact != null) {
                return List.of(exact);
            }
            List<SourceUnit> bySimpleName = units.values().stream().filter(u -> seed.equals(u.simpleName())).toList();
            if (!bySimpleName.isEmpty()) {
                return bySimpleName;
            }
            String path = seed.replace('\\', '/');
            return units.values().stream().filter(u -> path.equals(u.path())).toList();
        }
    }
}
