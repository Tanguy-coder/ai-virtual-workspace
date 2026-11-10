package dev.aiw.shared;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs an external program (git, docker) with a timeout and bounded output. */
public final class Processes {
    private Processes() {}

    public record Result(int exit, String out, String err, boolean timedOut, long durationMs) {
        public boolean ok() {
            return exit == 0 && !timedOut;
        }

        /** stdout and stderr together, for logs shown to people and (trimmed) to agents. */
        public String combined() {
            return err.isBlank() ? out : out.isBlank() ? err : out + (out.endsWith("\n") ? "" : "\n") + err;
        }
    }

    public static final int MAX_OUTPUT = 400_000;

    public static Result run(List<String> command, Path dir, Duration timeout, Map<String, String> env, String stdin) {
        long start = System.nanoTime();
        ProcessBuilder pb = new ProcessBuilder(command);
        if (dir != null) pb.directory(dir.toFile());
        if (env != null) pb.environment().putAll(env);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            return new Result(127, "", "Could not start " + command.getFirst() + ": " + e.getMessage(), false, 0);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
        Thread to = Thread.ofVirtual().start(() -> copy(p.getInputStream(), out));
        Thread te = Thread.ofVirtual().start(() -> copy(p.getErrorStream(), err));
        try {
            if (stdin != null) p.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
            p.getOutputStream().close();
        } catch (IOException ignored) {
            // the process may not read stdin
        }
        boolean finished;
        try {
            finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
            }
            to.join(2_000);
            te.join(2_000);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running " + command.getFirst(), e);
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        return new Result(finished ? p.exitValue() : -1, text(out), text(err), !finished, ms);
    }

    public static Result run(List<String> command, Path dir, Duration timeout) {
        return run(command, dir, timeout, null, null);
    }

    private static void copy(InputStream in, ByteArrayOutputStream sink) {
        byte[] buf = new byte[8192];
        try (in) {
            int n;
            while ((n = in.read(buf)) > 0) {
                synchronized (sink) {
                    if (sink.size() < MAX_OUTPUT) sink.write(buf, 0, Math.min(n, MAX_OUTPUT - sink.size()));
                }
            }
        } catch (IOException ignored) {
            // stream closed by the process
        }
    }

    private static String text(ByteArrayOutputStream b) {
        synchronized (b) {
            String s = b.toString(StandardCharsets.UTF_8);
            return b.size() >= MAX_OUTPUT ? s + "\n[... output truncated]" : s;
        }
    }
}
