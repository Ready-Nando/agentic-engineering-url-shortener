package com.example.sdlc.verify;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Process-execution boundary, so build verification can be exercised without spawning Maven. */
@FunctionalInterface
public interface CommandRunner {

    /**
     * Runs {@code command} directly (no shell) and waits at most {@code timeout} for it to finish.
     *
     * @throws java.io.UncheckedIOException if the command cannot be started at all
     */
    CommandResult run(List<String> command, Path workingDirectory, Duration timeout);
}
