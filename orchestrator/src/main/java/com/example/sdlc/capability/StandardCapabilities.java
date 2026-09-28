package com.example.sdlc.capability;

import static com.example.sdlc.capability.CapabilityNames.*;

import java.util.List;

import com.example.sdlc.codebase.ArchitectureRules;
import com.example.sdlc.codebase.CodebaseIndexer;
import com.example.sdlc.codebase.ImpactAnalyzer;
import com.example.sdlc.engine.Capability;
import com.example.sdlc.engine.Capability.Role;
import com.example.sdlc.engine.FailureKind;
import com.example.sdlc.engine.TaskFailure;
import com.example.sdlc.engine.TaskHandler;
import com.example.sdlc.engine.WorkflowEngine;
import com.example.sdlc.plan.Stage;
import com.example.sdlc.plan.TaskSpec;
import com.example.sdlc.plan.WorkflowPlan;
import com.example.sdlc.policy.ChangePolicy;
import com.example.sdlc.reasoning.ReasoningProvider;
import com.example.sdlc.verify.MavenBuildVerifier;

/**
 * The capability catalogue: what work exists, which gates are mandatory for it, and which deterministic
 * behaviour backs it. Plans can only reference these names.
 */
public final class StandardCapabilities {

    private static final String VERIFY_STATIC_BASELINE = "verify-static-baseline";

    private StandardCapabilities() {
    }

    /**
     * @param verifier real build verifier, or {@code null} when the operator disabled real builds (verification
     *                 then falls back to degraded static checks and the release cannot be declared ready)
     */
    public static List<Capability> create(ReasoningProvider reasoning, MavenBuildVerifier verifier, ChangePolicy policy) {
        CodebaseIndexer indexer = new CodebaseIndexer();
        ArchitectureRules rules = new ArchitectureRules();
        TaskHandler change = new ChangeProposalHandler(reasoning);
        List<String> plannable = List.of(DESIGN, IMPLEMENT, AUTHOR_TESTS, DOCUMENT, VERIFY_BUILD, REVIEW_SECURITY, REVIEW_API, ASSESS_RELEASE);
        List<String> integrity = List.of("workspace-integrity");
        List<String> none = List.of();
        return List.of(
                bootstrap(ANALYZE_REQUIREMENT, Stage.REQUIREMENTS, 3, List.of("requirement-quality"), null,
                        new RequirementAnalysisHandler(reasoning)),
                bootstrap(INDEX_CODEBASE, Stage.ANALYSIS, 1, none, null, new CodebaseScanHandler(indexer, rules)),
                bootstrap(VERIFY_BASELINE, Stage.TESTING, 2, List.of("baseline-green"), VERIFY_STATIC_BASELINE,
                        realBuildOr(verifier, true)),
                bootstrap(VERIFY_STATIC_BASELINE, Stage.TESTING, 1, List.of("baseline-green"), null,
                        new StaticVerificationHandler(indexer, rules, true)),
                bootstrap(ANALYZE_IMPACT, Stage.ANALYSIS, 2, List.of("impact-grounded"), null,
                        new ImpactAnalysisHandler(reasoning, new ImpactAnalyzer())),
                new Capability(PLAN, Stage.PLANNING, Role.WORK, false, false, none, true, 2, none, none, null,
                        new PlanningHandler(reasoning, plannable)),
                new Capability(DESIGN, Stage.DESIGN, Role.WORK, true, false, none, false, 2, none,
                        List.of("design-quality", "design-approval"), null, new DesignHandler(reasoning)),
                new Capability(IMPLEMENT, Stage.IMPLEMENTATION, Role.WORK, true, true, List.of("src/main/"), false, 3,
                        List.of("workspace-integrity", "baseline-green"), List.of("architecture-conformance"), null, change),
                new Capability(AUTHOR_TESTS, Stage.TESTING, Role.WORK, true, true, List.of("src/test/"), false, 2,
                        List.of("workspace-integrity", "baseline-green"), List.of("criteria-traced"), null, change),
                new Capability(DOCUMENT, Stage.DOCUMENTATION, Role.WORK, true, true,
                        List.of("docs/", "README.md", WorkspaceFacts.OPENAPI), false, 2, integrity, none, null, change),
                new Capability(VERIFY_BUILD, Stage.TESTING, Role.VERIFICATION, true, false, none, false, 2, integrity,
                        List.of("tests-pass", "acceptance-coverage"), VERIFY_STATIC, realBuildOr(verifier, false)),
                new Capability(VERIFY_STATIC, Stage.TESTING, Role.VERIFICATION, false, false, none, false, 1, integrity,
                        List.of("static-checks-pass"), null, new StaticVerificationHandler(indexer, rules, false)),
                new Capability(REVIEW_SECURITY, Stage.VALIDATION, Role.SECURITY_REVIEW, true, false, none, false, 1, integrity,
                        List.of("security-clean"), null, new SecurityReviewHandler(policy)),
                new Capability(REVIEW_API, Stage.VALIDATION, Role.COMPATIBILITY_REVIEW, true, false, none, false, 1, integrity,
                        List.of("api-compatible"), null, new ApiReviewHandler(indexer)),
                new Capability(ASSESS_RELEASE, Stage.RELEASE, Role.RELEASE, true, false, none, false, 1, integrity,
                        List.of("readiness-checklist", "release-approval"), null, new ReleaseAssessmentHandler()));
    }

    /**
     * Fixed discovery phase that runs before any plan exists. Requirement analysis, codebase scanning and the
     * baseline build are independent and run in parallel; impact analysis joins them; planning follows.
     * Changes additionally wait for the baseline (the plan validator adds that edge).
     */
    public static WorkflowPlan bootstrapPlan() {
        return WorkflowPlan.of(1, List.of(
                spec("requirements", "Analyse and normalise the requirement", Stage.REQUIREMENTS, ANALYZE_REQUIREMENT, List.of(), 3, null),
                spec("codebase-scan", "Index the target codebase", Stage.ANALYSIS, INDEX_CODEBASE, List.of(), 1, null),
                spec(WorkflowEngine.BASELINE_TASK, "Build and test the untouched baseline", Stage.TESTING, VERIFY_BASELINE, List.of(), 2,
                        VERIFY_STATIC_BASELINE),
                spec("impact", "Analyse impact on modules, APIs and data flows", Stage.ANALYSIS, ANALYZE_IMPACT,
                        List.of("requirements", "codebase-scan"), 2, null),
                spec("planning", "Decompose the work into a governed plan", Stage.PLANNING, PLAN, List.of("impact"), 2, null)),
                "fixed discovery phase", "bootstrap");
    }

    private static TaskSpec spec(String id, String title, Stage stage, String capability, List<String> dependsOn, int attempts, String fallback) {
        return new TaskSpec(id, title, stage, capability, dependsOn, "", List.of(), List.of(), List.of(), List.of(), List.of(), attempts, fallback);
    }

    private static Capability bootstrap(String name, Stage stage, int attempts, List<String> exitGates, String fallback, TaskHandler handler) {
        return new Capability(name, stage, Role.WORK, false, false, List.of(), false, attempts, List.of(), exitGates, fallback, handler);
    }

    private static TaskHandler realBuildOr(MavenBuildVerifier verifier, boolean baseline) {
        if (verifier == null) {
            return context -> {
                throw new TaskFailure(FailureKind.UNAVAILABLE, "real build verification disabled by operator (--verification static)");
            };
        }
        return new BuildVerificationHandler(verifier, baseline);
    }
}
