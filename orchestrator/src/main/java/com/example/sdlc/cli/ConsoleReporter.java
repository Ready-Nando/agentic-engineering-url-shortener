package com.example.sdlc.cli;

import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.example.sdlc.engine.ExecutionEvent;
import com.example.sdlc.engine.WorkflowRun;
import com.example.sdlc.plan.TaskSpec;

/**
 * Live, human-readable rendering of the event stream. Events arrive on the engine's coordinator thread in
 * the order transitions were applied, so the output is an accurate narrative of the run.
 */
final class ConsoleReporter implements Consumer<ExecutionEvent> {

    private final PrintStream out;
    private final boolean verbose;
    private final Style style;
    private final Supplier<WorkflowRun> run;
    private Instant start;

    ConsoleReporter(PrintStream out, boolean verbose, Style style, Supplier<WorkflowRun> run) {
        this.out = out;
        this.verbose = verbose;
        this.style = style;
        this.run = run;
    }

    @Override
    public void accept(ExecutionEvent e) {
        if (start == null) {
            start = e.at();
        }
        String task = e.taskId() == null ? "" : style.bold(pad(e.taskId(), 16)) + " ";
        switch (e.type()) {
            case RUN_STARTED, RUN_RESUMED -> {
                start = e.at();
                print(style.cyan("══ " + e.message() + "  (plan v" + e.data().get("planVersion") + ", " + e.data().get("tasks") + " tasks)"));
            }
            case PLAN_REVISED -> {
                print(style.cyan("◆ " + e.message()));
                if (e.data().get("rationale") != null) {
                    print("    rationale: " + e.data().get("rationale"));
                }
                printPlan();
            }
            case TASK_STARTED -> print("▶ " + task + e.message() + style.dim("  attempt " + e.attempt() + "/" + e.data().get("maxAttempts")
                    + "  gen " + e.data().get("generation") + "  in flight: " + e.data().get("inFlight")));
            case TASK_SUCCEEDED -> print(style.green("✔ ") + task + e.message() + style.dim("  (" + e.data().get("durationMs") + " ms on "
                    + e.data().get("thread") + ")"));
            case TASK_FAILED -> print(style.red("✖ " + task + "FAILED: " + e.message()));
            case ATTEMPT_FAILED -> {
                print(style.red("✖ ") + task + "attempt " + e.attempt() + " failed [" + e.data().get("kind") + "]: " + e.message());
                details(e);
            }
            case ATTEMPT_DISCARDED -> print(style.yellow("  ↺ ") + task + e.message());
            case RETRY_SCHEDULED -> print(style.yellow("  ↻ ") + task + e.message());
            case FALLBACK_ACTIVATED -> print(style.yellow("  ⤷ ") + task + e.message());
            case REWORK_REQUESTED -> print(style.yellow("  ⟲ ") + task + e.message());
            case GATE_PASSED -> print("  " + style.green("✓") + " " + task + style.dim(e.data().get("phase") + " gate ") + e.message());
            case GATE_FAILED -> {
                print("  " + style.red("✗") + " " + task + style.dim(e.data().get("phase") + " gate ") + e.message());
                details(e);
            }
            case POLICY_EVALUATED -> {
                String decision = String.valueOf(e.data().get("decision"));
                String line = "  ⚖ " + task + "policy " + decision + ": " + e.data().get("files");
                print(decision.equals("ALLOW") ? line : decision.equals("DENY") ? style.red(line) : style.yellow(line));
                if (e.data().get("findings") instanceof List<?> findings) {
                    findings.forEach(f -> print(style.dim("      " + f)));
                }
            }
            case CHANGESET_APPLIED -> print("  ✎ " + task + e.message() + style.dim(" " + e.data().get("files")));
            case CHANGESET_ROLLED_BACK -> print(style.yellow("  ⟲ ") + task + e.message()
                    + (e.data().get("cause") == null ? "" : style.dim("  [" + e.data().get("cause") + "]")));
            case CHANGESET_RECOVERED -> print(style.yellow("  ⟲ ") + task + e.message()
                    + style.dim(Boolean.TRUE.equals(e.data().get("diskTouched")) ? "  [restored " + e.data().get("restored") + "]"
                            : "  [already rolled back on disk]"));
            case HUMAN_INPUT_REQUESTED -> print(style.yellow("  ⏸ ") + task + "human checkpoint " + e.data().get("request") + " ["
                    + e.data().get("kind") + "]: " + e.message());
            case HUMAN_INPUT_CANCELLED -> print(style.dim("  ⊘ ") + task + "human checkpoint " + e.data().get("request")
                    + " cancelled: " + e.data().get("reason"));
            case HUMAN_INPUT_RECEIVED -> print(style.yellow("  👤 ") + task + e.message()
                    + (e.data().get("answers") == null ? "" : " " + e.data().get("answers")));
            case ARTIFACT_UNCHANGED -> print(style.dim("  = ") + task + style.dim(e.message()));
            case ARTIFACT_PUBLISHED -> {
                if (verbose) {
                    print(style.dim("  + " + task + e.message() + " <- " + e.data().get("inputs")));
                }
            }
            case ARTIFACT_RETRACTED, ARTIFACT_REJECTED -> print(style.dim("  - ") + task + style.dim(e.message()));
            case TASK_INVALIDATED -> print(style.yellow("  ⊘ ") + task + "invalidated: " + e.message());
            case TASK_BLOCKED, TASK_CANCELLED -> print(style.dim("  ⊘ " + task + e.type().name().substring(5).toLowerCase() + ": " + e.message()));
            case SAFE_STOP_INITIATED -> print(style.red("■ SAFE STOP: " + e.message()));
            case WORKSPACE_RESTORED -> print(style.yellow("  ⟲ " + e.message()));
            case RUN_PAUSED -> print(style.yellow("⏸ " + e.message()));
            case RUN_COMPLETED -> print(style.green("■ " + e.message()));
            case RUN_HALTED -> print(style.red("■ " + e.message()));
            case TASK_READY, GATE_WAITING -> {
                if (verbose) {
                    print(style.dim("  · " + task + e.type() + " " + e.message()));
                }
            }
        }
    }

    private void printPlan() {
        WorkflowRun current = run.get();
        if (current == null) {
            return;
        }
        for (TaskSpec spec : current.plan().topologicalOrder()) {
            print(style.dim("    " + pad(spec.id(), 18) + pad(spec.stage().name(), 15) + pad(spec.capability(), 20)
                    + (spec.dependsOn().isEmpty() ? "" : "<- " + String.join(", ", spec.dependsOn()))));
        }
    }

    private void details(ExecutionEvent e) {
        if (e.data().get("details") instanceof List<?> details) {
            details.stream().limit(verbose ? 50 : 6).forEach(d -> print(style.dim("      " + d)));
            if (!verbose && details.size() > 6) {
                print(style.dim("      ... " + (details.size() - 6) + " more (use --verbose or `sdlc events`)"));
            }
        }
    }

    private void print(String line) {
        Duration elapsed = start == null ? Duration.ZERO : Duration.between(start, Instant.now());
        out.println(style.dim(String.format("[%6.1fs] ", elapsed.toMillis() / 1000.0)) + line);
        out.flush();
    }

    static String pad(String text, int width) {
        return text.length() >= width ? text + " " : text + " ".repeat(width - text.length());
    }
}
