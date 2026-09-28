package com.example.sdlc.verify;

import java.time.Duration;
import java.util.Objects;

/**
 * Result of an external command. {@code output} is stdout and stderr interleaved, possibly cut down to its
 * most recent part. {@code exitCode} is -1 when the process could not be reaped after a timeout.
 */
public record CommandResult(int exitCode, String output, Duration duration, boolean timedOut) {

    public CommandResult {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(duration, "duration");
    }
}
