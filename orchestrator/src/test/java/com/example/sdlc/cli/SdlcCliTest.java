package com.example.sdlc.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.sdlc.engine.EventType;
import com.example.sdlc.engine.ExecutionEvent;
import com.example.sdlc.engine.RunStatus;
import com.example.sdlc.engine.WorkflowRun;
import com.example.sdlc.human.HumanRequest;
import com.example.sdlc.store.RunStore;
import com.example.sdlc.workspace.Workspace;

import picocli.CommandLine;

/** Drives the CLI the way a reviewer would, against the real scenarios (static verification for speed). */
class SdlcCliTest {

    @TempDir
    Path runs;

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void captureOutput() {
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(capturedErr, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreOutput() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private int sdlc(String... args) {
        String[] all = new String[args.length + 2];
        all[0] = "--runs-dir";
        all[1] = runs.toString();
        System.arraycopy(args, 0, all, 2, args.length);
        return new CommandLine(new SdlcCli()).execute(all);
    }

    private String output() {
        String text = captured.toString(StandardCharsets.UTF_8);
        captured.reset();
        return text;
    }

    private String errors() {
        String text = capturedErr.toString(StandardCharsets.UTF_8);
        capturedErr.reset();
        return text;
    }

    private String onlyRunId() throws Exception {
        try (var dirs = Files.list(runs)) {
            return dirs.findFirst().orElseThrow().getFileName().toString();
        }
    }

    private RunStore store() {
        return new RunStore(runs);
    }

    private String firstPendingRequest(String runId) {
        return store().load(runId).pendingHumanRequests().getFirst().id();
    }

    private List<ExecutionEvent> humanDecisions(String runId) {
        return store().events(runId).stream().filter(e -> e.type() == EventType.HUMAN_INPUT_RECEIVED).toList();
    }

    private void assertWorkspaceRestored(String runId) {
        Path directory = store().directory(runId);
        Workspace workspace = Workspace.open(directory.resolve("workspace"), directory.resolve("baseline"));
        assertThat(workspace.contentHash()).isEqualTo(workspace.baselineHash());
    }

    @Test
    void runsAScenarioAndInspectsItThroughEveryCommand() throws Exception {
        assertThat(sdlc("scenarios")).isZero();
        assertThat(output()).contains("brownfield", "BROWNFIELD");

        assertThat(sdlc("run", "brownfield", "--verification", "static")).isEqualTo(SdlcCli.EXIT_HALTED);
        String runOutput = output();
        assertThat(runOutput).contains("SAFE STOP", "NOT_READY", "policy DENY", "plan v2", "fallback");
        String runId = onlyRunId();
        // The runs directory is outside the repository, so outcome paths are printed absolute.
        assertThat(runOutput).contains(runs.resolve(runId).resolve("outcome").resolve("ENGINEERING_SUMMARY.md").toString())
                .doesNotContain("../");

        assertThat(sdlc("status", runId)).isZero();
        assertThat(output()).contains("HALTED", "impl-capture", "ROLLED_BACK", "exec 2 gen 1");

        assertThat(sdlc("lineage", runId, "changes/impl-capture")).isZero();
        assertThat(output()).contains("Derived from (upstream)", "decision/D-1@v1", "RETRACTED");

        assertThat(sdlc("events", runId, "--type", "POLICY_EVALUATED")).isZero();
        assertThat(output()).contains("POLICY_EVALUATED", "impl-capture");

        assertThat(sdlc("metrics", runId)).isZero();
        assertThat(output()).contains("\"status\" : \"HALTED\"", "\"verdict\" : \"NOT_READY\"", "\"fallbacks\" : 2",
                "\"policyDenials\" : 1", "\"rollbacksByCause\"");

        assertThat(sdlc("metrics")).isZero();
        assertThat(output()).contains("\"runs\" : 1", "\"halted\" : 1", "\"completed\" : 0", "\"ready\" : 0");

        assertThat(sdlc("governance")).isZero();
        assertThat(output()).contains("CMP-01", "assess-release", "readiness-checklist");

        assertThat(sdlc("runs")).isZero();
        assertThat(output()).contains(runId, "HALTED");
    }

    @Test
    void aDeferredRunKeepsItsHumanReviewerWhenResumedAndCancellingRestoresTheWorkspace() throws Exception {
        assertThat(sdlc("run", "brownfield", "--verification", "static", "--reviewer", "deferred"))
                .isEqualTo(SdlcCli.EXIT_AWAITING_HUMAN);
        String runId = onlyRunId();
        String first = firstPendingRequest(runId);
        assertThat(output()).contains("--runs-dir " + runs + " resume " + runId + " --approve " + first);

        assertThat(sdlc("resume", runId, "--approve", first, "--as", "alice")).isEqualTo(SdlcCli.EXIT_AWAITING_HUMAN);
        WorkflowRun resumed = store().load(runId);
        assertThat(resumed.pendingHumanRequests()).isNotEmpty().extracting(HumanRequest::id).doesNotContain(first);
        assertThat(resumed.setting("reviewer", null)).isEqualTo("deferred");
        assertThat(humanDecisions(runId)).isNotEmpty().allSatisfy(e -> assertThat(e.text("reviewer")).isEqualTo("alice"));
        assertThat(output()).doesNotContain("scenario-reviewer");

        assertThat(sdlc("cancel", runId, "--reason", "test")).isEqualTo(SdlcCli.EXIT_HALTED);
        assertThat(store().load(runId).status()).isEqualTo(RunStatus.HALTED);
        assertThat(humanDecisions(runId)).noneMatch(e -> "scenario-reviewer".equals(e.text("reviewer")));
        assertWorkspaceRestored(runId);
    }

    @Test
    void switchingAHumanReviewedRunToTheScriptedReviewerMustBeConfirmed() throws Exception {
        assertThat(sdlc("run", "brownfield", "--verification", "static", "--reviewer", "deferred"))
                .isEqualTo(SdlcCli.EXIT_AWAITING_HUMAN);
        String runId = onlyRunId();
        String first = firstPendingRequest(runId);
        int eventsBefore = store().events(runId).size();
        output();

        assertThat(sdlc("resume", runId, "--approve", first, "--reviewer", "scripted")).isEqualTo(CommandLine.ExitCode.USAGE);
        assertThat(errors()).contains("--allow-scripted-reviewer");
        assertThat(store().load(runId).setting("reviewer", null)).isEqualTo("deferred");
        assertThat(store().load(runId).pendingHumanRequests()).extracting(HumanRequest::id).contains(first);
        assertThat(store().events(runId)).hasSize(eventsBefore);

        assertThat(sdlc("resume", runId, "--approve", first, "--reviewer", "robot")).isEqualTo(CommandLine.ExitCode.USAGE);
        assertThat(errors()).contains("unknown --reviewer 'robot'");
        // A switch is only recorded once the decisions were accepted.
        assertThat(sdlc("resume", runId, "--approve", "hr-unknown", "--reviewer", "interactive")).isEqualTo(CommandLine.ExitCode.USAGE);
        assertThat(errors()).contains("unknown request hr-unknown");
        assertThat(output()).doesNotContain("changed from");
        assertThat(store().load(runId).setting("reviewer", null)).isEqualTo("deferred");
        assertThat(store().events(runId)).hasSize(eventsBefore);

        assertThat(sdlc("resume", runId, "--approve", first, "--as", "alice", "--reviewer", "scripted", "--allow-scripted-reviewer"))
                .isEqualTo(SdlcCli.EXIT_HALTED);
        assertThat(output()).contains("changed from deferred to scripted");
        assertThat(store().load(runId).setting("reviewer", null)).isEqualTo("scripted");
        assertThat(humanDecisions(runId)).extracting(e -> e.text("reviewer")).startsWith("alice").contains("scenario-reviewer");
    }

    @Test
    void clarificationAnswersAreAttributedToTheExplicitReviewer() throws Exception {
        assertThat(sdlc("run", "ambiguous", "--verification", "static", "--reviewer", "deferred"))
                .isEqualTo(SdlcCli.EXIT_AWAITING_HUMAN);
        String runId = onlyRunId();
        HumanRequest clarification = store().load(runId).pendingHumanRequests().getFirst();
        assertThat(clarification.kind()).isEqualTo(HumanRequest.Kind.CLARIFICATION);
        Path answers = new SdlcCli().repository().resolve("scenarios/ambiguous/answers.yaml");

        sdlc("resume", runId, "--answers", answers.toString(), "--as", "carol");

        assertThat(humanDecisions(runId)).filteredOn(e -> clarification.id().equals(e.text("request")))
                .singleElement().satisfies(e -> assertThat(e.text("reviewer")).isEqualTo("carol"));
    }

    @Test
    void aReviewerCanRequestChangesWhenResuming() throws Exception {
        assertThat(sdlc("run", "brownfield", "--verification", "static", "--reviewer", "deferred"))
                .isEqualTo(SdlcCli.EXIT_AWAITING_HUMAN);
        String runId = onlyRunId();
        HumanRequest first = store().load(runId).pendingHumanRequests().getFirst();

        assertThat(sdlc("resume", runId, "--request-changes", first.id(), "--as", "bob")).isEqualTo(CommandLine.ExitCode.USAGE);
        assertThat(errors()).contains("--comment");

        // The recorded provider has no second design recording, so the re-run design stops the run safely.
        assertThat(sdlc("resume", runId, "--request-changes", first.id(), "--comment", "store no fingerprints", "--as", "bob"))
                .isEqualTo(SdlcCli.EXIT_HALTED);
        assertThat(humanDecisions(runId)).anySatisfy(e -> {
            assertThat(e.text("request")).isEqualTo(first.id());
            assertThat(e.text("decision")).isEqualTo("REQUEST_CHANGES");
            assertThat(e.text("reviewer")).isEqualTo("bob");
            assertThat(e.text("changeTarget")).isEqualTo(first.taskId());
        });
        assertThat(store().load(runId).status()).isEqualTo(RunStatus.HALTED);
        assertWorkspaceRestored(runId);
    }
}
