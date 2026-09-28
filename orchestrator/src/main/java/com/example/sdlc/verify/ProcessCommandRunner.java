package com.example.sdlc.verify;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * Runs commands as child processes of this JVM. On timeout the whole process tree is killed, so a hung
 * Surefire fork cannot outlive the Maven process that started it.
 *
 * <p>The child gets only the environment variables a Maven build needs (see {@link #inheritedEnvironment}),
 * because the build executes code proposed by reasoning components, and this JVM's environment may hold
 * credentials such as model API keys. This limits accidental secret leakage; it is not a sandbox: the child
 * still runs as this user, with full access to the file system and the network.
 */
public final class ProcessCommandRunner implements CommandRunner {

    public static final int DEFAULT_MAX_OUTPUT_BYTES = 512 * 1024;

    // Upper case; matched case-insensitively because Windows variable names are (e.g. "Path", "http_proxy").
    private static final Set<String> INHERITED_VARIABLES = Set.of(
            "PATH", "HOME", "USER", "LOGNAME", "SHELL", "LANG", "LC_ALL", "LC_CTYPE", "TZ", "TMPDIR", "TEMP", "TMP",
            "JAVA_HOME", "MAVEN_OPTS", "MAVEN_ARGS", "MAVEN_HOME", "M2_HOME", "MAVEN_USER_HOME", "MVNW_REPOURL",
            "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY",
            "SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT", "USERPROFILE", "APPDATA", "LOCALAPPDATA");

    // Bounds the wait for a killed process to be reaped and for its output pipe to reach EOF. The pipe can
    // stay open after the process exits when a background grandchild inherited it; we then give up on the rest.
    private static final Duration GRACE = Duration.ofSeconds(5);

    private final int maxOutputBytes;

    public ProcessCommandRunner() {
        this(DEFAULT_MAX_OUTPUT_BYTES);
    }

    /** @param maxOutputBytes how much of the most recent output to keep; earlier output is discarded */
    public ProcessCommandRunner(int maxOutputBytes) {
        if (maxOutputBytes <= 0) {
            throw new IllegalArgumentException("maxOutputBytes must be positive: " + maxOutputBytes);
        }
        this.maxOutputBytes = maxOutputBytes;
    }

    @Override
    public CommandResult run(List<String> command, Path workingDirectory, Duration timeout) {
        long start = System.nanoTime();
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true);
        Map<String, String> environment = inheritedEnvironment(builder.environment());
        builder.environment().clear();
        builder.environment().putAll(environment);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start " + command.getFirst() + ": " + e.getMessage(), e);
        }

        TailBuffer output = new TailBuffer(maxOutputBytes);
        // Drained on its own thread: a child that fills the OS pipe buffer would otherwise block forever.
        Thread drainer = Thread.ofPlatform()
                .daemon()
                .name("process-output-" + process.pid())
                .start(() -> drain(process.getInputStream(), output));
        closeStdin(process);

        boolean timedOut;
        try {
            // convert(Duration) saturates, unlike Duration.toNanos(), so a huge timeout cannot overflow.
            timedOut = !process.waitFor(NANOSECONDS.convert(timeout), NANOSECONDS);
            if (timedOut) {
                destroyTree(process);
                process.waitFor(NANOSECONDS.convert(GRACE), NANOSECONDS);
            }
            drainer.join(GRACE);
        } catch (InterruptedException e) {
            destroyTree(process);
            Thread.currentThread().interrupt();
            throw new CancellationException("Interrupted while running " + command.getFirst());
        }

        int exitCode = process.isAlive() ? -1 : process.exitValue();
        Duration duration = Duration.ofNanos(System.nanoTime() - start);
        return new CommandResult(exitCode, output.contents(), duration, timedOut);
    }

    /** The subset of {@code parent} passed on to child processes. */
    static Map<String, String> inheritedEnvironment(Map<String, String> parent) {
        Map<String, String> inherited = new LinkedHashMap<>();
        parent.forEach((name, value) -> {
            if (INHERITED_VARIABLES.contains(name.toUpperCase(Locale.ROOT))) {
                inherited.put(name, value);
            }
        });
        return inherited;
    }

    private static void destroyTree(Process process) {
        // Snapshot first: once the root dies its children are re-parented and no longer listed as descendants.
        List<ProcessHandle> descendants = process.descendants().toList();
        process.destroyForcibly();
        descendants.forEach(ProcessHandle::destroyForcibly);
    }

    private static void closeStdin(Process process) {
        // Nothing is ever fed to the child; an unexpected read should see EOF rather than hang until the timeout.
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // The child may already be gone.
        }
    }

    private static void drain(InputStream stream, TailBuffer output) {
        byte[] chunk = new byte[8192];
        try (stream) {
            int read;
            while ((read = stream.read(chunk)) != -1) {
                output.write(chunk, 0, read);
            }
        } catch (IOException ignored) {
            // The pipe broke because the process was killed; keep what was captured.
        }
    }

    /** Ring buffer holding the last {@code capacity} bytes written to it. */
    private static final class TailBuffer {

        private final byte[] ring;
        private int next;
        private long written;

        TailBuffer(int capacity) {
            ring = new byte[capacity];
        }

        synchronized void write(byte[] bytes, int offset, int length) {
            written += length;
            if (length >= ring.length) {
                System.arraycopy(bytes, offset + length - ring.length, ring, 0, ring.length);
                next = 0;
                return;
            }
            int untilEnd = Math.min(length, ring.length - next);
            System.arraycopy(bytes, offset, ring, next, untilEnd);
            System.arraycopy(bytes, offset + untilEnd, ring, 0, length - untilEnd);
            next = (next + length) % ring.length;
        }

        synchronized String contents() {
            if (written <= ring.length) {
                return new String(ring, 0, (int) written, StandardCharsets.UTF_8);
            }
            byte[] ordered = new byte[ring.length];
            System.arraycopy(ring, next, ordered, 0, ring.length - next);
            System.arraycopy(ring, 0, ordered, ring.length - next, next);
            // The cut most likely split a line, maybe even a multi-byte character: start at the next full line.
            int from = 0;
            for (int i = 0; i < ordered.length - 1; i++) {
                if (ordered[i] == '\n') {
                    from = i + 1;
                    break;
                }
            }
            return new String(ordered, from, ordered.length - from, StandardCharsets.UTF_8);
        }
    }
}
