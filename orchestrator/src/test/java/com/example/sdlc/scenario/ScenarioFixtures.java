package com.example.sdlc.scenario;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import com.example.sdlc.engine.EventType;
import com.example.sdlc.engine.ExecutionEvent;
import com.example.sdlc.engine.WorkflowRun;
import com.example.sdlc.store.RunStore;

/** Runs the real scenarios from the repository's scenarios/ directory against a temporary runs directory. */
final class ScenarioFixtures {

    static final Path REPOSITORY = locateRepository();

    private ScenarioFixtures() {
    }

    static ScenarioRunner runner(Path runs) {
        return new ScenarioRunner(REPOSITORY, new RunStore(runs), Clock.systemUTC(), new PrintStream(OutputStream.nullOutputStream()));
    }

    static Scenario scenario(String id) {
        return Scenario.load(REPOSITORY.resolve("scenarios").resolve(id));
    }

    static ScenarioRunner.Options options(boolean realBuild) {
        return new ScenarioRunner.Options(ScenarioRunner.ReviewerMode.SCRIPTED, realBuild, 4, Duration.ofMinutes(5));
    }

    static Function<WorkflowRun, Consumer<ExecutionEvent>> quiet() {
        return run -> event -> { };
    }

    static long count(List<ExecutionEvent> events, EventType type, String taskId) {
        return events.stream().filter(e -> e.type() == type && (taskId == null || taskId.equals(e.taskId()))).count();
    }

    private static Path locateRepository() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("scenarios")) && Files.isDirectory(dir.resolve("shortener"))) {
                return dir;
            }
        }
        throw new IllegalStateException("repository root not found");
    }
}
