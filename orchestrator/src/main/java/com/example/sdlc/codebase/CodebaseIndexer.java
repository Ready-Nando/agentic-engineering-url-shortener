package com.example.sdlc.codebase;

import com.example.sdlc.codebase.JavaSource.Annotation;
import com.example.sdlc.codebase.JavaSource.Import;
import com.example.sdlc.codebase.JavaSource.TypeDecl;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Builds a {@link CodebaseModel} of a Maven module from {@code src/main/java}, {@code src/test/java} and
 * the Flyway migrations, using token-level heuristics rather than a compiler:
 *
 * <ul>
 *   <li>Dependencies are identifier tokens outside comments and literals that resolve to a project type
 *       via same-package types, single-type imports and on-demand imports of project packages (plus fully
 *       qualified references and static imports). A local variable or method that happens to share a
 *       project type's simple name is miscounted as a reference; unused imports are not counted.
 *   <li>Table access is read from SQL in string literals and text blocks, only for tables the migrations
 *       define. SQL assembled from constants or builders is invisible, and a non-SQL string that looks
 *       like SQL ({@code "failed to delete from links"}) counts.
 *   <li>Path literals are string literals and text blocks holding a single token that starts with one
 *       {@code /}, such as the URI template of a MockMvc request. Non-literal operands of a concatenation
 *       ({@code "/links/" + code}) and {@code %s}/{@code %d} format arguments stand for a path variable;
 *       a path starting with a constant ({@code API + "/links"}) is only seen from the literal on.
 *   <li>Main sources never resolve to test types, since they cannot see them when compiled.
 *   <li>Endpoints come from mapping annotations on API-layer main types only. Constants in paths are not
 *       resolved; the constant's source text stands in for it ({@code Paths.API + "/x"} gives
 *       {@code /Paths.API/x}).
 * </ul>
 *
 * Unreadable, oversized or malformed files are skipped rather than failing the whole index, and symbolic
 * links are not followed, so nothing outside the module is read.
 */
public final class CodebaseIndexer {

    private static final String MAIN_SOURCES = "src/main/java";
    private static final String TEST_SOURCES = "src/test/java";
    // Hand-written sources and migrations are far smaller; anything bigger is generated or not source at all.
    private static final long MAX_FILE_BYTES = 16L * 1024 * 1024;

    private static final Set<String> WEB_ANNOTATIONS =
            Set.of("RestController", "Controller", "RestControllerAdvice", "ControllerAdvice");
    private static final Set<String> CONFIG_ANNOTATIONS =
            Set.of("Configuration", "ConfigurationProperties", "SpringBootApplication", "AutoConfiguration");
    private static final Map<String, String> MAPPING_METHODS = Map.of(
            "GetMapping", "GET", "PostMapping", "POST", "PutMapping", "PUT",
            "DeleteMapping", "DELETE", "PatchMapping", "PATCH");
    private static final Pattern HTTP_METHOD = Pattern.compile("\\b(GET|HEAD|POST|PUT|PATCH|DELETE|OPTIONS|TRACE)\\b");

    private static final String SQL_NAME = "(\"?[A-Za-z_][\\w$]*\"?(?:\\s*\\.\\s*\"?[A-Za-z_][\\w$]*\"?)?)";
    private static final Pattern SQL_STATEMENT = Pattern.compile("(?i)\\b(select|insert|update|delete|merge|truncate)\\b");
    private static final Pattern SQL_WRITE =
            Pattern.compile("(?i)\\b(?:insert\\s+into|merge\\s+into|delete\\s+from|truncate(?:\\s+table)?)\\s+" + SQL_NAME);
    // SET is required so that prose such as "could not update links" is not taken for a statement.
    private static final Pattern SQL_UPDATE =
            Pattern.compile("(?i)\\bupdate\\s+" + SQL_NAME + "(?:\\s+(?:as\\s+)?[\\w$]+)?\\s+set\\b");
    // DELETE FROM is matched here too only so that its FROM is not reported as a read.
    private static final Pattern SQL_READ = Pattern.compile("(?i)\\b(?:(delete)\\s+)?(?:from|join)\\s+" + SQL_NAME);

    // One token starting with a single slash: whitespace means prose, and "//host/x" is a protocol-relative URL.
    private static final Pattern PATH_LITERAL = Pattern.compile("/(?!/)\\S*");
    private static final Pattern QUERY_OR_FRAGMENT = Pattern.compile("[?#]");
    // "/links/%s".formatted(code): the argument is a path variable as far as matching goes.
    private static final Pattern FORMAT_ARGUMENT = Pattern.compile("%[sd]");

    private record SourceFile(String path, boolean test, JavaSource source) {
    }

    private record Declaration(SourceFile file, TypeDecl type) {
    }

    public CodebaseModel index(Path moduleRoot) {
        Path root = moduleRoot.toAbsolutePath().normalize();
        List<Table> tables = SqlMigrations.read(root);
        Set<String> knownTables = tables.stream().map(Table::name).collect(Collectors.toSet());

        List<SourceFile> files = new ArrayList<>(parseSources(root, MAIN_SOURCES, false));
        files.addAll(parseSources(root, TEST_SOURCES, true));

        // The first declaration of a name wins; a duplicate (e.g. a test copy of a main type) is not indexed.
        // Sorted, so that enclosingProjectType can tell when no longer prefix can be a project type.
        NavigableMap<String, Declaration> declarations = new TreeMap<>();
        for (SourceFile file : files) {
            for (TypeDecl type : file.source().types()) {
                declarations.putIfAbsent(qualify(file.source().packageName(), type.name()), new Declaration(file, type));
            }
        }

        Map<String, List<String>> typesByPackage = declarations.entrySet().stream().collect(Collectors.groupingBy(
                e -> e.getValue().file().source().packageName(), Collectors.mapping(Map.Entry::getKey, Collectors.toList())));

        List<SourceUnit> units = new ArrayList<>();
        List<Endpoint> endpoints = new ArrayList<>();
        Map<SourceFile, Map<String, String>> scopes = new HashMap<>();
        declarations.forEach((fqn, declaration) -> {
            SourceFile file = declaration.file();
            TypeDecl type = declaration.type();
            JavaSource source = file.source();
            Map<String, String> scope = scopes.computeIfAbsent(file, f -> scope(f, declarations, typesByPackage));
            List<String> annotations = type.annotations().stream().map(Annotation::name).toList();
            Layer layer = layer(file.test(), source.packageName(), type.name(), type.kind(), annotations);

            Set<String> dependsOn = dependencies(file, type, scope, declarations);
            dependsOn.remove(fqn);
            List<String> literals = source.literalChains(type.regionStart(), type.end());
            Set<String> read = new TreeSet<>();
            Set<String> written = new TreeSet<>();
            collectTableAccess(literals, knownTables, read, written);

            units.add(new SourceUnit(fqn, type.name(), file.path(), layer, file.test(), dependsOn, read, written, annotations,
                    pathLiterals(literals, source.literalChains(type.regionStart(), type.end(), "{}"))));
            if (layer == Layer.API) {
                endpoints.addAll(endpoints(source, type, fqn));
            }
        });

        units.sort(Comparator.comparing(SourceUnit::typeName));
        return new CodebaseModel(units, endpoints.stream().distinct().sorted(Endpoint.ORDER).toList(), tables);
    }

    static String relativePath(Path root, Path file) {
        return root.relativize(file).toString().replace(File.separatorChar, '/');
    }

    /**
     * Regular files below {@code directory} whose name passes the filter, sorted. Symbolic links, files over
     * {@link #MAX_FILE_BYTES} and unreadable subdirectories are skipped; the walk never fails as a whole.
     */
    static List<Path> files(Path directory, Predicate<String> fileName) {
        List<Path> found = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return found;
        }
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    // Without FOLLOW_LINKS the attributes are the link's own, so a link is never a regular file.
                    if (attributes.isRegularFile() && attributes.size() <= MAX_FILE_BYTES
                            && fileName.test(file.getFileName().toString())) {
                        found.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException e) {
            // Keep what was found before the walk broke off.
        }
        found.sort(null);
        return found;
    }

    /** The file as UTF-8 with malformed bytes replaced, so odd encodings still index; empty if unreadable. */
    static Optional<String> read(Path file) {
        try {
            return Optional.of(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static List<SourceFile> parseSources(Path root, String sourceDirectory, boolean test) {
        List<SourceFile> files = new ArrayList<>();
        for (Path path : files(root.resolve(sourceDirectory), name -> name.endsWith(".java"))) {
            try {
                read(path).map(JavaSource::parse).ifPresent(source -> files.add(new SourceFile(relativePath(root, path), test, source)));
            } catch (RuntimeException e) {
                // Content the heuristics choke on: the rest of the module is still worth indexing.
            }
        }
        return files;
    }

    /**
     * Simple name to project type visible in a file. Later puts shadow earlier ones, following Java:
     * single-type imports beat same-package types, which beat on-demand imports.
     */
    private static Map<String, String> scope(SourceFile file, NavigableMap<String, Declaration> declarations,
                                             Map<String, List<String>> typesByPackage) {
        Map<String, String> scope = new HashMap<>();
        for (Import imported : file.source().imports()) {
            if (imported.wildcard() && !imported.isStatic()) {
                putPackageTypes(scope, imported.name(), file, declarations, typesByPackage);
            }
        }
        putPackageTypes(scope, file.source().packageName(), file, declarations, typesByPackage);
        for (Import imported : file.source().imports()) {
            if (!imported.wildcard() && !imported.isStatic()) {
                String simpleName = imported.name().substring(imported.name().lastIndexOf('.') + 1);
                // Importing a nested type (a.b.Outer.Inner) is a dependency on its top-level type. Importing a
                // library type (java.util.List) hides a project type of that simple name.
                enclosingProjectType(imported.name(), file, declarations).ifPresentOrElse(
                        fqn -> scope.put(simpleName, fqn), () -> scope.remove(simpleName));
            }
        }
        return scope;
    }

    private static void putPackageTypes(Map<String, String> scope, String packageName, SourceFile file,
                                        NavigableMap<String, Declaration> declarations, Map<String, List<String>> typesByPackage) {
        for (String fqn : typesByPackage.getOrDefault(packageName, List.of())) {
            if (visible(fqn, file, declarations)) {
                scope.put(declarations.get(fqn).type().name(), fqn);
            }
        }
    }

    private static Set<String> dependencies(SourceFile file, TypeDecl type, Map<String, String> scope,
                                            NavigableMap<String, Declaration> declarations) {
        JavaSource source = file.source();
        Set<String> dependsOn = new TreeSet<>();
        for (String identifier : source.identifiers(type.regionStart(), type.end())) {
            String fqn = scope.get(identifier);
            if (fqn != null) {
                dependsOn.add(fqn);
            }
        }
        for (String name : source.qualifiedNames(type.regionStart(), type.end())) {
            enclosingProjectType(name, file, declarations).ifPresent(dependsOn::add);
        }
        for (Import imported : source.imports()) {
            if (imported.isStatic()) {
                String owner = imported.wildcard()
                        ? imported.name()
                        : imported.name().substring(0, Math.max(0, imported.name().lastIndexOf('.')));
                enclosingProjectType(owner, file, declarations).ifPresent(dependsOn::add);
            }
        }
        return dependsOn;
    }

    /**
     * The shortest dotted prefix of {@code name} that is a project type visible from {@code file}. The search
     * stops once no declared name starts with the prefix, which keeps absurdly long dotted chains linear.
     */
    private static Optional<String> enclosingProjectType(String name, SourceFile file,
                                                         NavigableMap<String, Declaration> declarations) {
        int dot = name.indexOf('.');
        while (true) {
            String candidate = dot < 0 ? name : name.substring(0, dot);
            if (visible(candidate, file, declarations)) {
                return Optional.of(candidate);
            }
            String next = declarations.ceilingKey(candidate);
            if (dot < 0 || next == null || !next.startsWith(candidate)) {
                return Optional.empty();
            }
            dot = name.indexOf('.', dot + 1);
        }
    }

    private static boolean visible(String fqn, SourceFile from, Map<String, Declaration> declarations) {
        Declaration declaration = declarations.get(fqn);
        return declaration != null && (from.test() || !declaration.file().test());
    }

    private static void collectTableAccess(List<String> literals, Set<String> knownTables, Set<String> read, Set<String> written) {
        for (String sql : literals) {
            if (!SQL_STATEMENT.matcher(sql).find()) {
                continue;
            }
            addKnown(SQL_WRITE.matcher(sql), 1, knownTables, written);
            addKnown(SQL_UPDATE.matcher(sql), 1, knownTables, written);
            Matcher reads = SQL_READ.matcher(sql);
            while (reads.find()) {
                String table = SqlMigrations.normalizeName(reads.group(2));
                if (reads.group(1) == null && knownTables.contains(table)) {
                    read.add(table);
                }
            }
        }
    }

    /**
     * Literals that look like a URL path, cut at the query or fragment and normalised like endpoint paths.
     * {@code withOperands} has non-literal operands as {@code {}}, so {@code "/links/" + code} gives
     * {@code /links/{}}; the plain chains still contribute the {@code /links} of
     * {@code "http://localhost:" + port + "/links"}, whose chain with operands is not a path.
     */
    private static List<String> pathLiterals(List<String> literals, List<String> withOperands) {
        Set<String> paths = new TreeSet<>();
        for (List<String> chains : List.of(literals, withOperands)) {
            for (String literal : chains) {
                String text = FORMAT_ARGUMENT.matcher(literal.strip()).replaceAll("{}");
                if (PATH_LITERAL.matcher(text).matches()) {
                    paths.add(Endpoint.normalizePath(QUERY_OR_FRAGMENT.split(text, 2)[0]));
                }
            }
        }
        return List.copyOf(paths);
    }

    private static void addKnown(Matcher matcher, int group, Set<String> knownTables, Set<String> into) {
        while (matcher.find()) {
            String table = SqlMigrations.normalizeName(matcher.group(group));
            if (knownTables.contains(table)) {
                into.add(table);
            }
        }
    }

    /**
     * Beyond the documented stereotypes: {@code @SpringBootApplication} counts as configuration, controller
     * advice as API, and {@code @Component} as a service, since a Spring component is application wiring
     * and treating it as domain would make the domain-purity rule flag every component using a repository.
     */
    private static Layer layer(boolean test, String packageName, String simpleName, String kind, List<String> annotations) {
        if (test) {
            return Layer.TEST;
        }
        if (annotations.stream().anyMatch(WEB_ANNOTATIONS::contains) || List.of(packageName.split("\\.")).contains("api")) {
            return Layer.API;
        }
        if (annotations.contains("Repository") || simpleName.endsWith("Repository")) {
            return Layer.PERSISTENCE;
        }
        if (annotations.contains("Service")) {
            return Layer.SERVICE;
        }
        if (annotations.stream().anyMatch(CONFIG_ANNOTATIONS::contains)) {
            return Layer.CONFIG;
        }
        if (annotations.contains("Component")) {
            return Layer.SERVICE;
        }
        return switch (kind) {
            case "class", "record", "enum" -> Layer.DOMAIN;
            default -> Layer.OTHER;
        };
    }

    private static List<Endpoint> endpoints(JavaSource source, TypeDecl type, String fqn) {
        List<String> prefixes = type.annotations().stream()
                .filter(a -> a.name().equals("RequestMapping"))
                .findFirst()
                .map(a -> paths(source, a))
                .orElse(List.of(""));
        List<Endpoint> endpoints = new ArrayList<>();
        for (Annotation mapping : source.annotations(type.bodyStart() + 1, type.end(), 1)) {
            List<String> methods = mapping.name().equals("RequestMapping")
                    ? requestMethods(source, mapping)
                    : Optional.ofNullable(MAPPING_METHODS.get(mapping.name())).map(List::of).orElse(List.of());
            Optional<String> handler = methods.isEmpty() ? Optional.empty() : source.methodNameAfter(mapping.end());
            if (handler.isEmpty()) {
                continue;
            }
            for (String prefix : prefixes) {
                for (String path : paths(source, mapping)) {
                    for (String method : methods) {
                        endpoints.add(new Endpoint(method, Endpoint.normalizePath(prefix + "/" + path), fqn, handler.get()));
                    }
                }
            }
        }
        return endpoints;
    }

    private static List<String> paths(JavaSource source, Annotation mapping) {
        List<String> paths = source.attributes(mapping).stream()
                .filter(a -> a.name().equals("value") || a.name().equals("path"))
                .flatMap(a -> source.values(a.value()).stream())
                .toList();
        return paths.isEmpty() ? List.of("") : paths;
    }

    private static List<String> requestMethods(JavaSource source, Annotation mapping) {
        List<String> methods = source.attributes(mapping).stream()
                .filter(a -> a.name().equals("method"))
                .flatMap(a -> HTTP_METHOD.matcher(source.text(a.value())).results().map(r -> r.group(1)))
                .distinct()
                .toList();
        return methods.isEmpty() ? List.of("ANY") : methods;
    }

    private static String qualify(String packageName, String simpleName) {
        return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
    }
}
