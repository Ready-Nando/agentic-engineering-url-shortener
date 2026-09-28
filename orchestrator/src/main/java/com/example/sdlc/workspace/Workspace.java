package com.example.sdlc.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.example.sdlc.Json;
import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;

/**
 * An isolated copy of the target codebase that agents may change, plus a pristine baseline copy used for
 * diffs and integrity checks. The real repository is never modified: the reviewable outcome is a patch.
 *
 * <p>Writes happen only on the engine's coordinator thread; handlers may read concurrently, which is safe
 * because every write replaces the file atomically.
 */
public final class Workspace {

    /** Build output and tool state; ignored only directly under the root, where build tools put them. */
    private static final Set<String> IGNORED_DIRECTORIES = Set.of("target", "data", ".idea", ".git", "node_modules");
    private static final Set<String> IGNORED_FILES = Set.of(".DS_Store");
    private static final String TEMP_PREFIX = ".tmp-";

    private final Path root;
    private final Path baseline;

    private Workspace(Path root, Path baseline) {
        // Normalised so the lexical containment check in resolve() holds for bases like "runs/../runs/x".
        this.root = root.toAbsolutePath().normalize();
        this.baseline = baseline.toAbsolutePath().normalize();
    }

    public static Workspace open(Path root, Path baseline) {
        return new Workspace(root, baseline);
    }

    /**
     * Copies {@code module} (plus extra files such as the Maven wrapper, given as source path to relative
     * destination) into {@code runDirectory/workspace} and {@code runDirectory/baseline}.
     */
    public static Workspace create(Path module, Map<Path, String> extraFiles, Path runDirectory) {
        Path root = runDirectory.resolve("workspace");
        Path baseline = runDirectory.resolve("baseline");
        for (Path destination : List.of(root, baseline)) {
            copyTree(module, destination);
            extraFiles.forEach((source, relative) -> copyFile(source, destination.resolve(relative)));
        }
        return new Workspace(root, baseline);
    }

    public Path root() {
        return root;
    }

    public Path baselineRoot() {
        return baseline;
    }

    public boolean exists(String path) {
        return Files.isRegularFile(resolve(root, path));
    }

    public boolean existsInBaseline(String path) {
        return Files.isRegularFile(resolve(baseline, path));
    }

    public Optional<String> read(String path) {
        return readFile(resolve(root, path));
    }

    public Optional<String> readBaseline(String path) {
        return readFile(resolve(baseline, path));
    }

    public Optional<String> hash(String path) {
        return read(path).map(Json::sha256);
    }

    /**
     * Resolves a change set against the current content without writing anything. Several changes to one file
     * merge into a single delta whose operation follows from its pre- and post-image, so policy always sees
     * the net effect (a delete followed by a create of an existing file is an edit).
     */
    public List<FileDelta> preview(ChangeSet changeSet) {
        Map<String, String> pending = new LinkedHashMap<>();
        Map<String, FileDelta> deltas = new LinkedHashMap<>();
        CaseIndex known = new CaseIndex(trackedFiles());
        for (FileChange change : changeSet.changes()) {
            String path = normalise(change.path());
            requireTrackable(path);
            known.requireSameSpelling(path);
            requireFileSlot(path, pending);
            String current = pending.containsKey(path) ? pending.get(path) : read(path).orElse(null);
            String expectedBase = changeSet.baseHashes().get(path);
            if (expectedBase != null && !pending.containsKey(path) && (current == null || !expectedBase.equals(Json.sha256(current)))) {
                throw new WorkspaceException(WorkspaceException.Reason.CONFLICT,
                        path + " changed after the proposal was made (optimistic concurrency check failed)");
            }
            String after = switch (change.op()) {
                case CREATE -> {
                    if (current != null) {
                        throw invalid("cannot create " + path + ": file already exists (use an edit)");
                    }
                    yield change.content() == null ? "" : change.content();
                }
                case EDIT -> applyEdit(path, current, change);
                case DELETE -> {
                    if (current == null) {
                        throw invalid("cannot delete " + path + ": file does not exist");
                    }
                    yield null;
                }
            };
            FileDelta earlier = deltas.get(path);
            String before = earlier != null ? earlier.before() : current;
            if (before == null && after == null) {
                deltas.remove(path);
            } else {
                deltas.put(path, new FileDelta(opFor(before, after), path, before, after));
            }
            pending.put(path, after);
            known.add(path);
        }
        return List.copyOf(deltas.values());
    }

    private static FileChange.Op opFor(String before, String after) {
        return before == null ? FileChange.Op.CREATE : after == null ? FileChange.Op.DELETE : FileChange.Op.EDIT;
    }

    /**
     * A file cannot replace a directory or sit below another file, on disk or earlier in the same change set.
     * Caught here, such a change is an invalid proposal; caught by {@link #apply}, it would fail half-way through
     * with an I/O error and leave the files written before it in place.
     */
    private void requireFileSlot(String path, Map<String, String> pending) {
        Path file = resolve(root, path);
        if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
            throw invalid("cannot change " + path + ": it is a directory");
        }
        for (Path parent = file.getParent(); !parent.equals(root); parent = parent.getParent()) {
            if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                throw invalid("cannot change " + path + ": " + slashSeparated(root.relativize(parent)) + " is a file");
            }
        }
        pending.forEach((other, content) -> {
            if (content != null && (path.startsWith(other + "/") || other.startsWith(path + "/"))) {
                throw invalid("cannot change both " + other + " and " + path + ": one of them would have to be a directory");
            }
        });
    }

    public AppliedChangeSet apply(String id, String taskId, int attempt, ChangeSet changeSet) {
        List<FileDelta> deltas = preview(changeSet);
        List<AppliedChangeSet.AppliedFile> applied = new ArrayList<>();
        for (FileDelta delta : deltas) {
            Path file = resolve(root, delta.path());
            if (delta.after() == null) {
                deleteFile(file);
            } else {
                writeAtomically(file, delta.after());
            }
            applied.add(new AppliedChangeSet.AppliedFile(delta.path(), delta.op(), delta.before(),
                    delta.after() == null ? null : Json.sha256(delta.after())));
        }
        return new AppliedChangeSet(id, taskId, attempt, changeSet.summary(), applied);
    }

    /**
     * Restores every file touched by {@code applied} to its pre-image. Refuses (without touching anything) if a
     * file no longer holds what was written, because restoring would then destroy someone else's change.
     */
    public void rollback(AppliedChangeSet applied) {
        for (AppliedChangeSet.AppliedFile file : applied.files()) {
            String currentHash = hash(file.path()).orElse(null);
            boolean matches = file.postHash() == null ? currentHash == null : file.postHash().equals(currentHash);
            if (!matches) {
                throw new WorkspaceException(WorkspaceException.Reason.INTEGRITY,
                        "cannot roll back " + applied.id() + ": " + file.path() + " was modified after it was applied");
            }
        }
        for (AppliedChangeSet.AppliedFile file : applied.files().reversed()) {
            Path target = resolve(root, file.path());
            if (file.preImage() == null) {
                deleteFile(target);
            } else {
                writeAtomically(target, file.preImage());
            }
        }
    }

    /** Hash over all tracked files (build output excluded); equal hashes mean identical source trees. */
    public String contentHash() {
        return treeHash(root);
    }

    public String baselineHash() {
        return treeHash(baseline);
    }

    /** Relative paths whose content differs from the baseline (added, modified or deleted). */
    public List<String> changedPaths() {
        Map<String, String> current = fileHashes(root);
        Map<String, String> original = fileHashes(baseline);
        Set<String> paths = new TreeSet<>(current.keySet());
        paths.addAll(original.keySet());
        return paths.stream().filter(p -> !java.util.Objects.equals(current.get(p), original.get(p))).toList();
    }

    /** Unified diff of the workspace against the baseline, with paths prefixed so it applies at the repo root. */
    public String unifiedDiff(String pathPrefix) {
        StringBuilder diff = new StringBuilder();
        for (String path : changedPaths()) {
            List<String> before = readBaseline(path).map(Workspace::lines).orElse(List.of());
            List<String> after = read(path).map(Workspace::lines).orElse(List.of());
            String from = readBaseline(path).isPresent() ? "a/" + pathPrefix + path : "/dev/null";
            String to = read(path).isPresent() ? "b/" + pathPrefix + path : "/dev/null";
            diff.append("diff --git a/").append(pathPrefix).append(path).append(" b/").append(pathPrefix).append(path).append('\n');
            if (readBaseline(path).isEmpty()) {
                diff.append("new file mode 100644\n");
            } else if (read(path).isEmpty()) {
                diff.append("deleted file mode 100644\n");
            }
            List<String> unified = UnifiedDiffUtils.generateUnifiedDiff(from, to, before, DiffUtils.diff(before, after), 3);
            unified.forEach(line -> diff.append(line).append('\n'));
        }
        return diff.toString();
    }

    private static List<String> lines(String content) {
        return content.lines().toList();
    }

    private String applyEdit(String path, String current, FileChange change) {
        if (current == null) {
            throw invalid("cannot edit " + path + ": file does not exist");
        }
        if (change.find() == null || change.find().isEmpty()) {
            throw invalid("edit of " + path + " has an empty 'find' anchor");
        }
        int first = current.indexOf(change.find());
        if (first < 0) {
            throw invalid("edit anchor not found in " + path + ": \"" + abbreviate(change.find()) + "\"");
        }
        if (current.indexOf(change.find(), first + 1) >= 0) {
            throw invalid("edit anchor is ambiguous in " + path + " (matches more than once)");
        }
        String replacement = change.replace() == null ? "" : change.replace();
        return current.substring(0, first) + replacement + current.substring(first + change.find().length());
    }

    private static WorkspaceException invalid(String message) {
        return new WorkspaceException(WorkspaceException.Reason.INVALID_CHANGE, message);
    }

    private static String abbreviate(String text) {
        String firstLine = text.strip().lines().findFirst().orElse("");
        return firstLine.length() > 80 ? firstLine.substring(0, 77) + "..." : firstLine;
    }

    /**
     * Accepts only canonical relative paths with '/' separators. Policy decides on path strings, so every file
     * must have exactly one accepted spelling: "a/./B.java" or ".mvn//x" would otherwise evade path rules or
     * turn one file into two deltas.
     */
    private static String normalise(String path) {
        if (path == null || path.isEmpty()) {
            throw invalid("empty path");
        }
        if (path.chars().anyMatch(Character::isISOControl)) {
            throw invalid("path contains control characters: " + path.replaceAll("\\p{Cntrl}", "?"));
        }
        if (path.startsWith("/")) {
            throw invalid("path escapes the workspace: " + path);
        }
        if (path.indexOf('\\') >= 0) {
            throw invalid("path must use '/' separators: " + path);
        }
        for (String segment : path.split("/", -1)) {
            if (segment.equals("..")) {
                throw invalid("path escapes the workspace: " + path);
            }
            if (segment.isEmpty() || segment.equals(".")) {
                throw invalid("path is not canonical (empty or '.' segment): " + path);
            }
        }
        Path parsed;
        try {
            parsed = Path.of(path);
        } catch (InvalidPathException e) {
            throw invalid("invalid path: " + path + " (" + e.getReason() + ")");
        }
        if (parsed.isAbsolute() || parsed.getRoot() != null) {
            throw invalid("path escapes the workspace: " + path);
        }
        if (!slashSeparated(parsed.normalize()).equals(path)) {
            throw invalid("path is not canonical: " + path);
        }
        return path;
    }

    private static String slashSeparated(Path path) {
        List<String> names = new ArrayList<>();
        path.forEach(name -> names.add(name.toString()));
        return String.join("/", names);
    }

    /** Changes may only target files that {@link #trackedFiles()} sees; anything else would be invisible to diffs and hashes. */
    private static void requireTrackable(String path) {
        int slash = path.indexOf('/');
        if (slash > 0 && IGNORED_DIRECTORIES.contains(path.substring(0, slash).toLowerCase(Locale.ROOT))) {
            throw invalid("cannot change " + path + ": " + path.substring(0, slash) + "/ holds build output or tool state, not sources");
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (IGNORED_FILES.contains(name) || name.startsWith(TEMP_PREFIX)) {
            throw invalid("cannot change " + path + ": reserved file name");
        }
    }

    private static Path resolve(Path base, String relative) {
        Path resolved = base.resolve(normalise(relative)).normalize();
        if (!resolved.startsWith(base)) {
            throw invalid("path escapes the workspace: " + relative);
        }
        requireNoAlias(base, resolved, relative);
        return resolved;
    }

    /**
     * Lexical containment is not enough: a symbolic link inside the tree may point anywhere, and on a
     * case-insensitive file system "Mvnw" is "mvnw". The nearest existing entry on the path must therefore
     * be exactly where the path says, so writes cannot leave the tree and no file is reachable under two names.
     */
    private static void requireNoAlias(Path base, Path resolved, String relative) {
        Path existing = resolved;
        while (!existing.equals(base) && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing.equals(base)) {
            return;
        }
        Path real;
        Path realBase;
        try {
            real = existing.toRealPath();
            realBase = base.toRealPath();
        } catch (IOException e) {
            throw invalid("path cannot be resolved inside the workspace (dangling symbolic link?): " + relative);
        }
        if (!real.startsWith(realBase)) {
            throw invalid("path escapes the workspace through a symbolic link: " + relative);
        }
        if (!real.equals(realBase.resolve(base.relativize(existing)))) {
            throw invalid("path is an alias (symbolic link or letter case) of " + slashSeparated(realBase.relativize(real))
                    + ": " + relative);
        }
    }

    private static Optional<String> readFile(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeAtomically(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), TEMP_PREFIX, ".part");
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteFile(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String treeHash(Path base) {
        StringBuilder combined = new StringBuilder();
        fileHashes(base).forEach((path, hash) -> combined.append(path).append('=').append(hash).append('\n'));
        return Json.sha256(combined.toString());
    }

    private static Map<String, String> fileHashes(Path base) {
        Map<String, String> hashes = new TreeMap<>();
        for (String path : trackedFiles(base)) {
            readFile(base.resolve(path)).ifPresent(content -> hashes.put(path, Json.sha256(content)));
        }
        return hashes;
    }

    /** Tracked files of the workspace (relative paths, sorted); build output and tool directories excluded. */
    public List<String> trackedFiles() {
        return trackedFiles(root);
    }

    /**
     * Entries of the workspace that are neither tracked files nor directories: symbolic links, special files and
     * entries that could not be read (relative paths, sorted). Their content is invisible to diffs and hashes,
     * so a non-empty result means the workspace can no longer be vouched for.
     */
    public List<String> irregularEntries() {
        return scan(root).irregular();
    }

    private static List<String> trackedFiles(Path base) {
        return scan(base).files();
    }

    private record Scan(List<String> files, List<String> irregular) {
    }

    /**
     * Walks the tree without entering ignored directories and without following links. Verification builds
     * rewrite {@code target/} concurrently with reviews, so those directories must be skipped, not filtered
     * after the fact.
     */
    private static Scan scan(Path base) {
        List<String> files = new ArrayList<>();
        List<String> irregular = new ArrayList<>();
        try {
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return isIgnoredDirectory(base, dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    if (!attrs.isRegularFile()) {
                        irregular.add(relative(base, file));
                    } else if (!IGNORED_FILES.contains(name) && !name.startsWith(TEMP_PREFIX)) {
                        files.add(relative(base, file));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    // A file that vanished mid-walk (an atomic write's temp file) is fine; an unreadable one is not.
                    if (!(e instanceof NoSuchFileException)) {
                        irregular.add(relative(base, file));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        files.sort(null);
        irregular.sort(null);
        return new Scan(files, irregular);
    }

    private static boolean isIgnoredDirectory(Path base, Path dir) {
        return base.equals(dir.getParent()) && IGNORED_DIRECTORIES.contains(dir.getFileName().toString());
    }

    private static String relative(Path base, Path file) {
        return base.relativize(file).toString().replace('\\', '/');
    }

    private static void copyTree(Path source, Path destination) {
        try {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (isIgnoredDirectory(source, dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Files.createDirectories(destination.resolve(source.relativize(dir).toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!IGNORED_FILES.contains(file.getFileName().toString())) {
                        // Links are copied as links, never followed: their targets are not part of the module.
                        Files.copy(file, destination.resolve(source.relativize(file).toString()),
                                StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void copyFile(Path source, Path destination) {
        try {
            Files.createDirectories(destination.getParent());
            Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Known paths and their parent directories, to reject a path that differs from a known one only in letter
     * case: on a case-insensitive file system both name the same file, but policy would see two paths.
     */
    private static final class CaseIndex {

        private final Set<String> exact = new HashSet<>();
        private final Map<String, String> byLowerCase = new HashMap<>();

        CaseIndex(List<String> files) {
            files.forEach(this::add);
        }

        void add(String path) {
            for (String prefix = path; prefix != null; prefix = parent(prefix)) {
                exact.add(prefix);
                byLowerCase.putIfAbsent(prefix.toLowerCase(Locale.ROOT), prefix);
            }
        }

        void requireSameSpelling(String path) {
            for (String prefix = path; prefix != null; prefix = parent(prefix)) {
                String known = byLowerCase.get(prefix.toLowerCase(Locale.ROOT));
                if (known != null && !exact.contains(prefix)) {
                    throw invalid(path + " differs only in letter case from existing " + known);
                }
            }
        }

        private static String parent(String path) {
            int slash = path.lastIndexOf('/');
            return slash < 0 ? null : path.substring(0, slash);
        }
    }
}
