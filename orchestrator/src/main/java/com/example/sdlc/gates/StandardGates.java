package com.example.sdlc.gates;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.example.sdlc.Json;
import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.capability.Clarifications;
import com.example.sdlc.capability.VerificationReport;
import com.example.sdlc.codebase.ArchitectureRules;
import com.example.sdlc.codebase.CodebaseIndexer;
import com.example.sdlc.codebase.LayerViolation;
import com.example.sdlc.engine.Gate;
import com.example.sdlc.engine.GateContext;
import com.example.sdlc.engine.GateResult;
import com.example.sdlc.engine.OutputArtifact;
import com.example.sdlc.human.HumanRequest;
import com.example.sdlc.policy.PersonalData;
import com.example.sdlc.reasoning.DesignProposal;
import com.example.sdlc.reasoning.Impact;
import com.example.sdlc.reasoning.RequirementSpec;
import com.example.sdlc.workspace.AppliedChangeSet;

import tools.jackson.databind.JsonNode;

/**
 * The catalogue of deterministic gates. Each gate is a small pure function of run state and the attempt's
 * result; together they encode the lifecycle's entry and exit criteria. Reasoning output never decides a
 * gate: at most it supplies the content a gate inspects, and self-declared flags (confirmed, personal-data
 * labels) are not trusted where a human or a deterministic check must decide.
 */
public final class StandardGates {

    /** Words that make an acceptance criterion unverifiable unless it is quantified. */
    private static final Pattern VAGUE = Pattern.compile(
            "(?i)\\b(fast|quick(ly)?|slow|secure|reliabl[ey]|robust|scalable|better|improved?|user-friendly|easy|simple|"
                    + "appropriate(ly)?|reasonable|efficient|soon|flexible|etc|as needed|some|several)\\b");
    private static final Pattern NUMBER = Pattern.compile("\\d");

    private final CodebaseIndexer indexer;
    private final ArchitectureRules rules;

    public StandardGates(CodebaseIndexer indexer, ArchitectureRules rules) {
        this.indexer = indexer;
        this.rules = rules;
    }

    public List<Gate> all() {
        return List.of(
                Gate.of("requirement-quality", this::requirementQuality),
                Gate.of("impact-grounded", this::impactGrounded),
                Gate.of("design-quality", this::designQuality),
                Gate.of("design-approval", this::designApproval),
                Gate.of("workspace-integrity", this::workspaceIntegrity),
                Gate.of("baseline-green", this::baselineGreen),
                Gate.of("architecture-conformance", this::architectureConformance),
                Gate.of("criteria-traced", this::criteriaTraced),
                Gate.of("tests-pass", this::testsPass),
                Gate.of("acceptance-coverage", this::acceptanceCoverage),
                Gate.of("static-checks-pass", this::staticChecksPass),
                Gate.of("security-clean", this::securityClean),
                Gate.of("api-compatible", this::apiCompatible),
                Gate.of("readiness-checklist", this::readinessChecklist),
                Gate.of("release-approval", this::releaseApproval));
    }

    // ---------------------------------------------------------------- requirements

    /**
     * Stops for clarification when the specification has blocking questions, high-impact assumptions no
     * human has answered, or unmeasurable acceptance criteria - even if the reasoning component claimed
     * otherwise (a HIGH-impact assumption marked "confirmed" by the analysis itself still has to be asked).
     */
    GateResult requirementQuality(GateContext context) {
        RequirementSpec spec = context.output(ArtifactKeys.REQUIREMENT_SPEC, RequirementSpec.class).orElse(null);
        if (spec == null || spec.acceptanceCriteria().isEmpty()) {
            return GateResult.fail("specification has no acceptance criteria", List.of("add verifiable acceptance criteria"));
        }
        Set<String> answered = context.current(ArtifactKeys.CLARIFICATIONS, Clarifications.class)
                .map(c -> c.answers().keySet()).orElse(Set.of());
        List<HumanRequest.Question> questions = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        for (RequirementSpec.OpenQuestion question : spec.openQuestions()) {
            if (question.blocking() && !answered.contains(question.id())) {
                questions.add(new HumanRequest.Question(question.id(), question.question(), question.rationale(),
                        question.options().stream().map(o -> new HumanRequest.Option(o.id(), o.label(), o.consequence())).toList(),
                        question.options().isEmpty()));
                reasons.add("blocking question " + question.id() + " raised by analysis");
            }
        }
        for (RequirementSpec.Assumption assumption : spec.assumptions()) {
            if (assumption.impact() == Impact.HIGH && !answered.contains(assumption.id())) {
                questions.add(new HumanRequest.Question(assumption.id(), "Confirm high-impact assumption: " + assumption.statement(),
                        "The analysis treated this as settled, but it has HIGH impact and no human has confirmed it.",
                        List.of(new HumanRequest.Option("confirm", "Confirm", "proceed on this assumption"),
                                new HumanRequest.Option("reject", "Reject", "the assumption is wrong; re-analyse")), true));
                reasons.add("HIGH-impact assumption " + assumption.id() + " not confirmed by a human");
            }
        }
        for (RequirementSpec.AcceptanceCriterion criterion : spec.acceptanceCriteria()) {
            if (VAGUE.matcher(criterion.statement()).find() && !NUMBER.matcher(criterion.statement()).find()
                    && !answered.contains(criterion.id())) {
                questions.add(new HumanRequest.Question(criterion.id(), "How should this be measured? \"" + criterion.statement() + "\"",
                        "Unquantified qualitative wording cannot be verified by a test.", List.of(), true));
                reasons.add("unmeasurable criterion " + criterion.id());
            }
        }
        if (!questions.isEmpty()) {
            return GateResult.clarification("Requirement needs clarification (" + questions.size() + " question(s))", reasons, questions);
        }
        return GateResult.pass(spec.acceptanceCriteria().size() + " verifiable acceptance criteria, no blocking ambiguity");
    }

    GateResult impactGrounded(GateContext context) {
        JsonNode impact = context.output(ArtifactKeys.IMPACT_ANALYSIS, JsonNode.class).orElse(null);
        if (impact == null) {
            return GateResult.fail("no impact analysis produced", List.of());
        }
        List<String> unresolved = strings(impact.path("analysis").path("unresolvedSeeds"));
        if (!unresolved.isEmpty()) {
            return GateResult.fail("impact seeds do not exist in the codebase: " + unresolved,
                    unresolved.stream().map(s -> "unknown component or table '" + s + "'; use names from the codebase model").toList());
        }
        if (impact.path("analysis").path("components").isEmpty()) {
            return GateResult.fail("impact analysis found no affected components", List.of());
        }
        return GateResult.pass("all " + impact.path("seeds").size() + " seeds grounded in the codebase");
    }

    // ---------------------------------------------------------------- design

    GateResult designQuality(GateContext context) {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, OutputArtifact> output : context.result().outputs().entrySet()) {
            if (!output.getKey().startsWith(ArtifactKeys.DECISION_PREFIX)) {
                continue;
            }
            DesignProposal.DecisionRecord decision = output.getValue().as(DesignProposal.DecisionRecord.class);
            if (decision.rationale() == null || decision.rationale().isBlank()) {
                problems.add("decision " + decision.id() + " has no rationale");
            }
            if (decision.alternatives().size() < 2) {
                problems.add("decision " + decision.id() + " considers fewer than two alternatives");
            }
        }
        context.output(ArtifactKeys.API_CONTRACT, JsonNode.class).ifPresent(contract -> contract.path("operations").forEach(op -> {
            String method = op.path("method").asString().toUpperCase(Locale.ROOT);
            String path = op.path("path").asString();
            if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method) || !path.startsWith("/")) {
                problems.add("invalid operation " + method + " " + path);
            }
            boolean declaresClientError = false;
            for (JsonNode status : op.path("responses")) {
                declaresClientError |= status.asInt() >= 400 && status.asInt() < 500;
            }
            if (!method.equals("GET") && !declaresClientError) {
                problems.add(method + " " + path + " declares no 4xx error responses");
            }
        }));
        context.output(ArtifactKeys.DATA_MODEL, JsonNode.class).ifPresent(model -> model.path("tables").forEach(table ->
                table.path("columns").forEach(column -> {
                    String name = column.path("name").asString();
                    // The column name is checked too: a raw-looking column cannot be labelled away.
                    if (column.path("personalData").asString("NONE").equalsIgnoreCase("RAW") || PersonalData.isRawPersonalDataColumn(name)) {
                        problems.add("column " + table.path("table").asString() + "." + name
                                + " stores raw personal data; pseudonymise it (e.g. a keyed hash column)");
                    }
                })));
        return problems.isEmpty() ? GateResult.pass("decisions justified, contract and data model well-formed")
                : GateResult.fail("design does not meet quality bar", problems);
    }

    /** High-impact decisions and any data model change need an architect's sign-off before implementation. */
    GateResult designApproval(GateContext context) {
        List<String> reasons = new ArrayList<>();
        for (Map.Entry<String, OutputArtifact> output : context.result().outputs().entrySet()) {
            if (output.getKey().startsWith(ArtifactKeys.DECISION_PREFIX)) {
                DesignProposal.DecisionRecord decision = output.getValue().as(DesignProposal.DecisionRecord.class);
                if (decision.impact() == Impact.HIGH) {
                    reasons.add("HIGH impact decision " + decision.id() + ": " + decision.title() + " -> " + decision.choice());
                }
            }
        }
        context.output(ArtifactKeys.DATA_MODEL, JsonNode.class).ifPresent(model -> model.path("tables").forEach(table ->
                reasons.add("data model " + table.path("change").asString() + " " + table.path("table").asString())));
        return reasons.isEmpty() ? GateResult.pass("no high-impact decisions; approval not required")
                : GateResult.approval("Design sign-off required for " + context.task().id(), reasons);
    }

    // ---------------------------------------------------------------- workspace and implementation

    /**
     * Entry gate: the workspace holds exactly baseline + governed change sets (detects out-of-band edits,
     * including symbolic links, which could redirect later writes outside the workspace).
     */
    GateResult workspaceIntegrity(GateContext context) {
        Set<String> governed = new LinkedHashSet<>();
        List<String> problems = new ArrayList<>();
        Map<String, String> lastHash = new HashMap<>();
        for (AppliedChangeSet applied : context.run().appliedChanges()) {
            for (AppliedChangeSet.AppliedFile file : applied.files()) {
                governed.add(file.path());
                lastHash.put(file.path(), file.postHash());
            }
        }
        lastHash.forEach((path, expected) -> {
            String actual = context.workspace().hash(path).orElse(null);
            if (expected == null ? actual != null : !expected.equals(actual)) {
                problems.add(path + " differs from its governed change set");
            }
        });
        for (String path : context.workspace().changedPaths()) {
            if (!governed.contains(path)) {
                problems.add(path + " was changed outside governance");
            }
        }
        context.workspace().irregularEntries().forEach(path -> problems.add(path + " is a link or special file"));
        return problems.isEmpty() ? GateResult.pass("workspace matches baseline + " + context.run().appliedChanges().size() + " change sets")
                : GateResult.block("workspace was modified outside the workflow", problems);
    }

    /** Exit gate of the baseline build, and entry gate of change tasks: never build on a red baseline. */
    GateResult baselineGreen(GateContext context) {
        VerificationReport report = context.result() != null
                ? context.output(ArtifactKeys.BASELINE_VERIFICATION, VerificationReport.class).orElse(null)
                : context.current(ArtifactKeys.BASELINE_VERIFICATION, VerificationReport.class).orElse(null);
        if (report == null) {
            return GateResult.block("no baseline verification available", List.of());
        }
        if (report.passedRealBuild()) {
            return GateResult.pass("baseline green: " + report.testsRun() + " tests passed");
        }
        if (report.degraded()) {
            return GateResult.pass("baseline only statically checked (DEGRADED) - release will not be declared ready");
        }
        return GateResult.block("baseline is not green (" + report.outcome() + "); failures could not be attributed to the change",
                report.failedTests().stream().map(t -> t.className() + "." + t.testName()).toList());
    }

    GateResult architectureConformance(GateContext context) {
        List<LayerViolation> before = context.current(ArtifactKeys.CODEBASE_MODEL, JsonNode.class)
                .map(model -> List.of(Json.convert(model.path("baselineViolations"), LayerViolation[].class)))
                .orElse(List.of());
        List<LayerViolation> introduced = rules.check(indexer.index(context.workspace().root())).stream()
                .filter(v -> !before.contains(v)).toList();
        return introduced.isEmpty() ? GateResult.pass("no new layering violations")
                : GateResult.fail("change introduces layering violations", introduced.stream()
                .map(v -> v.fromType() + " (" + v.fromLayer() + ") -> " + v.toType() + " (" + v.toLayer() + "): " + v.rule()).toList());
    }

    GateResult criteriaTraced(GateContext context) {
        RequirementSpec spec = context.current(ArtifactKeys.REQUIREMENT_SPEC, RequirementSpec.class).orElse(null);
        if (spec == null) {
            return GateResult.pass("no specification to trace");
        }
        String allTests = testSources(context.workspace().root().resolve("src/test/java"));
        List<String> untraced = spec.acceptanceCriteria().stream().map(RequirementSpec.AcceptanceCriterion::id)
                .filter(id -> !Pattern.compile("\\b" + Pattern.quote(id) + "\\b").matcher(allTests).find()).toList();
        return untraced.isEmpty() ? GateResult.pass("every acceptance criterion is referenced by a test")
                : GateResult.fail("acceptance criteria without tests: " + untraced,
                untraced.stream().map(id -> "add a test that references " + id + " (e.g. in @DisplayName)").toList());
    }

    // ---------------------------------------------------------------- verification

    GateResult testsPass(GateContext context) {
        VerificationReport report = context.output(ArtifactKeys.VERIFICATION_REPORT, VerificationReport.class).orElse(null);
        if (report == null) {
            return GateResult.fail("no verification report", List.of());
        }
        if (report.passedRealBuild()) {
            return GateResult.pass(report.testsRun() + " tests passed");
        }
        if (report.outcome().equals("PASSED")) {
            return GateResult.fail("build passed but no tests ran", List.of());
        }
        List<String> details = new ArrayList<>();
        report.failedTests().forEach(t -> details.add(t.className() + "." + t.testName() + ": " + t.message()));
        if (report.outcome().equals("COMPILATION_FAILED")) {
            report.outputTail().lines().filter(l -> l.contains("ERROR") && l.contains(".java")).limit(10).forEach(details::add);
        }
        return GateResult.defect(report.outcome() + ": " + report.failures() + " failures, " + report.errors() + " errors",
                details, report.suspects());
    }

    GateResult acceptanceCoverage(GateContext context) {
        VerificationReport report = context.output(ArtifactKeys.VERIFICATION_REPORT, VerificationReport.class).orElse(null);
        if (report == null) {
            return GateResult.fail("no verification report", List.of());
        }
        List<String> uncovered = report.acceptanceCoverage().stream().filter(c -> !c.covered()).map(VerificationReport.Coverage::id).toList();
        if (uncovered.isEmpty()) {
            return GateResult.pass(report.acceptanceCoverage().isEmpty() ? "no acceptance criteria to cover"
                    : "all " + report.acceptanceCoverage().size() + " acceptance criteria covered by passing tests");
        }
        List<String> testAuthors = context.run().appliedChanges().stream()
                .filter(applied -> applied.paths().stream().anyMatch(p -> p.startsWith("src/test/")))
                .map(AppliedChangeSet::taskId).distinct().toList();
        return GateResult.defect("acceptance criteria not covered: " + uncovered,
                uncovered.stream().map(id -> id + " has no passing test").toList(), testAuthors);
    }

    GateResult staticChecksPass(GateContext context) {
        VerificationReport report = context.output(ArtifactKeys.VERIFICATION_REPORT, VerificationReport.class)
                .or(() -> context.output(ArtifactKeys.BASELINE_VERIFICATION, VerificationReport.class))
                .orElse(null);
        if (report == null) {
            return GateResult.fail("no static verification report", List.of());
        }
        List<String> violations = report.introducedViolations().stream().map(v -> v.fromType() + " -> " + v.toType()).toList();
        return violations.isEmpty() ? GateResult.pass("static checks passed (DEGRADED: tests not executed)")
                : GateResult.fail("static checks found new layering violations", violations);
    }

    // ---------------------------------------------------------------- validation and release

    GateResult securityClean(GateContext context) {
        JsonNode review = context.output(ArtifactKeys.SECURITY_REVIEW, JsonNode.class).orElse(null);
        if (review != null && review.path("status").asString().equals("CLEAN")) {
            return GateResult.pass(review.path("filesReviewed").asInt() + " files clean; " + review.path("approvedFindings").size()
                    + " findings covered by recorded approvals");
        }
        List<String> details = new ArrayList<>();
        if (review != null) {
            details.addAll(strings(review.path("blocking")));
            details.addAll(strings(review.path("unapproved")));
        }
        return GateResult.block("security/compliance review found open issues", details);
    }

    GateResult apiCompatible(GateContext context) {
        JsonNode review = context.output(ArtifactKeys.API_COMPATIBILITY, JsonNode.class).orElse(null);
        if (review != null && review.path("status").asString().equals("COMPATIBLE")) {
            return GateResult.pass("no breaking changes; added " + strings(review.path("addedOperations")));
        }
        List<String> details = new ArrayList<>();
        if (review != null) {
            details.addAll(strings(review.path("breakingChanges")));
            strings(review.path("undocumented")).forEach(u -> details.add("undocumented: " + u));
            strings(review.path("unimplemented")).forEach(u -> details.add("unimplemented: " + u));
        }
        return GateResult.block("API change control failed", details);
    }

    GateResult readinessChecklist(GateContext context) {
        JsonNode readiness = context.output(ArtifactKeys.RELEASE_READINESS, JsonNode.class).orElse(null);
        if (readiness == null) {
            return GateResult.block("no readiness assessment", List.of());
        }
        List<String> failed = new ArrayList<>();
        readiness.path("items").forEach(item -> {
            if (!item.path("passed").asBoolean()) {
                failed.add(item.path("id").asString() + ": " + item.path("evidence").asString());
            }
        });
        return failed.isEmpty() ? GateResult.pass("all " + readiness.path("items").size() + " readiness checks passed")
                : GateResult.block("NOT READY: " + failed.size() + " readiness check(s) failed", failed);
    }

    /** Human sign-off, shown the checklist, findings that only exist in the aggregate, and the exact tree approved. */
    GateResult releaseApproval(GateContext context) {
        List<String> details = new ArrayList<>();
        context.output(ArtifactKeys.RELEASE_READINESS, JsonNode.class).ifPresent(readiness ->
                readiness.path("items").forEach(item -> details.add((item.path("passed").asBoolean() ? "PASS " : "FAIL ")
                        + item.path("title").asString() + " - " + item.path("evidence").asString())));
        context.current(ArtifactKeys.SECURITY_REVIEW, JsonNode.class).ifPresent(review ->
                strings(review.path("aggregateFindings")).forEach(f -> details.add("ATTENTION " + f)));
        details.add("changed files: " + context.workspace().changedPaths());
        details.add("approving source tree " + context.workspace().contentHash());
        return GateResult.approval("Release sign-off: accept the change for merge", details);
    }

    // ---------------------------------------------------------------- helpers

    private static String testSources(Path root) {
        if (!Files.isDirectory(root)) {
            return "";
        }
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    all.append(Files.readString(p)).append('\n');
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return all.toString();
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.isString() ? node.asString() : node.toString()));
        return values;
    }
}
