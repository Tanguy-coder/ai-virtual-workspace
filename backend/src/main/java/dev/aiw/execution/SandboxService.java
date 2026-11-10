package dev.aiw.execution;

import dev.aiw.shared.Processes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * One ephemeral Docker container per task run (docs/10). The task's worktree is mounted at /work; the container
 * runs as the platform's user, without network by default, with CPU, memory and process limits. Commands of the
 * agent and of the platform (tests) run inside it, never in the API process.
 */
@Service
public class SandboxService {
    private static final Logger log = LoggerFactory.getLogger(SandboxService.class);

    /** First words of the commands an agent may run. Shell operators are refused: one command per call. */
    public static final Set<String> ALLOWED = Set.of("npm", "npx", "node", "python", "python3", "pip", "pytest",
            "mvn", "java", "javac", "ls", "cat", "head", "tail", "wc", "pwd", "echo", "tree");
    /** Commands that need the package registry: the container joins the network for their duration only. */
    private static final List<String> NEEDS_NETWORK = List.of("npm install", "npm ci", "npm i ", "pip install", "mvn ");

    private final boolean enabled;
    private final String cpus;
    private final String memory;
    private final Duration commandTimeout;
    private final String user;

    SandboxService(@Value("${aiw.sandbox.enabled:true}") boolean enabled, @Value("${aiw.sandbox.cpus:1}") String cpus,
                   @Value("${aiw.sandbox.memory:1g}") String memory,
                   @Value("${aiw.sandbox.command-timeout-seconds:300}") int timeoutSeconds) {
        this.enabled = enabled;
        this.cpus = cpus;
        this.memory = memory;
        this.commandTimeout = Duration.ofSeconds(timeoutSeconds);
        Processes.Result u = Processes.run(List.of("id", "-u"), null, Duration.ofSeconds(5));
        Processes.Result g = Processes.run(List.of("id", "-g"), null, Duration.ofSeconds(5));
        this.user = u.ok() && g.ok() ? u.out().strip() + ":" + g.out().strip() : null;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Image for the project's stack, from the files at the root of the worktree. */
    public static String imageFor(Path worktree) {
        if (Files.exists(worktree.resolve("pom.xml"))) return "maven:3.9-eclipse-temurin-21";
        if (Files.exists(worktree.resolve("pyproject.toml")) || Files.exists(worktree.resolve("requirements.txt"))) return "python:3.12-slim";
        return "node:22-bookworm-slim";
    }

    public record Command(String command, int exit, String output, boolean timedOut, long durationMs) {
        public boolean ok() {
            return exit == 0 && !timedOut;
        }
    }

    public static class SandboxException extends RuntimeException {
        public SandboxException(String message) {
            super(message);
        }
    }

    /** Starts (or returns, if still alive) the container of a run. */
    public String ensure(String runId, String existingId, Path worktree, String image) {
        if (!enabled) throw new SandboxException("Sandboxes are disabled on this platform (aiw.sandbox.enabled=false)");
        if (existingId != null && docker("inspect", "-f", "{{.State.Running}}", existingId).out().strip().equals("true")) {
            return existingId;
        }
        if (existingId != null) docker("rm", "-f", existingId);
        if (!docker("image", "inspect", image).ok()) {
            Processes.Result pull = Processes.run(List.of("docker", "pull", "-q", image), null, Duration.ofMinutes(10));
            if (!pull.ok()) throw new SandboxException("Could not pull image " + image + ": " + pull.combined().strip());
        }
        List<String> cmd = new ArrayList<>(List.of("docker", "run", "-d", "--rm", "--name", "aiw-run-" + runId.toLowerCase() + "-" + System.nanoTime() % 100000,
                "--label", "aiw.run=" + runId, "--network", "none", "--cpus", cpus, "--memory", memory,
                "--pids-limit", "512", "--security-opt", "no-new-privileges", "-e", "HOME=/tmp", "-e", "CI=true",
                "-e", "npm_config_cache=/tmp/.npm", "-v", worktree.toAbsolutePath() + ":/work", "-w", "/work"));
        if (user != null) {
            cmd.add("--user");
            cmd.add(user);
        }
        cmd.addAll(List.of(image, "sleep", "infinity"));
        Processes.Result r = Processes.run(cmd, null, Duration.ofMinutes(2));
        if (!r.ok()) throw new SandboxException("Could not start the sandbox: " + r.combined().strip());
        return r.out().strip();
    }

    /** Runs one command in the container. Commands that install packages get network access for their duration. */
    public Command exec(String containerId, String command, Duration timeout) {
        boolean network = NEEDS_NETWORK.stream().anyMatch(p -> (command.strip() + " ").startsWith(p));
        if (network) docker("network", "connect", "bridge", containerId);
        try {
            Processes.Result r = Processes.run(List.of("docker", "exec", containerId, "sh", "-c", command), null,
                    timeout == null ? commandTimeout : timeout);
            return new Command(command, r.exit(), r.combined(), r.timedOut(), r.durationMs());
        } finally {
            if (network) docker("network", "disconnect", "-f", "bridge", containerId);
        }
    }

    public void stop(String containerId) {
        if (containerId == null) return;
        Processes.Result r = docker("rm", "-f", containerId);
        if (!r.ok()) log.debug("Sandbox {} already gone: {}", containerId, r.combined());
    }

    /** Sandboxes of this platform: run id → container id (short). */
    public java.util.Map<String, String> running() {
        Processes.Result r = docker("ps", "-a", "--filter", "label=aiw.run", "--format", "{{.ID}}\t{{.Label \"aiw.run\"}}");
        java.util.Map<String, String> out = new java.util.HashMap<>();
        if (!r.ok()) return out;
        r.out().lines().map(l -> l.split("\t")).filter(p -> p.length == 2).forEach(p -> out.put(p[1], p[0]));
        return out;
    }

    public record Stats(String cpu, String memory) {}

    public Stats stats(String containerId) {
        Processes.Result r = docker("stats", "--no-stream", "--format", "{{.CPUPerc}}\t{{.MemUsage}}", containerId);
        if (!r.ok()) return null;
        String[] p = r.out().strip().split("\t");
        return p.length == 2 ? new Stats(p[0], p[1].split("/")[0].strip()) : null;
    }

    /**
     * Checks an agent's command: an allowed program, no shell operators, nothing outside /work.
     * Returns the reason it is refused, or null.
     */
    public static String refusal(String command) {
        String c = command == null ? "" : command.strip();
        if (c.isEmpty()) return "Empty command.";
        if (c.matches("(?s).*(;|&&|\\|\\||\\||`|\\$\\(|>|<|\\n).*")) {
            return "One command per call, without shell operators (; && | > < ` $( ).";
        }
        String first = c.split("\\s+")[0];
        if (!ALLOWED.contains(first)) return "\"" + first + "\" is not allowed. Allowed: " + String.join(", ", ALLOWED.stream().sorted().toList()) + ".";
        if (c.matches(".*\\s(/(?!work)|\\.\\./).*")) return "Paths must stay inside the project.";
        return null;
    }

    private Processes.Result docker(String... args) {
        List<String> cmd = new ArrayList<>(List.of("docker"));
        cmd.addAll(List.of(args));
        return Processes.run(cmd, null, Duration.ofSeconds(30));
    }
}
