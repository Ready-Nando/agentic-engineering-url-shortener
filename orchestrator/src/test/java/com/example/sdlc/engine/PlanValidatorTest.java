package com.example.sdlc.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.example.sdlc.capability.StandardCapabilities;
import com.example.sdlc.engine.Gate;
import com.example.sdlc.gates.StandardGates;
import com.example.sdlc.plan.PlanRejectedException;
import com.example.sdlc.plan.Stage;
import com.example.sdlc.plan.TaskSpec;
import com.example.sdlc.plan.WorkflowPlan;
import com.example.sdlc.policy.ChangePolicy;

class PlanValidatorTest {

    private final Map<String, Capability> capabilities = StandardCapabilities.create(null, null, new ChangePolicy()).stream()
            .collect(Collectors.toMap(Capability::name, Function.identity()));
    private final PlanValidator validator = new PlanValidator(capabilities, gateNames(), 3);
    private final WorkflowPlan bootstrap = StandardCapabilities.bootstrapPlan();

    private static Set<String> gateNames() {
        return new StandardGates(null, null).all().stream().map(Gate::name).collect(Collectors.toSet());
    }

    private static TaskSpec task(String id, String capability, List<String> dependsOn, List<String> scope, List<String> verifies, int attempts) {
        return new TaskSpec(id, id, null, capability, dependsOn, "", List.of(), scope, verifies, List.of(), List.of(), attempts, null);
    }

    private static List<TaskSpec> validPlan() {
        return List.of(
                task("design", "design", List.of(), List.of(), List.of(), 0),
                task("impl", "implement", List.of("design"), List.of("src/main/**"), List.of(), 9),
                task("tests", "author-tests", List.of("impl"), List.of("src/test/**"), List.of(), 0),
                task("verify", "verify-build", List.of("tests"), List.of(), List.of("impl", "tests"), 0),
                task("security", "review-security", List.of("tests"), List.of(), List.of(), 0),
                task("api", "review-api", List.of("tests"), List.of(), List.of(), 0),
                task("release", "assess-release", List.of("verify", "security", "api"), List.of(), List.of(), 0));
    }

    @Test
    void normalisesAValidProposal() {
        PlanValidator.Validated validated = validator.validate(bootstrap, validPlan(), "why", "planning", WorkflowEngine.BASELINE_TASK);
        WorkflowPlan plan = validated.plan();

        assertThat(plan.version()).isEqualTo(2);
        assertThat(plan.tasks()).hasSize(bootstrap.tasks().size() + 7);
        assertThat(plan.task("design").dependsOn()).containsExactly("planning");
        assertThat(plan.task("impl").dependsOn()).containsExactly("design", "baseline");
        assertThat(plan.task("impl").stage()).isEqualTo(Stage.IMPLEMENTATION);
        assertThat(plan.task("impl").exitGates()).contains("architecture-conformance");
        assertThat(plan.task("impl").entryGates()).contains("workspace-integrity", "baseline-green");
        assertThat(plan.task("verify").fallback()).isEqualTo("verify-static");
        assertThat(plan.task("release").exitGates()).containsExactly("readiness-checklist", "release-approval");
        assertThat(plan.task("impl").maxAttempts()).isEqualTo(3);
        assertThat(validated.notes()).singleElement().satisfies(n -> assertThat(n).contains("requested 9 attempts; capped at 3"));
    }

    @Test
    void rejectsUnknownAndBootstrapOnlyCapabilities() {
        List<TaskSpec> plan = List.of(task("x", "deploy-to-production", List.of(), List.of(), List.of(), 1),
                task("y", "plan", List.of(), List.of(), List.of(), 1));

        assertThatThrownBy(() -> validator.validate(bootstrap, plan, "", "planning", null))
                .isInstanceOfSatisfying(PlanRejectedException.class, e -> assertThat(e.violations())
                        .anyMatch(v -> v.contains("deploy-to-production")).anyMatch(v -> v.contains("'plan'")));
    }

    @Test
    void rejectsDanglingDependencies() {
        List<TaskSpec> cyclic = List.of(task("a", "design", List.of("b"), List.of(), List.of(), 1),
                task("b", "design", List.of("a"), List.of(), List.of(), 1),
                task("release", "assess-release", List.of("a", "b", "ghost"), List.of(), List.of(), 1));

        assertThatThrownBy(() -> validator.validate(bootstrap, cyclic, "", "planning", null))
                .isInstanceOfSatisfying(PlanRejectedException.class, e -> assertThat(e.violations()).anyMatch(v -> v.contains("ghost")));
    }

    @Test
    void everyChangeMustBeVerifiedAndSecurityReviewedBeforeASingleRelease() {
        List<TaskSpec> unsafe = List.of(
                task("impl", "implement", List.of(), List.of("src/main/**"), List.of(), 1),
                task("verify", "verify-build", List.of(), List.of(), List.of("impl"), 1),
                task("release", "assess-release", List.of("verify"), List.of(), List.of(), 1));

        assertThatThrownBy(() -> validator.validate(bootstrap, unsafe, "", "planning", null))
                .isInstanceOfSatisfying(PlanRejectedException.class, e -> assertThat(e.violations())
                        .anyMatch(v -> v.contains("has no SECURITY_REVIEW task"))
                        .anyMatch(v -> v.contains("'verify' (VERIFICATION) must run after change task 'impl'"))
                        .anyMatch(v -> v.contains("must depend (transitively) on 'impl'")));
    }

    @Test
    void everyVerificationTaskMustListEveryChangeTask() {
        List<TaskSpec> plan = validPlan().stream()
                .map(t -> t.id().equals("verify") ? task("verify", "verify-build", List.of("tests"), List.of(), List.of("impl"), 0) : t)
                .toList();

        assertThatThrownBy(() -> validator.validate(bootstrap, plan, "", "planning", null))
                .isInstanceOfSatisfying(PlanRejectedException.class, e -> assertThat(e.violations())
                        .containsExactly("'verify' must verify every change task; missing [tests]"));
    }

    @Test
    void planThatChangesCodeMustBeCompatibilityReviewed() {
        List<TaskSpec> plan = validPlan().stream()
                .filter(t -> !t.id().equals("api"))
                .map(t -> t.id().equals("release") ? t.withDependsOn(List.of("verify", "security")) : t)
                .toList();

        assertThatThrownBy(() -> validator.validate(bootstrap, plan, "", "planning", null))
                .isInstanceOfSatisfying(PlanRejectedException.class, e -> assertThat(e.violations())
                        .containsExactly("plan changes code but has no COMPATIBILITY_REVIEW task"));
    }

    /** A second verifier (e.g. one per partition), with the release waiting for both. */
    private static List<TaskSpec> withSecondVerifier(List<String> firstVerifies, List<String> secondVerifies, List<String> secondDependsOn) {
        List<TaskSpec> plan = new ArrayList<>(validPlan().stream()
                .map(t -> t.id().equals("verify") ? task("verify", "verify-build", List.of("tests"), List.of(), firstVerifies, 0)
                        : t.id().equals("release") ? t.withDependsOn(List.of("verify", "verify-again", "security", "api")) : t)
                .toList());
        plan.add(task("verify-again", "verify-build", secondDependsOn, List.of(), secondVerifies, 0));
        return plan;
    }

    @Test
    void verificationTasksMayNotPartitionTheChanges() {
        // Each build runs the whole suite on the whole tree: 'verify' would meet a defect in 'tests' it may not send back.
        List<TaskSpec> plan = withSecondVerifier(List.of("impl"), List.of("tests"), List.of("verify"));

        assertThatThrownBy(() -> validator.validate(bootstrap, plan, "", "planning", null))
                .isInstanceOfSatisfying(PlanRejectedException.class, e -> assertThat(e.violations()).containsExactly(
                        "'verify' must verify every change task; missing [tests]",
                        "'verify-again' must verify every change task; missing [impl]"));
    }

    @Test
    void sequentialVerificationTasksThatEachVerifyEverythingAreAccepted() {
        List<TaskSpec> plan = withSecondVerifier(List.of("impl", "tests"), List.of("impl", "tests"), List.of("verify"));

        WorkflowPlan validated = validator.validate(bootstrap, plan, "", "planning", null).plan();

        assertThat(validated.task("verify").verifies()).containsExactly("impl", "tests");
        assertThat(validated.task("verify-again").verifies()).containsExactly("impl", "tests");
        assertThat(validated.ancestors("verify-again")).contains("verify");
        assertThat(validated.task("api").exitGates()).contains("api-compatible");
    }

    @Test
    void verificationTasksMayNotRunInParallel() {
        List<TaskSpec> plan = withSecondVerifier(List.of("impl", "tests"), List.of("impl", "tests"), List.of("tests"));

        assertThatThrownBy(() -> validator.validate(bootstrap, plan, "", "planning", null))
                .isInstanceOfSatisfying(PlanRejectedException.class, e -> assertThat(e.violations()).containsExactly(
                        "verification tasks 'verify' and 'verify-again' could run in parallel; make one depend on the other"));
    }

    @Test
    void changeScopesMustStayInsideTheWorkspace() {
        List<TaskSpec> plan = List.of(
                task("impl", "implement", List.of(), List.of("../../etc/**"), List.of(), 1),
                task("review", "review-api", List.of(), List.of("src/**"), List.of(), 1),
                task("release", "assess-release", List.of("impl", "review"), List.of(), List.of(), 1));

        assertThatThrownBy(() -> validator.validate(bootstrap, plan, "", "planning", null))
                .isInstanceOfSatisfying(PlanRejectedException.class, e -> assertThat(e.violations())
                        .anyMatch(v -> v.contains("'../../etc/**' is outside what 'implement' may change"))
                        .anyMatch(v -> v.contains("cannot change files")));
    }

    @Test
    void eachChangeCapabilityMayOnlyTouchItsOwnPartOfTheWorkspace() {
        List<TaskSpec> plan = List.of(
                task("impl", "implement", List.of(), List.of("**"), List.of(), 1),
                task("docs", "document", List.of(), List.of("src/main/java/**"), List.of(), 1),
                task("tests", "author-tests", List.of(), List.of("src/main/resources/application.yml"), List.of(), 1),
                task("verify", "verify-build", List.of("impl", "docs", "tests"), List.of(), List.of("impl", "docs", "tests"), 1),
                task("security", "review-security", List.of("impl", "docs", "tests"), List.of(), List.of(), 1),
                task("release", "assess-release", List.of("verify", "security"), List.of(), List.of(), 1));

        assertThatThrownBy(() -> validator.validate(bootstrap, plan, "", "planning", null))
                .isInstanceOfSatisfying(PlanRejectedException.class, e -> assertThat(e.violations())
                        .anyMatch(v -> v.contains("'**' is outside what 'implement' may change"))
                        .anyMatch(v -> v.contains("'src/main/java/**' is outside what 'document' may change"))
                        .anyMatch(v -> v.contains("'src/main/resources/application.yml' is outside what 'author-tests' may change")));
    }

    @Test
    void workspaceWideChecksMustRunAfterEveryChange() {
        List<TaskSpec> plan = List.of(
                task("impl", "implement", List.of(), List.of("src/main/**"), List.of(), 1),
                task("tests", "author-tests", List.of("impl"), List.of("src/test/**"), List.of(), 1),
                task("verify", "verify-build", List.of("tests"), List.of(), List.of("impl", "tests"), 1),
                task("security", "review-security", List.of("tests"), List.of(), List.of(), 1),
                task("api", "review-api", List.of("impl"), List.of(), List.of(), 1),
                task("release", "assess-release", List.of("verify", "security", "api"), List.of(), List.of(), 1));

        assertThatThrownBy(() -> validator.validate(bootstrap, plan, "", "planning", null))
                .isInstanceOfSatisfying(PlanRejectedException.class, e -> assertThat(e.violations())
                        .containsExactly("'api' (COMPATIBILITY_REVIEW) must run after change task 'tests'"));
    }

    @Test
    void plansCannotChooseTheirOwnFallbacks() {
        List<TaskSpec> plan = validPlan().stream()
                .map(t -> t.id().equals("impl") ? t.withFallback("document") : t)
                .toList();

        assertThatThrownBy(() -> validator.validate(bootstrap, plan, "", "planning", null))
                .isInstanceOfSatisfying(PlanRejectedException.class, e -> assertThat(e.violations())
                        .anyMatch(v -> v.contains("may not choose fallback 'document'")));
    }

    @Test
    void verificationMayOnlyReworkChangeTasks() {
        List<TaskSpec> plan = validPlan().stream()
                .map(t -> t.id().equals("verify")
                        ? new TaskSpec("verify", "verify", null, "verify-build", List.of("tests"), "", List.of(), List.of(),
                        List.of("impl", "tests", "design"), List.of(), List.of(), 0, null)
                        : t)
                .toList();

        assertThatThrownBy(() -> validator.validate(bootstrap, plan, "", "planning", null))
                .isInstanceOfSatisfying(PlanRejectedException.class, e -> assertThat(e.violations())
                        .containsExactly("'verify' verifies 'design', which is not a change task of this plan"));
    }

    @Test
    void rejectsPureCycles() {
        List<TaskSpec> cyclic = List.of(task("a", "design", List.of("b"), List.of(), List.of(), 1),
                task("b", "design", List.of("a"), List.of(), List.of(), 1),
                task("release", "assess-release", List.of("a", "b"), List.of(), List.of(), 1));

        assertThatThrownBy(() -> validator.validate(bootstrap, cyclic, "", "planning", null))
                .isInstanceOfSatisfying(PlanRejectedException.class, e -> assertThat(e.violations()).contains("dependency cycle detected"));
    }

    @Test
    void replanReplacesPreviouslyPlannedTasksButKeepsBootstrapTasks() {
        WorkflowPlan v2 = validator.validate(bootstrap, validPlan(), "", "planning", null).plan();
        List<TaskSpec> smaller = validPlan().stream().filter(t -> !t.id().equals("tests"))
                .map(t -> t.id().equals("verify") ? t.withDependsOn(List.of("impl")) : t).toList();
        List<TaskSpec> fixedVerifies = smaller.stream().map(t -> t.id().equals("verify")
                ? new TaskSpec("verify", "verify", null, "verify-build", List.of("impl"), "", List.of(), List.of(), List.of("impl"), List.of(), List.of(), 0, null)
                : t.id().equals("security") || t.id().equals("api") ? t.withDependsOn(List.of("impl")) : t).toList();

        WorkflowPlan v3 = validator.validate(v2, fixedVerifies, "", "planning", null).plan();

        assertThat(v3.version()).isEqualTo(3);
        assertThat(v3.contains("tests")).isFalse();
        assertThat(v3.contains("requirements")).isTrue();
        assertThat(Set.copyOf(v3.tasks().stream().map(TaskSpec::id).toList())).contains("design", "impl", "verify", "release");
    }
}
