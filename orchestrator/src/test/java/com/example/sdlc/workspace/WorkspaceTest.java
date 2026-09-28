package com.example.sdlc.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.condition.OS.WINDOWS;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.example.sdlc.Json;
import com.example.sdlc.policy.ChangeContext;
import com.example.sdlc.policy.ChangePolicy;
import com.example.sdlc.policy.PolicyDecision;
import com.example.sdlc.policy.PolicyVerdict;

class WorkspaceTest {

    @TempDir
    Path temp;

    private static final String APP = "src/main/java/demo/App.java";
    private static final String CONTROLLER = "src/main/java/demo/web/LinkController.java";

    private Workspace workspace;

    @BeforeEach
    void setUp() throws Exception {
        Path module = temp.resolve("module");
        Files.createDirectories(module.resolve("src/main/java/demo/web"));
        Files.createDirectories(module.resolve("src/main/java/demo/data"));
        Files.createDirectories(module.resolve("target/classes"));
        Files.createDirectories(module.resolve("data"));
        Files.writeString(module.resolve(APP), "class App {\n    int a;\n}\n");
        Files.writeString(module.resolve(CONTROLLER), "class LinkController {}\n");
        Files.writeString(module.resolve("src/main/java/demo/data/Record.java"), "record Record() {}\n");
        Files.writeString(module.resolve("target/classes/App.class"), "binary");
        Files.writeString(module.resolve("data/shortener.mv.db"), "database");
        Files.writeString(temp.resolve("mvnw"), "#!/bin/sh\n");
        workspace = Workspace.create(module, Map.of(temp.resolve("mvnw"), "mvnw"), temp.resolve("run"));
    }

    private static ChangeSet changes(FileChange... changes) {
        return new ChangeSet("test", null, List.of(changes), Map.of());
    }

    private static WorkspaceException.Reason reasonOf(Throwable e) {
        return ((WorkspaceException) e).reason();
    }

    @Test
    void copiesSourcesAndExtraFilesButNotBuildOutput() {
        assertThat(workspace.trackedFiles()).containsExactly("mvnw", APP, "src/main/java/demo/data/Record.java", CONTROLLER);
        assertThat(workspace.irregularEntries()).isEmpty();
        assertThat(workspace.contentHash()).isEqualTo(workspace.baselineHash());
    }

    @Test
    void toolDirectoriesAreIgnoredOnlyAtTheRoot() throws Exception {
        Files.writeString(workspace.root().resolve("src/main/java/demo/data/Record.java"), "record Record(int id) {}\n");
        Files.createDirectories(workspace.root().resolve("data"));
        Files.writeString(workspace.root().resolve("data/shortener.mv.db"), "changed database");

        assertThat(workspace.changedPaths()).containsExactly("src/main/java/demo/data/Record.java");
    }

    @Test
    void applyThenRollbackRestoresTheExactBaseline() {
        String baseline = workspace.contentHash();
        AppliedChangeSet applied = workspace.apply("cs-1", "impl", 1, changes(
                FileChange.edit("src/main/java/demo/App.java", "    int a;", "    int a;\n    int b;"),
                FileChange.create("src/main/java/demo/New.java", "class New {}\n")));

        assertThat(workspace.changedPaths()).containsExactly("src/main/java/demo/App.java", "src/main/java/demo/New.java");
        workspace.rollback(applied);

        assertThat(workspace.contentHash()).isEqualTo(baseline);
        assertThat(workspace.exists("src/main/java/demo/New.java")).isFalse();
    }

    @Test
    void rollbackRefusesToDestroyLaterModifications() {
        AppliedChangeSet first = workspace.apply("cs-1", "a", 1, changes(FileChange.edit("src/main/java/demo/App.java", "int a;", "int a1;")));
        workspace.apply("cs-2", "b", 1, changes(FileChange.edit("src/main/java/demo/App.java", "int a1;", "int a2;")));

        assertThatThrownBy(() -> workspace.rollback(first))
                .isInstanceOf(WorkspaceException.class)
                .satisfies(e -> assertThat(((WorkspaceException) e).reason()).isEqualTo(WorkspaceException.Reason.INTEGRITY));
        assertThat(workspace.read("src/main/java/demo/App.java")).hasValueSatisfying(s -> assertThat(s).contains("int a2;"));
    }

    @Test
    void editAnchorsMustMatchExactlyOnce() {
        assertThatThrownBy(() -> workspace.preview(changes(FileChange.edit("src/main/java/demo/App.java", "missing", "x"))))
                .hasMessageContaining("anchor not found");
        workspace.apply("cs-1", "a", 1, changes(FileChange.edit("src/main/java/demo/App.java", "int a;", "int a; int a;")));
        assertThatThrownBy(() -> workspace.preview(changes(FileChange.edit("src/main/java/demo/App.java", "int a;", "x"))))
                .hasMessageContaining("ambiguous");
    }

    @Test
    void proposalsBasedOnStaleContentAreRejectedAsConflicts() {
        String staleHash = Json.sha256("class App {\n    int a;\n}\n");
        workspace.apply("cs-1", "other", 1, changes(FileChange.edit("src/main/java/demo/App.java", "int a;", "int changed;")));
        ChangeSet stale = new ChangeSet("stale", null,
                List.of(FileChange.edit("src/main/java/demo/App.java", "class App {", "final class App {")),
                Map.of("src/main/java/demo/App.java", staleHash));

        assertThatThrownBy(() -> workspace.preview(stale))
                .isInstanceOf(WorkspaceException.class)
                .satisfies(e -> assertThat(((WorkspaceException) e).reason()).isEqualTo(WorkspaceException.Reason.CONFLICT));
    }

    @Test
    void pathsCannotEscapeTheWorkspace() {
        assertThatThrownBy(() -> workspace.preview(changes(FileChange.create("../outside.txt", "x"))))
                .hasMessageContaining("escapes");
        assertThatThrownBy(() -> workspace.preview(changes(FileChange.create("/etc/passwd", "x"))))
                .hasMessageContaining("escapes");
        assertThatThrownBy(() -> workspace.preview(changes(FileChange.create("src/../../outside.txt", "x"))))
                .hasMessageContaining("escapes");
    }

    @Test
    void workspacesOpenedThroughNonNormalisedPathsStillResolveTheirFiles() {
        Workspace reopened = Workspace.open(temp.resolve("run/../run/workspace"), temp.resolve("run/./baseline"));

        assertThat(reopened.read(APP)).hasValueSatisfying(s -> assertThat(s).contains("int a;"));
        assertThat(reopened.existsInBaseline(APP)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"src/main/java/demo/./App.java", "./mvnw", ".mvn//wrapper.properties", "src/main/java/demo/",
            "src\\main\\java\\demo\\New.java", "src/main/java/demo/New\u0000.java", "src/main/java/demo/New\n.java", ""})
    void onlyCanonicalRelativePathsAreAccepted(String path) {
        assertThatThrownBy(() -> workspace.preview(changes(FileChange.create(path, "x"))))
                .isInstanceOf(WorkspaceException.class)
                .satisfies(e -> assertThat(reasonOf(e)).isEqualTo(WorkspaceException.Reason.INVALID_CHANGE));
    }

    @Test
    void aFileCannotBeReachedUnderTwoSpellings() {
        ChangeSet twoSpellings = changes(
                FileChange.edit(APP, "int a;", "int b;"),
                FileChange.edit("src/main/java/demo/./App.java", "int a;", "int c;"));

        assertThatThrownBy(() -> workspace.apply("cs-1", "impl", 1, twoSpellings)).isInstanceOf(WorkspaceException.class);
        assertThat(workspace.read(APP)).hasValue("class App {\n    int a;\n}\n");
    }

    @Test
    void pathsDifferingFromExistingOnesOnlyInLetterCaseAreRejected() {
        for (String alias : List.of("Mvnw", "src/main/java/demo/app.java", "Src/main/java/demo/New.java", "src/main/java/Demo/New.java")) {
            assertThatThrownBy(() -> workspace.preview(changes(FileChange.create(alias, "x"))))
                    .as(alias)
                    .isInstanceOf(WorkspaceException.class)
                    .satisfies(e -> assertThat(reasonOf(e)).isEqualTo(WorkspaceException.Reason.INVALID_CHANGE));
        }
        assertThatThrownBy(() -> workspace.preview(changes(
                FileChange.create("src/main/java/demo/New.java", "x"), FileChange.create("src/main/java/demo/NEW.java", "y"))))
                .hasMessageContaining("letter case");
    }

    @Test
    void changesCannotTargetFilesTheWorkspaceDoesNotTrack() {
        for (String untracked : List.of("target/classes/Evil.class", "Target/Evil.java", "data/shortener.mv.db",
                "src/main/java/demo/.tmp-123.part", "src/.DS_Store")) {
            assertThatThrownBy(() -> workspace.preview(changes(FileChange.create(untracked, "x"))))
                    .as(untracked)
                    .isInstanceOf(WorkspaceException.class);
        }
    }

    @Test
    @DisabledOnOs(WINDOWS)
    void symbolicLinksCannotBeUsedToReachOutsideTheWorkspace() throws Exception {
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "top secret");
        Files.createSymbolicLink(workspace.root().resolve("src/escape"), outside);
        Files.createSymbolicLink(workspace.root().resolve("src/secret.txt"), outside.resolve("secret.txt"));

        assertThatThrownBy(() -> workspace.preview(changes(FileChange.create("src/escape/planted.txt", "x"))))
                .hasMessageContaining("symbolic link");
        assertThatThrownBy(() -> workspace.read("src/secret.txt")).hasMessageContaining("symbolic link");
        assertThat(outside.resolve("planted.txt")).doesNotExist();
        assertThat(workspace.irregularEntries()).containsExactly("src/escape", "src/secret.txt");
        assertThat(workspace.trackedFiles()).doesNotContain("src/secret.txt");
    }

    @Test
    @DisabledOnOs(WINDOWS)
    void symbolicLinksInsideTheWorkspaceCannotAliasProtectedPaths() throws Exception {
        Files.createDirectories(workspace.root().resolve(".mvn/wrapper"));
        Files.createSymbolicLink(workspace.root().resolve("src/tools"), workspace.root().resolve(".mvn"));

        assertThatThrownBy(() -> workspace.preview(changes(FileChange.create("src/tools/wrapper/maven-wrapper.properties", "x"))))
                .hasMessageContaining("alias");
        assertThat(workspace.irregularEntries()).containsExactly("src/tools");
    }

    @Test
    void deleteThenCreateOfAnExistingFileIsAnEdit() {
        List<FileDelta> deltas = workspace.preview(changes(
                FileChange.delete(CONTROLLER),
                FileChange.create(CONTROLLER, "class LinkController { /* no validation */ }\n")));

        assertThat(deltas).singleElement().satisfies(delta -> {
            assertThat(delta.op()).isEqualTo(FileChange.Op.EDIT);
            assertThat(delta.before()).isEqualTo("class LinkController {}\n");
        });
        PolicyVerdict verdict = new ChangePolicy().evaluate(new ChangeContext("impl", List.of("**"), deltas, null, null,
                workspace::existsInBaseline));
        assertThat(verdict.ruleIds(PolicyDecision.REQUIRE_APPROVAL)).contains("SEC-04");
    }

    @Test
    void mergedDeltasFollowTheNetEffect() {
        String created = "src/main/java/demo/New.java";

        assertThat(workspace.preview(changes(FileChange.create(created, "class New {}\n"), FileChange.delete(created)))).isEmpty();
        assertThat(workspace.preview(changes(FileChange.create(created, "class New {}\n"),
                FileChange.edit(created, "New {", "New { int x;"))))
                .singleElement().satisfies(delta -> assertThat(delta.op()).isEqualTo(FileChange.Op.CREATE));
        assertThat(workspace.preview(changes(FileChange.edit(APP, "int a;", "int b;"), FileChange.delete(APP))))
                .singleElement().satisfies(delta -> {
                    assertThat(delta.op()).isEqualTo(FileChange.Op.DELETE);
                    assertThat(delta.before()).contains("int a;");
                });
    }

    @Test
    void aFileCannotReplaceADirectoryOrSitBelowAFile() {
        String created = "src/main/java/demo/New.java";
        for (ChangeSet conflicting : List.of(
                changes(FileChange.create(created, "x"), FileChange.create("src/main/java/demo/web", "x")),
                changes(FileChange.create(created, "x"), FileChange.create(APP + "/Nested.java", "x")),
                changes(FileChange.create(created, "x"), FileChange.create(created + "/Nested.java", "x")),
                changes(FileChange.create("src/main/java/demo/pkg/A.java", "x"), FileChange.create("src/main/java/demo/pkg", "x")))) {
            assertThatThrownBy(() -> workspace.apply("cs-1", "impl", 1, conflicting))
                    .isInstanceOf(WorkspaceException.class)
                    .satisfies(e -> assertThat(reasonOf(e)).isEqualTo(WorkspaceException.Reason.INVALID_CHANGE));
        }
        assertThat(workspace.changedPaths()).isEmpty();
    }

    @Test
    void createRequiresANewFileAndDeleteAnExistingOne() {
        assertThatThrownBy(() -> workspace.preview(changes(FileChange.create("src/main/java/demo/App.java", "x"))))
                .hasMessageContaining("already exists");
        assertThatThrownBy(() -> workspace.preview(changes(FileChange.delete("src/main/java/demo/Nope.java"))))
                .hasMessageContaining("does not exist");
    }

    @Test
    void unifiedDiffCoversAddedModifiedAndDeletedFiles() {
        workspace.apply("cs-1", "a", 1, changes(
                FileChange.edit("src/main/java/demo/App.java", "int a;", "int b;"),
                FileChange.create("src/main/java/demo/New.java", "class New {}\n"),
                FileChange.delete("mvnw")));

        String diff = workspace.unifiedDiff("module/");

        assertThat(diff).contains("diff --git a/module/src/main/java/demo/App.java b/module/src/main/java/demo/App.java")
                .contains("-    int a;").contains("+    int b;")
                .contains("new file mode 100644").contains("--- /dev/null").contains("+++ b/module/src/main/java/demo/New.java")
                .contains("deleted file mode 100644");
    }

    // ---------------------------------------------------------------- byte-faithful diffs

    /** Before- and after-images (null: absent) whose differences String.lines() would hide or blur. */
    private static final Map<String, String[]> FAITHFUL_CASES = faithfulCases();

    private static Map<String, String[]> faithfulCases() {
        Map<String, String[]> cases = new LinkedHashMap<>();
        cases.put("crlf-to-lf.txt", new String[] {"a\r\nb\r\n", "a\nb\n"});
        cases.put("lf-to-crlf.txt", new String[] {"a\nb\n", "a\r\nb\r\n"});
        cases.put("newline-added.txt", new String[] {"a\nb", "a\nb\n"});
        cases.put("newline-removed.txt", new String[] {"a\nb\n", "a\nb"});
        cases.put("unterminated-context.txt", new String[] {"a\nb\nc", "A\nb\nc"});
        cases.put("far-from-the-end.txt", new String[] {"1\n2\n3\n4\n5\n6\n7\n8\n9", "1\nTWO\n3\n4\n5\n6\n7\n8\n9"});
        cases.put("created-unterminated.txt", new String[] {null, "x\ny"});
        cases.put("created-empty.txt", new String[] {null, ""});
        cases.put("deleted-unterminated.txt", new String[] {"a\nb", null});
        cases.put("deleted.txt", new String[] {"a\r\n", null});
        return cases;
    }

    private static List<FileDelta> faithfulDeltas() {
        return FAITHFUL_CASES.entrySet().stream().map(e -> new FileDelta(e.getValue()[0] == null ? FileChange.Op.CREATE
                : e.getValue()[1] == null ? FileChange.Op.DELETE : FileChange.Op.EDIT, e.getKey(), e.getValue()[0], e.getValue()[1])).toList();
    }

    @Test
    void approvalDiffReproducesTheExactAfterImageBytes() {
        String diff = Workspace.unifiedDiff(faithfulDeltas(), "");

        Map<String, String> before = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        FAITHFUL_CASES.forEach((path, images) -> {
            before.put(path, images[0]);
            expected.put(path, images[1]);
        });
        assertThat(applyExactly(diff, before)).isEqualTo(expected);
        assertThat(diff).contains("-a\r\n-b\r\n+a\n+b\n", "-b\n\\ No newline at end of file\n+b\n");
        assertThat(diff.substring(diff.indexOf("diff --git a/far-from-the-end.txt"), diff.indexOf("diff --git a/created-unterminated.txt")))
                .as("an unterminated last line outside every hunk is not mentioned").doesNotContain("No newline");
    }

    @Test
    void outcomeDiffOfTheWorkspaceIsByteFaithfulToo() throws Exception {
        Files.writeString(workspace.root().resolve(APP), "class App {\r\n    int a;\r\n}");
        Files.writeString(workspace.root().resolve(CONTROLLER), "class LinkController {}");

        String diff = workspace.unifiedDiff("");

        Map<String, String> before = new LinkedHashMap<>();
        before.put(APP, workspace.readBaseline(APP).orElseThrow());
        before.put(CONTROLLER, workspace.readBaseline(CONTROLLER).orElseThrow());
        assertThat(applyExactly(diff, before)).isEqualTo(Map.of(APP, workspace.read(APP).orElseThrow(),
                CONTROLLER, workspace.read(CONTROLLER).orElseThrow()));
    }

    @Test
    void gitAppliesTheApprovalDiffToTheExactAfterImageBytes() throws Exception {
        Path repo = Files.createDirectories(temp.resolve("git-apply"));
        for (Map.Entry<String, String[]> file : FAITHFUL_CASES.entrySet()) {
            if (file.getValue()[0] != null) {
                Files.writeString(repo.resolve(file.getKey()), file.getValue()[0]);
            }
        }
        Path patch = temp.resolve("approval.patch");
        Files.writeString(patch, Workspace.unifiedDiff(faithfulDeltas(), ""));
        Process git;
        try {
            git = new ProcessBuilder("git", "-c", "core.autocrlf=false", "-c", "core.safecrlf=false", "apply",
                    "--whitespace=nowarn", patch.toString()).directory(repo.toFile()).redirectErrorStream(true).start();
        } catch (java.io.IOException e) {
            assumeTrue(false, "git is not available");
            return;
        }
        String output = new String(git.getInputStream().readAllBytes());
        assertThat(git.waitFor()).as(output).isZero();

        for (Map.Entry<String, String[]> file : FAITHFUL_CASES.entrySet()) {
            Path applied = repo.resolve(file.getKey());
            if (file.getValue()[1] == null) {
                assertThat(applied).as(file.getKey()).doesNotExist();
            } else {
                assertThat(Files.readString(applied)).as(file.getKey()).isEqualTo(file.getValue()[1]);
            }
        }
    }

    private static final Pattern HUNK = Pattern.compile("@@ -(\\d+)(?:,(\\d+))? \\+\\d+(?:,\\d+)? @@.*");

    /**
     * Applies each file section of a unified diff to its before-image (null: absent), byte for byte: lines are
     * split on '\n' only and "\ No newline at end of file" drops the preceding line's terminator.
     */
    private static Map<String, String> applyExactly(String diff, Map<String, String> before) {
        List<String> lines = new ArrayList<>(List.of(diff.split("\n", -1)));
        assertThat(lines.removeLast()).as("diff ends with a newline").isEmpty();
        Map<String, String> after = new LinkedHashMap<>();
        int i = 0;
        while (i < lines.size()) {
            String header = lines.get(i++);
            assertThat(header).startsWith("diff --git ");
            String path = header.substring(header.indexOf(" b/") + 3);
            boolean deleted = false;
            while (i < lines.size() && !lines.get(i).startsWith("@@") && !lines.get(i).startsWith("diff --git ")) {
                deleted |= lines.get(i++).startsWith("deleted file mode");
            }
            List<String> source = terminatedLines(before.get(path));
            List<String> result = new ArrayList<>();
            int pos = 0;
            while (i < lines.size() && lines.get(i).startsWith("@@")) {
                Matcher hunk = HUNK.matcher(lines.get(i++));
                assertThat(hunk.matches()).isTrue();
                int oldStart = Integer.parseInt(hunk.group(1));
                int start = source.isEmpty() ? 0 : oldStart - 1;
                result.addAll(source.subList(pos, start));
                pos = start;
                while (i < lines.size() && !lines.get(i).startsWith("@@") && !lines.get(i).startsWith("diff --git ")) {
                    String line = lines.get(i++);
                    boolean unterminated = i < lines.size() && lines.get(i).equals("\\ No newline at end of file");
                    if (unterminated) {
                        i++;
                    }
                    String text = line.substring(1) + (unterminated ? "" : "\n");
                    switch (line.charAt(0)) {
                        case ' ' -> {
                            assertThat(source.get(pos++)).isEqualTo(text);
                            result.add(text);
                        }
                        case '-' -> assertThat(source.get(pos++)).isEqualTo(text);
                        case '+' -> result.add(text);
                        default -> throw new AssertionError("unexpected diff line in " + path + ": " + line);
                    }
                }
            }
            result.addAll(source.subList(pos, source.size()));
            if (deleted) {
                assertThat(result).as(path + " fully removed").isEmpty();
            }
            after.put(path, deleted ? null : String.join("", result));
        }
        return after;
    }

    /** Lines with their terminators, so joining them gives back the exact content. */
    private static List<String> terminatedLines(String content) {
        List<String> lines = new ArrayList<>();
        if (content == null) {
            return lines;
        }
        int start = 0;
        for (int end = content.indexOf('\n'); end >= 0; end = content.indexOf('\n', start)) {
            lines.add(content.substring(start, end + 1));
            start = end + 1;
        }
        if (start < content.length()) {
            lines.add(content.substring(start));
        }
        return lines;
    }
}
