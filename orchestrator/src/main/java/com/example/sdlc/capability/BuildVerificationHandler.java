package com.example.sdlc.capability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.sdlc.artifact.Artifact;
import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.engine.FailureKind;
import com.example.sdlc.engine.OutputArtifact;
import com.example.sdlc.engine.TaskContext;
import com.example.sdlc.engine.TaskFailure;
import com.example.sdlc.engine.TaskHandler;
import com.example.sdlc.engine.TaskResult;
import com.example.sdlc.reasoning.RequirementSpec;
import com.example.sdlc.verify.BuildOutcome;
import com.example.sdlc.verify.BuildReport;
import com.example.sdlc.verify.FailedTest;
import com.example.sdlc.verify.MavenBuildVerifier;

/**
 * Runs the target project's real test suite in the workspace. Infrastructure problems are raised as
 * TRANSIENT failures (retry, then fallback); code problems are reported so the exit gates can send the
 * responsible upstream work back for rework, with the suspects derived from failing tests and compiler output.
 */
final class BuildVerificationHandler implements TaskHandler {

    private static final String RESOURCES = "src/main/resources/";
    private static final Pattern COMPILER_PATH = Pattern.compile("(src/(?:main|test)/java/[\\w/]+\\.java)");
    private static final Pattern IDENTIFIER = Pattern.compile("\\b[A-Z][A-Za-z0-9_]*\\b");
    /** camelCase and snake_case words: fields, methods, types and columns quoted in assertion messages. */
    private static final Pattern SYMBOL = Pattern.compile("\\b[a-zA-Z][a-z0-9]+[A-Z][A-Za-z0-9]*\\b|\\b[a-z][a-z0-9]*(?:_[a-z0-9]+)+\\b");

    private final MavenBuildVerifier verifier;
    private final boolean baseline;

    BuildVerificationHandler(MavenBuildVerifier verifier, boolean baseline) {
        this.verifier = verifier;
        this.baseline = baseline;
    }

    @Override
    public TaskResult execute(TaskContext context) {
        List<Artifact> changes = context.readAll(ArtifactKeys.CHANGES_PREFIX);
        BuildReport report = verifier.verify(context.workspace().root());
        if (report.outcome() == BuildOutcome.NOT_RUN || report.outcome() == BuildOutcome.INFRASTRUCTURE_ERROR
                || report.outcome() == BuildOutcome.TIMED_OUT) {
            // A build that never started will not start on retry; a failed or slow one might succeed.
            FailureKind kind = report.outcome() == BuildOutcome.NOT_RUN ? FailureKind.UNAVAILABLE : FailureKind.TRANSIENT;
            throw new TaskFailure(kind, "build could not run: " + report.outcome(),
                    report.outputTail().lines().skip(Math.max(0, report.outputTail().lines().count() - 8)).toList());
        }
        List<VerificationReport.Coverage> coverage = baseline ? List.of()
                : context.find(ArtifactKeys.REQUIREMENT_SPEC, RequirementSpec.class).map(spec -> coverage(context, spec, report)).orElse(List.of());
        List<String> suspects = baseline ? List.of() : suspects(context, report, WorkspaceFacts.ownersByPath(changes));
        VerificationReport content = new VerificationReport(VerificationReport.BUILD, false, report.outcome().name(), report.command(),
                report.testsRun(), report.failures(), report.errors(), report.skipped(), report.duration().toMillis(),
                report.failedTests(), report.passedTestClasses(), coverage, suspects, List.of(),
                report.outcome() == BuildOutcome.PASSED ? "" : report.outputTail(), context.workspace().contentHash(), "");
        String key = baseline ? ArtifactKeys.BASELINE_VERIFICATION : ArtifactKeys.VERIFICATION_REPORT;
        return TaskResult.of(report.outcome() + ": " + report.testsRun() + " tests, " + report.failures() + " failures, "
                + report.errors() + " errors in " + report.duration().toSeconds() + "s", key, OutputArtifact.of("verification", content));
    }

    private static List<VerificationReport.Coverage> coverage(TaskContext context, RequirementSpec spec, BuildReport report) {
        Map<String, List<String>> criteriaByTest = WorkspaceFacts.criteriaByTestType(context);
        List<VerificationReport.Coverage> coverage = new ArrayList<>();
        for (RequirementSpec.AcceptanceCriterion criterion : spec.acceptanceCriteria()) {
            List<String> tests = criteriaByTest.entrySet().stream()
                    .filter(e -> e.getValue().contains(criterion.id()))
                    .map(Map.Entry::getKey)
                    .toList();
            List<String> passing = tests.stream().filter(test -> passed(test, report)).toList();
            coverage.add(new VerificationReport.Coverage(criterion.id(), tests, passing, !passing.isEmpty()));
        }
        return coverage;
    }

    private static boolean passed(String testType, BuildReport report) {
        boolean anyPassed = report.passedTestClasses().stream().anyMatch(c -> c.equals(testType) || c.startsWith(testType + "$"));
        boolean anyFailed = report.failedTests().stream().anyMatch(f -> f.className().equals(testType) || f.className().startsWith(testType + "$"));
        return anyPassed && !anyFailed;
    }

    /**
     * Which change sets probably caused the failure, most specific evidence first: files named by compiler
     * errors; else, per failing test, the changed resources its source loads (e.g. {@code static/openapi.yaml})
     * together with that test's code evidence: symbols quoted in its failure message that a change set
     * introduced into production code (e.g. {@code $.uniqueVisitors}), else production types its source
     * references. An empty result means "unknown", and the engine reworks every verified task.
     */
    private static List<String> suspects(TaskContext context, BuildReport report, Map<String, String> owners) {
        Set<String> fromCompiler = new LinkedHashSet<>();
        Matcher compilerPaths = COMPILER_PATH.matcher(report.outputTail());
        while (compilerPaths.find()) {
            String owner = owners.get(compilerPaths.group(1));
            if (owner != null) {
                fromCompiler.add(owner);
            }
        }
        if (!fromCompiler.isEmpty()) {
            return List.copyOf(fromCompiler);
        }
        Map<String, String> ownerBySimpleName = new LinkedHashMap<>();
        owners.forEach((path, owner) -> {
            if (path.startsWith("src/main/java/")) {
                ownerBySimpleName.put(WorkspaceFacts.simpleNameOf(path), owner);
            }
        });
        // A contract test failing on an edited OpenAPI document implicates the edit, but the same failure also
        // arises when an implementation adds an undocumented field: the resource adds a suspect, it never
        // explains the test's own code evidence away, or the implementation would never be sent back.
        Set<String> suspects = new LinkedHashSet<>();
        for (FailedTest failed : report.failedTests()) {
            String source = testSource(context, failed);
            List<String> literals = stringLiterals(source);
            owners.forEach((path, owner) -> {
                if (isResource(path) && loads(literals, path.substring(RESOURCES.length()))) {
                    suspects.add(owner);
                }
            });
            suspects.addAll(fromCodeEvidence(context, failed, source, owners, ownerBySimpleName));
        }
        return List.copyOf(suspects);
    }

    /** Symbols the test's failure message quotes that a change set introduced, else the types its source references. */
    private static Set<String> fromCodeEvidence(TaskContext context, FailedTest failed, String source, Map<String, String> owners,
            Map<String, String> ownerBySimpleName) {
        Set<String> fromMessage = new LinkedHashSet<>();
        SYMBOL.matcher(failed.message() == null ? "" : failed.message()).results().map(MatchResult::group).distinct()
                .forEach(symbol -> owners.forEach((path, owner) -> {
                    if (isCode(path) && introduces(context, path, symbol)) {
                        fromMessage.add(owner);
                    }
                }));
        if (!fromMessage.isEmpty()) {
            return fromMessage;
        }
        Set<String> fromSource = new LinkedHashSet<>();
        IDENTIFIER.matcher(source).results().map(MatchResult::group).distinct()
                .map(ownerBySimpleName::get).filter(Objects::nonNull).forEach(fromSource::add);
        return fromSource;
    }

    /**
     * The string literals and text blocks of a Java source, raw; comments and character literals are skipped, not
     * searched. Scanned by hand: a regex over literal contents recurses once per character and overflows a worker
     * thread's stack on a long fixture literal (a base64 or JSON blob), which would fail the verification FATAL.
     */
    private static List<String> stringLiterals(String source) {
        List<String> literals = new ArrayList<>();
        int i = 0;
        while (i < source.length()) {
            if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                i = end < 0 ? source.length() : end + 1;
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? source.length() : end + 2;
            } else if (source.startsWith("\"\"\"", i)) {
                int end = closing(source, i + 3, "\"\"\"");
                if (end >= 0) {
                    literals.add(source.substring(i + 3, end));
                }
                i = end < 0 ? source.length() : end + 3;
            } else if (source.charAt(i) == '"' || source.charAt(i) == '\'') {
                String quote = String.valueOf(source.charAt(i));
                int end = closing(source, i + 1, quote);
                if (end >= 0 && quote.equals("\"")) {
                    literals.add(source.substring(i + 1, end));
                }
                // An unterminated literal ends at its line, as it does for the compiler.
                i = end >= 0 ? end + 1 : lineEnd(source, i + 1);
            } else {
                i++;
            }
        }
        return literals;
    }

    /** Where the literal opened just before {@code from} closes, skipping escapes; -1 if it never does. */
    private static int closing(String source, int from, String quote) {
        boolean singleLine = quote.length() == 1;
        for (int i = from; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\\') {
                i++;
            } else if (singleLine && c == '\n') {
                return -1;
            } else if (source.startsWith(quote, i)) {
                return i;
            }
        }
        return -1;
    }

    private static int lineEnd(String source, int from) {
        int end = source.indexOf('\n', from);
        return end < 0 ? source.length() : end + 1;
    }

    /**
     * Whether a literal names the resource: the path itself, or the path after a scheme or directory prefix
     * ({@code classpath:static/openapi.yaml}, {@code /static/openapi.yaml}). A literal that merely ends with the
     * same characters ({@code validation-messages.properties} for {@code messages.properties}) is another file.
     */
    private static boolean loads(List<String> literals, String resource) {
        return literals.stream().anyMatch(literal -> literal.equals(resource)
                || literal.endsWith("/" + resource) || literal.endsWith(":" + resource));
    }

    private static String testSource(TaskContext context, FailedTest failed) {
        String topLevel = failed.className().contains("$") ? failed.className().substring(0, failed.className().indexOf('$')) : failed.className();
        return context.readFile("src/test/java/" + topLevel.replace('.', '/') + ".java").orElse("");
    }

    /** Non-Java files on the main classpath, which tests reference by their path relative to the resources root. */
    private static boolean isResource(String path) {
        return path.startsWith(RESOURCES) && path.length() > RESOURCES.length() && !path.endsWith(".java");
    }

    /** Production code and schema; documentation that merely mentions a symbol is not a suspect. */
    private static boolean isCode(String path) {
        return path.startsWith("src/main/") && (path.endsWith(".java") || path.endsWith(".sql"));
    }

    private static boolean introduces(TaskContext context, String path, String symbol) {
        String now = context.readFile(path).orElse("");
        String before = context.workspace().readBaseline(path).orElse("");
        return now.contains(symbol) && !before.contains(symbol);
    }
}
