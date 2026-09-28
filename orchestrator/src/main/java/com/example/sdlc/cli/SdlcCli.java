package com.example.sdlc.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

import com.example.sdlc.Json;
import com.example.sdlc.artifact.Artifact;
import com.example.sdlc.capability.StandardCapabilities;
import com.example.sdlc.engine.Capability;
import com.example.sdlc.engine.ExecutionEvent;
import com.example.sdlc.engine.RunStatus;
import com.example.sdlc.engine.TaskState;
import com.example.sdlc.engine.WorkflowRun;
import com.example.sdlc.gates.StandardGates;
import com.example.sdlc.human.HumanRequest;
import com.example.sdlc.human.HumanResponse;
import com.example.sdlc.metrics.AggregateMetrics;
import com.example.sdlc.metrics.ReliabilityMetrics;
import com.example.sdlc.plan.TaskSpec;
import com.example.sdlc.policy.ChangePolicy;
import com.example.sdlc.policy.PolicyRule;
import com.example.sdlc.report.EngineeringSummary;
import com.example.sdlc.report.Lineage;
import com.example.sdlc.scenario.Scenario;
import com.example.sdlc.scenario.ScenarioRunner;
import com.example.sdlc.store.RunStore;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import tools.jackson.databind.JsonNode;

@Command(name = "sdlc", mixinStandardHelpOptions = true, version = "sdlc-orchestrator 0.1.0",
        description = "Governed, dependency-graph based SDLC orchestration for the URL shortener.",
        subcommands = {SdlcCli.Scenarios.class, SdlcCli.Run.class, SdlcCli.Resume.class, SdlcCli.Status.class,
                SdlcCli.LineageCommand.class, SdlcCli.Events.class, SdlcCli.Metrics.class, SdlcCli.Governance.class,
                SdlcCli.Runs.class, SdlcCli.Cancel.class})
public final class SdlcCli implements Runnable {

    static final int EXIT_HALTED = 2;
    static final int EXIT_AWAITING_HUMAN = 3;

    @Option(names = "--repo", description = "Repository root (default: auto-detected from the working directory).")
    Path repo;

    @Option(names = "--runs-dir", description = "Where runs are stored (default: <repo>/runs).")
    Path runsDir;

    private final PrintStream out = System.out;
    private final Style style = Style.detect();

    public static void main(String[] args) {
        System.exit(new CommandLine(new SdlcCli()).execute(args));
    }

    @Override
    public void run() {
        new CommandLine(this).usage(out);
    }

    Path repository() {
        if (repo != null) {
            return repo.toAbsolutePath().normalize();
        }
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("scenarios")) && Files.isDirectory(dir.resolve("shortener"))) {
                return dir;
            }
        }
        throw new CommandLine.ParameterException(new CommandLine(this),
                "cannot find the repository root (a directory with scenarios/ and shortener/); pass --repo");
    }

    RunStore store() {
        return new RunStore(runsDir != null ? runsDir.toAbsolutePath() : repository().resolve("runs"));
    }

    ScenarioRunner runner() {
        return new ScenarioRunner(repository(), store(), Clock.systemUTC(), out);
    }

    int report(ScenarioRunner.Result result) {
        WorkflowRun run = result.run();
        out.println();
        printStatus(run, false);
        ReliabilityMetrics metrics = ReliabilityMetrics.of(result.events());
        out.println();
        out.println(style.bold("Reliability") + "  tasks ok " + metrics.tasksSucceeded() + ", failed " + metrics.tasksFailed()
                + " · attempts " + metrics.attempts() + " · retries " + metrics.retries() + " · fallbacks " + metrics.fallbacks()
                + " · reworks " + metrics.reworks() + " · rollbacks " + metrics.rollbacks() + "/" + metrics.changeSetsApplied()
                + (metrics.rollbacks() == 0 ? "" : " " + metrics.rollbacksByCause())
                + " · MTTR " + EngineeringSummary.format(metrics.meanTimeToRecovery())
                + " · e2e " + EngineeringSummary.format(metrics.endToEnd()) + " · max concurrency " + metrics.maxConcurrency());
        Path directory = displayPath(result.directory());
        out.println(style.bold("Outcome") + "      " + directory.resolve("outcome/ENGINEERING_SUMMARY.md"));
        if (Files.exists(result.outcome().resolve("changes.patch"))) {
            out.println("             " + directory.resolve("outcome/changes.patch") + "  (apply with: git apply " + directory.resolve("outcome/changes.patch") + ")");
        }
        if (Files.exists(result.outcome().resolve("abandoned-changes.patch"))) {
            out.println("             " + directory.resolve("outcome/abandoned-changes.patch") + "  (compensated; kept for forensics)");
        }
        out.println("             " + directory.resolve("events.jsonl") + " · " + directory.resolve("artifacts/"));
        return switch (run.status()) {
            case COMPLETED -> 0;
            case AWAITING_HUMAN -> EXIT_AWAITING_HUMAN;
            default -> EXIT_HALTED;
        };
    }

    /**
     * Relative to the repository when inside it (commands are run from there), otherwise absolute - also when
     * no repository can be found, e.g. {@code status} with {@code --runs-dir} from elsewhere.
     */
    Path displayPath(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path root;
        try {
            root = repository();
        } catch (CommandLine.ParameterException e) {
            return absolute;
        }
        return absolute.startsWith(root) ? root.relativize(absolute) : absolute;
    }

    /** A path as a shell argument in a copy-and-paste hint. */
    static String shellArgument(Path path) {
        String text = path.toString();
        return text.matches("[\\w./:@%+=,-]+") ? text : "'" + text.replace("'", "'\\''") + "'";
    }

    void printStatus(WorkflowRun run, boolean verbose) {
        String verdict = run.verdict() == null ? "" : "  verdict " + (run.verdict().equals("READY") ? style.green(run.verdict()) : style.red(run.verdict()));
        out.println(style.bold("Run " + run.id()) + "  " + colour(run.status()) + verdict);
        out.println("  " + run.title());
        if (run.statusReason() != null) {
            out.println("  " + style.dim(run.statusReason()));
        }
        out.println(style.bold("Plan v" + run.plan().version()) + style.dim("  (" + run.plan().rationale() + ")"));
        for (TaskSpec spec : run.plan().topologicalOrder()) {
            TaskState state = run.task(spec.id());
            out.println("  " + ConsoleReporter.pad(spec.id(), 18) + ConsoleReporter.pad(spec.stage().name(), 15)
                    + ConsoleReporter.pad(spec.capability() + (state.onFallback() ? "→" + spec.fallback() : ""), 24)
                    + colour(state.status()) + " ".repeat(Math.max(1, 16 - state.status().name().length()))
                    + style.dim("exec " + state.executions() + " gen " + state.generation()
                    + (spec.dependsOn().isEmpty() ? "" : "  <- " + String.join(", ", spec.dependsOn()))));
            if (verbose && state.detail() != null) {
                out.println("  " + " ".repeat(18) + style.dim(state.detail()));
            }
        }
        for (HumanRequest request : run.pendingHumanRequests()) {
            out.println();
            out.println(style.yellow("  ⏸ " + request.id() + " [" + request.kind() + "] task " + request.taskId() + ": " + request.title()));
            request.details().forEach(d -> out.println("      " + d));
            for (HumanRequest.Question question : request.questions()) {
                out.println("      " + style.bold(question.id()) + ": " + question.text());
                if (question.rationale() != null) {
                    out.println("          " + style.dim(question.rationale()));
                }
                question.options().forEach(o -> out.println("          - " + o.id() + ": " + o.label()
                        + (o.consequence() == null || o.consequence().isBlank() ? "" : style.dim(" (" + o.consequence() + ")"))));
            }
        }
        if (!run.pendingHumanRequests().isEmpty()) {
            String scenarioDir = run.setting("scenarioDirectory", "scenarios/" + run.scenarioId());
            boolean clarification = run.pendingHumanRequests().stream().anyMatch(r -> r.kind() == HumanRequest.Kind.CLARIFICATION);
            out.println();
            String sdlc = "./sdlc" + (runsDir == null ? "" : " --runs-dir " + shellArgument(displayPath(runsDir)));
            out.println("  Resume with, for example:");
            out.println(clarification
                    ? "    " + sdlc + " resume " + run.id() + " --answers " + scenarioDir + "/answers.yaml"
                    : "    " + sdlc + " resume " + run.id() + " --approve " + run.pendingHumanRequests().getFirst().id());
            if (!clarification) {
                out.println("    (or --reject / --request-changes <request> --comment \"...\"; the run keeps its "
                        + run.setting("reviewer", "deferred") + " reviewer)");
            }
        }
    }

    String colour(Enum<?> status) {
        String name = status.name();
        return switch (name) {
            case "SUCCEEDED", "COMPLETED" -> style.green(name);
            case "FAILED", "HALTED", "BLOCKED" -> style.red(name);
            case "AWAITING_HUMAN", "ROLLED_BACK" -> style.yellow(name);
            default -> style.dim(name);
        };
    }

    java.util.function.Function<WorkflowRun, java.util.function.Consumer<ExecutionEvent>> reporter(boolean verbose) {
        return run -> new ConsoleReporter(out, verbose, style, () -> run);
    }

    // ---------------------------------------------------------------- commands

    @Command(name = "scenarios", description = "List the available scenarios.")
    static final class Scenarios implements Callable<Integer> {
        @ParentCommand
        SdlcCli cli;

        @Override
        public Integer call() {
            for (Scenario scenario : Scenario.discover(cli.repository().resolve("scenarios"))) {
                cli.out.println(cli.style.bold(ConsoleReporter.pad(scenario.id(), 14)) + ConsoleReporter.pad(scenario.kind(), 12) + scenario.title());
                if (scenario.description() != null) {
                    scenario.description().strip().lines().forEach(l -> cli.out.println(" ".repeat(26) + cli.style.dim(l)));
                }
            }
            return 0;
        }
    }

    @Command(name = "run", description = "Run a scenario through the governed SDLC workflow.")
    static final class Run implements Callable<Integer> {
        @ParentCommand
        SdlcCli cli;

        @Parameters(index = "0", description = "Scenario id (see `sdlc scenarios`).")
        String scenario;

        @Option(names = "--reviewer", defaultValue = "scripted", description = "scripted | interactive | deferred (default: ${DEFAULT-VALUE}).")
        String reviewer;

        @Option(names = "--verification", defaultValue = "build",
                description = "build = run the real test suite in the workspace; static = degraded checks only (default: ${DEFAULT-VALUE}).")
        String verification;

        @Option(names = "--parallelism", defaultValue = "4", description = "Maximum concurrently executing tasks.")
        int parallelism;

        @Option(names = "--verbose", description = "Show every event.")
        boolean verbose;

        @Override
        public Integer call() {
            Scenario definition = Scenario.load(cli.repository().resolve("scenarios").resolve(scenario));
            ScenarioRunner.Options options = new ScenarioRunner.Options(mode(reviewer), !"static".equalsIgnoreCase(verification),
                    parallelism, Duration.ofMinutes(10));
            cli.out.println(cli.style.bold(definition.title()) + cli.style.dim("  [" + definition.kind() + "]"));
            definition.requirement().strip().lines().forEach(l -> cli.out.println(cli.style.dim("  > " + l)));
            cli.out.println();
            return cli.report(cli.runner().start(definition, options, cli.reporter(verbose)));
        }
    }

    @Command(name = "resume", description = "Record human decisions for a paused run and continue it.")
    static final class Resume implements Callable<Integer> {
        @ParentCommand
        SdlcCli cli;

        @Parameters(index = "0", description = "Run id.")
        String runId;

        @Option(names = "--answers", description = "YAML file with clarification answers: {reviewer: name, answers: {Q1: option}}.")
        Path answers;

        @Option(names = "--approve", description = "Approve the given pending request id.")
        List<String> approve = new ArrayList<>();

        @Option(names = "--reject", description = "Reject the given pending request id.")
        List<String> reject = new ArrayList<>();

        @Option(names = "--request-changes", description = "Request changes on the given pending request id; --comment says what should change.")
        List<String> requestChanges = new ArrayList<>();

        @Option(names = "--target", description = "Task whose output should change for --request-changes (default: the task that raised the request).")
        String target;

        @Option(names = "--comment", defaultValue = "", description = "Comment recorded with approvals/rejections/change requests.")
        String comment;

        @Option(names = "--as", description = "Reviewer name recorded in the audit log (default: OS user).")
        String reviewerName;

        @Option(names = "--reviewer", description = "scripted | interactive | deferred for checkpoints reached after resuming "
                + "(default: the mode the run was started with; a change is recorded with the run).")
        String reviewer;

        @Option(names = "--allow-scripted-reviewer", description = "Confirm handing a human-reviewed run's remaining checkpoints to the scenario script.")
        boolean allowScriptedReviewer;

        @Option(names = "--parallelism", defaultValue = "4")
        int parallelism;

        @Option(names = "--verbose")
        boolean verbose;

        @Override
        public Integer call() throws IOException {
            RunStore store = cli.store();
            WorkflowRun run = store.load(runId);
            if (run.status().isFinal()) {
                cli.out.println("run " + runId + " already finished: " + run.status());
                return run.status() == RunStatus.COMPLETED ? 0 : EXIT_HALTED;
            }
            if (!requestChanges.isEmpty() && comment.isBlank()) {
                throw new CommandLine.ParameterException(new CommandLine(this), "--request-changes needs a --comment saying what should change");
            }
            if (target != null && requestChanges.isEmpty()) {
                throw new CommandLine.ParameterException(new CommandLine(this), "--target only applies to --request-changes");
            }
            ScenarioRunner.ReviewerMode mode = reviewerMode(run);
            String who = reviewerName != null ? reviewerName : System.getProperty("user.name", "reviewer");
            if (answers != null) {
                JsonNode file = Json.YAML.readTree(Files.readString(answers));
                Map<String, String> values = new LinkedHashMap<>();
                file.path("answers").properties().forEach(e -> values.put(e.getKey(), e.getValue().asString()));
                // An explicit --as wins over the name written in the answers file.
                String answeredBy = reviewerName != null ? reviewerName : file.path("reviewer").asString(who);
                for (HumanRequest request : run.pendingHumanRequests()) {
                    if (request.kind() == HumanRequest.Kind.CLARIFICATION) {
                        List<String> missing = request.questions().stream().map(HumanRequest.Question::id).filter(id -> !values.containsKey(id)).toList();
                        if (!missing.isEmpty()) {
                            throw new CommandLine.ParameterException(new CommandLine(this), "answers file does not answer " + missing);
                        }
                        answer(run, request.id(), HumanResponse.answer(answeredBy, values));
                    }
                }
            }
            approve.forEach(id -> answer(run, id, HumanResponse.approve(who, comment)));
            reject.forEach(id -> answer(run, id, HumanResponse.reject(who, comment)));
            for (String id : requestChanges) {
                String changeTarget = target != null ? target : run.humanRequest(id).map(HumanRequest::taskId).orElse(null);
                answer(run, id, HumanResponse.requestChanges(who, comment, changeTarget));
            }
            String saved = run.setting("reviewer", "deferred");
            if (!name(mode).equals(saved)) {
                run.updateSetting("reviewer", name(mode));
                cli.out.println(cli.style.yellow("Reviewer for run " + runId + " changed from " + saved + " to " + name(mode)
                        + "; recorded in the run settings."));
            }
            store.save(run);
            return cli.report(cli.runner().resume(runId, mode, parallelism, Duration.ofMinutes(10), cli.reporter(verbose)));
        }

        /**
         * Later checkpoints are decided by the reviewer the run was started with (deferred when unknown). A
         * scripted reviewer approves whatever its scenario says, so it may only replace a human reviewer when
         * explicitly confirmed. Only validates; the change is recorded once the decisions were accepted.
         */
        private ScenarioRunner.ReviewerMode reviewerMode(WorkflowRun run) {
            ScenarioRunner.ReviewerMode saved;
            try {
                saved = mode(run.setting("reviewer", "deferred"));
            } catch (IllegalArgumentException e) {
                saved = ScenarioRunner.ReviewerMode.DEFERRED;
            }
            ScenarioRunner.ReviewerMode requested;
            try {
                requested = reviewer == null ? saved : mode(reviewer);
            } catch (IllegalArgumentException e) {
                throw new CommandLine.ParameterException(new CommandLine(this), "unknown --reviewer '" + reviewer
                        + "' (expected scripted, interactive or deferred)");
            }
            if (requested == ScenarioRunner.ReviewerMode.SCRIPTED && saved != requested && !allowScriptedReviewer) {
                throw new CommandLine.ParameterException(new CommandLine(this), "run " + runId + " has a " + name(saved)
                        + " reviewer; --reviewer scripted would let the scenario script decide its remaining checkpoints. "
                        + "Omit --reviewer to keep the " + name(saved) + " reviewer, or add --allow-scripted-reviewer to switch deliberately.");
            }
            return requested;
        }

        private void answer(WorkflowRun run, String requestId, HumanResponse response) {
            try {
                run.answer(requestId, response, Instant.now());
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw new CommandLine.ParameterException(new CommandLine(this), e.getMessage());
            }
        }
    }

    @Command(name = "cancel", description = "Safely stop a paused run: cancel its checkpoints and roll back every applied change.")
    static final class Cancel implements Callable<Integer> {
        @ParentCommand
        SdlcCli cli;

        @Parameters(index = "0", description = "Run id.")
        String runId;

        @Option(names = "--reason", required = true, description = "Why the run is being stopped (recorded in the audit log).")
        String reason;

        @Option(names = "--as", description = "Who is stopping the run (default: OS user).")
        String who;

        @Override
        public Integer call() {
            WorkflowRun run = cli.store().load(runId);
            if (run.status().isFinal()) {
                cli.out.println("run " + runId + " already finished: " + run.status());
                return run.status() == RunStatus.COMPLETED ? 0 : EXIT_HALTED;
            }
            String by = who != null ? who : System.getProperty("user.name", "operator");
            return cli.report(cli.runner().cancel(runId, "cancelled by " + by + ": " + reason, cli.reporter(false)));
        }
    }

    @Command(name = "status", description = "Show the plan, task states and pending human checkpoints of a run.")
    static final class Status implements Callable<Integer> {
        @ParentCommand
        SdlcCli cli;

        @Parameters(index = "0")
        String runId;

        @Override
        public Integer call() {
            cli.printStatus(cli.store().load(runId), true);
            return 0;
        }
    }

    @Command(name = "lineage", description = "Show provenance of an artifact: what produced it, what it derived from, what depends on it.")
    static final class LineageCommand implements Callable<Integer> {
        @ParentCommand
        SdlcCli cli;

        @Parameters(index = "0")
        String runId;

        @Parameters(index = "1", arity = "0..1", description = "Artifact key, optionally with @vN. Omit to list artifacts.")
        String artifact;

        @Override
        public Integer call() {
            WorkflowRun run = cli.store().load(runId);
            Lineage lineage = new Lineage(run.artifacts());
            if (artifact == null) {
                run.artifacts().all().forEach(a -> cli.out.println(Lineage.describe(a)));
                return 0;
            }
            Artifact target = lineage.resolve(artifact).orElseThrow(() ->
                    new CommandLine.ParameterException(new CommandLine(this), "no artifact " + artifact));
            cli.out.println(cli.style.bold("Derived from (upstream):"));
            lineage.upstream(target).forEach(l -> cli.out.println("  " + l));
            cli.out.println(cli.style.bold("Derived from it (downstream):"));
            List<String> downstream = lineage.downstream(target);
            (downstream.isEmpty() ? List.of("  (nothing)") : downstream).forEach(l -> cli.out.println("  " + l));
            List<Artifact> history = run.artifacts().history(target.key());
            cli.out.println(cli.style.bold("Versions:"));
            history.forEach(a -> cli.out.println("  " + Lineage.describe(a)));
            return 0;
        }
    }

    @Command(name = "events", description = "Print the audit log of a run.")
    static final class Events implements Callable<Integer> {
        @ParentCommand
        SdlcCli cli;

        @Parameters(index = "0")
        String runId;

        @Option(names = "--type", description = "Only events of this type (e.g. POLICY_EVALUATED).")
        String type;

        @Option(names = "--task", description = "Only events of this task.")
        String task;

        @Option(names = "--json", description = "Print raw JSON lines.")
        boolean json;

        @Override
        public Integer call() {
            for (ExecutionEvent e : cli.store().events(runId)) {
                if ((type != null && !e.type().name().equalsIgnoreCase(type)) || (task != null && !task.equals(e.taskId()))) {
                    continue;
                }
                cli.out.println(json ? Json.MAPPER.writeValueAsString(e)
                        : String.format("%4d %s %-22s %-16s %s", e.seq(), e.at(), e.type(), e.taskId() == null ? "" : e.taskId()
                        + (e.attempt() == null ? "" : "#" + e.attempt()), e.message()));
            }
            return 0;
        }
    }

    @Command(name = "metrics", description = "Reliability metrics for one run, or aggregated over all runs.")
    static final class Metrics implements Callable<Integer> {
        @ParentCommand
        SdlcCli cli;

        @Parameters(index = "0", arity = "0..1")
        String runId;

        @Override
        public Integer call() {
            RunStore store = cli.store();
            if (runId != null) {
                cli.out.println(Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(ReliabilityMetrics.of(store.events(runId))));
                return 0;
            }
            List<ReliabilityMetrics> all = new ArrayList<>();
            for (String id : store.runIds()) {
                ReliabilityMetrics metrics = ReliabilityMetrics.of(store.events(id));
                all.add(metrics);
                cli.out.println(ConsoleReporter.pad(id, 36) + ConsoleReporter.pad(metrics.outcome(), 22)
                        + "retries " + metrics.retries() + ", rollbacks " + metrics.rollbacks() + ", MTTR "
                        + EngineeringSummary.format(metrics.meanTimeToRecovery()) + ", e2e " + EngineeringSummary.format(metrics.endToEnd()));
            }
            cli.out.println();
            cli.out.println(Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(AggregateMetrics.of(all)));
            return 0;
        }
    }

    @Command(name = "governance", description = "Print the policy rules, gates and capability catalogue.")
    static final class Governance implements Callable<Integer> {
        @ParentCommand
        SdlcCli cli;

        @Override
        public Integer call() {
            cli.out.println(cli.style.bold("Change policy rules"));
            for (PolicyRule rule : ChangePolicy.RULES) {
                cli.out.println("  " + ConsoleReporter.pad(rule.id(), 8) + ConsoleReporter.pad(rule.category().name(), 16)
                        + ConsoleReporter.pad(rule.decision().name(), 18) + rule.description());
            }
            cli.out.println(cli.style.bold("Capabilities (mandatory gates are attached whatever a plan says)"));
            for (Capability c : StandardCapabilities.create(null, null, new ChangePolicy())) {
                cli.out.println("  " + ConsoleReporter.pad(c.name(), 24) + ConsoleReporter.pad(c.stage().name(), 16)
                        + (c.plannable() ? "plannable " : "bootstrap ") + c.role() + " "
                        + (c.proposesChanges() ? "changes " + c.scopeRoots() + " " : "")
                        + "entry " + c.entryGates() + " exit " + c.exitGates() + (c.defaultFallback() == null ? "" : " fallback " + c.defaultFallback()));
            }
            cli.out.println(cli.style.bold("Gates") + "  " + String.join(", ", new StandardGates(null, null).all().stream()
                    .map(com.example.sdlc.engine.Gate::name).sorted().toList()) + ", plan-valid (engine)");
            return 0;
        }
    }

    @Command(name = "runs", description = "List persisted runs.")
    static final class Runs implements Callable<Integer> {
        @ParentCommand
        SdlcCli cli;

        @Override
        public Integer call() {
            RunStore store = cli.store();
            for (String id : store.runIds()) {
                WorkflowRun run = store.load(id);
                cli.out.println(ConsoleReporter.pad(id, 36) + ConsoleReporter.pad(run.status().name(), 16)
                        + ConsoleReporter.pad(run.verdict() == null ? "" : run.verdict(), 11) + run.title());
            }
            return 0;
        }
    }

    static ScenarioRunner.ReviewerMode mode(String value) {
        return ScenarioRunner.ReviewerMode.valueOf(value.toUpperCase(Locale.ROOT));
    }

    static String name(ScenarioRunner.ReviewerMode mode) {
        return mode.name().toLowerCase(Locale.ROOT);
    }
}
