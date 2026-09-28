package com.example.sdlc.scenario;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import com.example.sdlc.capability.StandardCapabilities;
import com.example.sdlc.codebase.ArchitectureRules;
import com.example.sdlc.codebase.CodebaseIndexer;
import com.example.sdlc.engine.Capability;
import com.example.sdlc.engine.EventLog;
import com.example.sdlc.engine.ExecutionEvent;
import com.example.sdlc.engine.WorkflowEngine;
import com.example.sdlc.engine.WorkflowRun;
import com.example.sdlc.gates.StandardGates;
import com.example.sdlc.human.HumanGateway;
import com.example.sdlc.policy.ChangePolicy;
import com.example.sdlc.reasoning.RecordedReasoningProvider;
import com.example.sdlc.report.OutcomeWriter;
import com.example.sdlc.store.RunStore;
import com.example.sdlc.verify.MavenBuildVerifier;
import com.example.sdlc.verify.ProcessCommandRunner;
import com.example.sdlc.workspace.Workspace;

/**
 * Composition root for a governed run: creates the isolated workspace, wires provider, capabilities, gates,
 * policy and reviewer into the engine, persists state and writes the outcome. Also resumes paused runs.
 */
public final class ScenarioRunner {

    public enum ReviewerMode { SCRIPTED, INTERACTIVE, DEFERRED }

    public record Options(ReviewerMode reviewer, boolean realBuild, int parallelism, Duration buildTimeout) {
        public static Options defaults() {
            return new Options(ReviewerMode.SCRIPTED, true, 4, Duration.ofMinutes(10));
        }
    }

    public record Result(WorkflowRun run, Path directory, Path outcome, List<ExecutionEvent> events) {
    }

    private static final DateTimeFormatter RUN_ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final Path repository;
    private final RunStore store;
    private final Clock clock;
    private final PrintStream console;

    public ScenarioRunner(Path repository, RunStore store, Clock clock, PrintStream console) {
        this.repository = repository;
        this.store = store;
        this.clock = clock;
        this.console = console;
    }

    public Result start(Scenario scenario, Options options, Function<WorkflowRun, Consumer<ExecutionEvent>> listener) {
        String runId = uniqueRunId(scenario.id());
        Path runDirectory = store.directory(runId);
        Path module = repository.resolve(scenario.targetModule());
        if (!Files.isRegularFile(module.resolve("pom.xml"))) {
            throw new IllegalArgumentException("target module not found: " + module);
        }
        Map<Path, String> wrapper = new LinkedHashMap<>();
        for (String file : List.of("mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties")) {
            if (Files.isRegularFile(repository.resolve(file))) {
                wrapper.put(repository.resolve(file), file);
            }
        }
        Workspace workspace = Workspace.create(module, wrapper, runDirectory);
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("scenarioDirectory", repository.relativize(scenario.directory()).toString());
        settings.put("provider", new RecordedReasoningProvider(scenario.directory()).name());
        settings.put("verification", options.realBuild() ? "build" : "static");
        settings.put("patchPrefix", scenario.targetModule() + "/");
        settings.put("reviewer", options.reviewer().name().toLowerCase());
        settings.put("scenarioKind", scenario.kind());
        WorkflowRun run = new WorkflowRun(runId, scenario.id(), scenario.title(), scenario.requirement(), clock.instant(),
                StandardCapabilities.bootstrapPlan(), settings);
        store.save(run);
        return execute(run, workspace, scenario, options, List.of(), listener);
    }

    public Result resume(String runId, ReviewerMode reviewer, int parallelism, Duration buildTimeout,
                         Function<WorkflowRun, Consumer<ExecutionEvent>> listener) {
        WorkflowRun run = store.load(runId);
        Path runDirectory = store.directory(runId);
        Workspace workspace = Workspace.open(runDirectory.resolve("workspace"), runDirectory.resolve("baseline"));
        Scenario scenario = Scenario.load(repository.resolve(run.setting("scenarioDirectory", "scenarios/" + run.scenarioId())));
        Options options = new Options(reviewer, "build".equals(run.setting("verification", "build")), parallelism, buildTimeout);
        return execute(run, workspace, scenario, options, store.events(runId), listener);
    }

    /** Aborts a paused (or interrupted) run: compensates its changes and records who stopped it and why. */
    public Result cancel(String runId, String reason, Function<WorkflowRun, Consumer<ExecutionEvent>> listener) {
        WorkflowRun run = store.load(runId);
        Path runDirectory = store.directory(runId);
        Workspace workspace = Workspace.open(runDirectory.resolve("workspace"), runDirectory.resolve("baseline"));
        Scenario scenario = Scenario.load(repository.resolve(run.setting("scenarioDirectory", "scenarios/" + run.scenarioId())));
        EventLog log = new EventLog(run.id(), clock, store.events(runId));
        log.subscribe(store::append);
        log.subscribe(listener.apply(run));
        engine(scenario, new Options(ReviewerMode.DEFERRED, false, 1, Duration.ofMinutes(1))).stop(run, workspace, log, store::save, reason);
        Path outcome = OutcomeWriter.write(run, workspace, log.events(), runDirectory);
        return new Result(run, runDirectory, outcome, log.events());
    }

    public RunStore store() {
        return store;
    }

    private Result execute(WorkflowRun run, Workspace workspace, Scenario scenario, Options options,
                           List<ExecutionEvent> previousEvents, Function<WorkflowRun, Consumer<ExecutionEvent>> listener) {
        WorkflowEngine engine = engine(scenario, options);
        EventLog log = new EventLog(run.id(), clock, previousEvents);
        log.subscribe(store::append);
        log.subscribe(listener.apply(run));
        engine.execute(run, workspace, log, store::save);
        Path directory = store.directory(run.id());
        Path outcome = OutcomeWriter.write(run, workspace, log.events(), directory);
        return new Result(run, directory, outcome, log.events());
    }

    private WorkflowEngine engine(Scenario scenario, Options options) {
        ChangePolicy policy = new ChangePolicy();
        MavenBuildVerifier verifier = options.realBuild()
                ? new MavenBuildVerifier(new ProcessCommandRunner(), options.buildTimeout())
                : null;
        List<Capability> capabilities = StandardCapabilities.create(new RecordedReasoningProvider(scenario.directory()), verifier, policy);
        StandardGates gates = new StandardGates(new CodebaseIndexer(), new ArchitectureRules());
        return new WorkflowEngine(capabilities, gates.all(), policy, reviewer(options.reviewer(), scenario),
                clock, new WorkflowEngine.Settings(options.parallelism(), 8, 2));
    }

    private HumanGateway reviewer(ReviewerMode mode, Scenario scenario) {
        return switch (mode) {
            case SCRIPTED -> new ScriptedReviewer(scenario.reviewer());
            case INTERACTIVE -> new InteractiveReviewer(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
                    console, System.getProperty("user.name", "reviewer"));
            case DEFERRED -> HumanGateway.DEFERRED;
        };
    }

    private String uniqueRunId(String scenarioId) {
        String base = scenarioId + "-" + RUN_ID_TIME.format(clock.instant());
        String candidate = base;
        for (int i = 2; Files.exists(store.directory(candidate)); i++) {
            candidate = base + "-" + i;
        }
        return candidate;
    }
}
