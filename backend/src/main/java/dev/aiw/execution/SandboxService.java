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
    private final Path dataDir;
    /** Named volume holding the data dir (platform running in Docker), or null: worktrees are host paths. */
    private final String dataVolume;
    /**
     * Which installation owns a sandbox: several platforms (the Docker one, a dev run, test contexts) can share one
     * Docker daemon, and each must only clean up its own containers. Derived from where its worktrees live.
     */
    private final String owner;
    /** Remove this platform's sandboxes when it stops (tests). In production they outlive a restart: runs resume in them. */
    @Value("${aiw.sandbox.stop-on-shutdown:false}")
    private boolean stopOnShutdown;

    SandboxService(@Value("${aiw.sandbox.enabled:true}") boolean enabled, @Value("${aiw.sandbox.cpus:1}") String cpus,
                   @Value("${aiw.sandbox.memory:1g}") String memory,
                   @Value("${aiw.sandbox.command-timeout-seconds:300}") int timeoutSeconds,
                   @Value("${aiw.data-dir}") String dataDir, @Value("${aiw.sandbox.data-volume:}") String dataVolume) {
        this.enabled = enabled;
        this.dataDir = Path.of(dataDir).toAbsolutePath().normalize();
        this.dataVolume = dataVolume == null || dataVolume.isBlank() ? null : dataVolume.strip();
        this.owner = Integer.toHexString(((this.dataVolume == null ? "" : this.dataVolume) + "|" + this.dataDir).hashCode());
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
        // A container mounted the old way (host path while the data is in a volume) sees an empty /work: replace it.
        String expected = dataVolume == null ? "bind" : "volume";
        if (existingId != null && docker("inspect", "-f", "{{.State.Running}} {{range .Mounts}}{{.Type}}{{end}}", existingId)
                .out().strip().equals("true " + expected)) {
            return existingId;
        }
        if (existingId != null) docker("rm", "-f", existingId);
        if (!docker("image", "inspect", image).ok()) {
            Processes.Result pull = Processes.run(List.of("docker", "pull", "-q", image), null, Duration.ofMinutes(10));
            if (!pull.ok()) throw new SandboxException("Could not pull image " + image + ": " + pull.combined().strip());
        }
        List<String> cmd = new ArrayList<>(List.of("docker", "run", "-d", "--rm", "--name", "aiw-run-" + runId.toLowerCase() + "-" + System.nanoTime() % 100000,
                "--label", "aiw.run=" + runId, "--label", "aiw.owner=" + owner, "--network", "none", "--cpus", cpus, "--memory", memory,
                "--pids-limit", "512", "--security-opt", "no-new-privileges", "-e", "HOME=/tmp", "-e", "CI=true",
                "-e", "npm_config_cache=/tmp/.npm", "--mount", mount(worktree), "-w", "/work"));
        if (user != null) {
            cmd.add("--user");
            cmd.add(user);
        }
        cmd.addAll(List.of(image, "sleep", "infinity"));
        Processes.Result r = Processes.run(cmd, null, Duration.ofMinutes(2));
        if (!r.ok()) throw new SandboxException("Could not start the sandbox: " + r.combined().strip());
        return r.out().strip();
    }

    /** The worktree at /work: a bind mount of a host path, or the matching subpath of the data volume. */
    String mount(Path worktree) {
        return mount(worktree, "/work", false);
    }

    /** A directory of the data dir mounted at {@code target}, as a bind (host paths) or a subpath of the data volume. */
    String mount(Path dir, String target, boolean readOnly) {
        Path abs = dir.toAbsolutePath().normalize();
        String ro = readOnly ? ",readonly" : "";
        if (dataVolume == null) return "type=bind,source=" + abs + ",target=" + target + ro;
        if (!abs.startsWith(dataDir)) throw new SandboxException("The directory " + abs + " is outside the data directory " + dataDir);
        return "type=volume,source=" + dataVolume + ",target=" + target + ",volume-subpath=" + dataDir.relativize(abs) + ro;
    }

    // ------------------------------------------------------------------ tools and applications (docs/18)

    /**
     * One run of a scanning tool (Gitleaks, Semgrep, OSV-Scanner, ZAP, k6) in a throwaway container: the code under
     * analysis mounted read-only, an output directory writable, the tool's own pinned image. {@code network}: "none",
     * "bridge" (registries, vulnerability databases) or the applications' network for dynamic tests.
     */
    public record ToolRun(String image, List<String> args, Path source, String sourceTarget, Path output, String outputTarget,
                          String network, Duration timeout) {}

    /** Runs the tool; its JSON report is on stdout (or in the output directory), its log on stderr. */
    public Processes.Result runTool(ToolRun t) {
        if (!enabled) throw new SandboxException("Sandboxes are disabled on this platform (aiw.sandbox.enabled=false)");
        pull(t.image());
        List<String> cmd = new ArrayList<>(List.of("docker", "run", "--rm", "--label", "aiw.tool=1", "--label", "aiw.owner=" + owner,
                "--network", t.network() == null ? "none" : t.network(), "--cpus", "2", "--memory", "2g", "--pids-limit", "1024",
                "--security-opt", "no-new-privileges"));
        if (t.source() != null) cmd.addAll(List.of("--mount", mount(t.source(), t.sourceTarget(), true)));
        if (t.output() != null) cmd.addAll(List.of("--mount", mount(t.output(), t.outputTarget(), false)));
        cmd.add(t.image());
        cmd.addAll(t.args());
        return Processes.run(cmd, null, t.timeout() == null ? Duration.ofMinutes(10) : t.timeout());
    }

    private void pull(String image) {
        if (docker("image", "inspect", image).ok()) return;
        Processes.Result pull = Processes.run(List.of("docker", "pull", "-q", image), null, Duration.ofMinutes(20));
        if (!pull.ok()) throw new SandboxException("Could not pull image " + image + ": " + pull.combined().strip());
    }

    /** The network deployed applications and the dynamic tests share; created on first use. */
    public String appNetwork() {
        String name = "aiw-apps";
        if (!docker("network", "inspect", name).ok()) {
            Processes.Result r = docker("network", "create", "--label", "aiw.owner=" + owner, name);
            if (!r.ok() && !docker("network", "inspect", name).ok()) throw new SandboxException("Could not create the network " + name + ": " + r.combined().strip());
        }
        return name;
    }

    /** docker build of a snapshot (its Dockerfile). The build may fetch packages: it has the network, the app later has its own. */
    public Command build(Path context, String tag, Duration timeout) {
        if (!enabled) throw new SandboxException("Sandboxes are disabled on this platform (aiw.sandbox.enabled=false)");
        Processes.Result r = Processes.run(List.of("docker", "build", "-q", "--label", "aiw.owner=" + owner, "-t", tag,
                context.toAbsolutePath().toString()), null, timeout);
        return new Command("docker build " + tag, r.exit(), r.combined(), r.timedOut(), r.durationMs());
    }

    /**
     * Starts an application container (replacing one of the same name): on the applications' network under its name,
     * its port published on the host. Limited like a sandbox; restarted by Docker if it crashes.
     */
    public Command runApp(String name, String image, int hostPort, int containerPort, java.util.Map<String, String> env) {
        docker("rm", "-f", name);
        List<String> cmd = new ArrayList<>(List.of("docker", "run", "-d", "--name", name, "--label", "aiw.app=1", "--label", "aiw.owner=" + owner,
                "--network", appNetwork(), "--network-alias", name, "-p", hostPort + ":" + containerPort, "--cpus", "1",
                "--memory", "512m", "--pids-limit", "256", "--security-opt", "no-new-privileges", "--restart", "on-failure:3"));
        env.forEach((k, v) -> cmd.addAll(List.of("-e", k + "=" + v)));
        cmd.add(image);
        Processes.Result r = Processes.run(cmd, null, Duration.ofMinutes(2));
        return new Command("docker run " + name, r.exit(), r.combined(), r.timedOut(), r.durationMs());
    }

    /** HTTP status of a URL as seen from the applications' network (0 when nothing answers). */
    public int probe(String url) {
        Processes.Result r = Processes.run(List.of("docker", "run", "--rm", "--network", appNetwork(), PROBE_IMAGE, "-s", "-o", "/dev/null",
                "-w", "%{http_code}", "--max-time", "5", url), null, Duration.ofSeconds(30));
        try {
            return Integer.parseInt(r.out().strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static final String PROBE_IMAGE = "curlimages/curl:8.11.0";

    public void ensureProbeImage() {
        pull(PROBE_IMAGE);
    }

    public boolean appRunning(String name) {
        return docker("inspect", "-f", "{{.State.Running}}", name).out().strip().equals("true");
    }

    public String appLogs(String name, int lines) {
        return docker("logs", "--tail", String.valueOf(lines), name).combined();
    }

    public void stopApp(String name) {
        docker("rm", "-f", name);
    }

    /** Runs one command in the container. Commands that install packages get network access for their duration. */
    public Command exec(String containerId, String command, Duration timeout) {
        boolean network = NEEDS_NETWORK.stream().anyMatch(p -> (command.strip() + " ").startsWith(p));
        if (network) {
            // A container on the "none" network cannot join another one: leave it first, rejoin it afterwards.
            docker("network", "disconnect", "none", containerId);
            Processes.Result joined = docker("network", "connect", "bridge", containerId);
            if (!joined.ok()) {
                docker("network", "connect", "none", containerId);
                return new Command(command, 1, "The platform could not give this command network access: "
                        + joined.combined().strip(), false, 0);
            }
        }
        try {
            Processes.Result r = Processes.run(List.of("docker", "exec", containerId, "sh", "-c", command), null,
                    timeout == null ? commandTimeout : timeout);
            return new Command(command, r.exit(), r.combined(), r.timedOut(), r.durationMs());
        } finally {
            if (network) {
                docker("network", "disconnect", "-f", "bridge", containerId);
                Processes.Result isolated = docker("network", "connect", "none", containerId);
                if (!isolated.ok()) log.warn("Sandbox {} could not rejoin the none network: {}", containerId, isolated.combined().strip());
            }
        }
    }

    public void stop(String containerId) {
        if (containerId == null) return;
        Processes.Result r = docker("rm", "-f", containerId);
        if (!r.ok()) log.debug("Sandbox {} already gone: {}", containerId, r.combined());
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        if (!stopOnShutdown) return;
        running().values().forEach(this::stop);
        Processes.Result apps = docker("ps", "-aq", "--filter", "label=aiw.app", "--filter", "label=aiw.owner=" + owner);
        if (apps.ok()) apps.out().lines().filter(l -> !l.isBlank()).forEach(this::stop);
    }

    /** Sandboxes of this platform (not of another one sharing the Docker daemon): run id → container id (short). */
    public java.util.Map<String, String> running() {
        Processes.Result r = docker("ps", "-a", "--filter", "label=aiw.run",
                "--format", "{{.ID}}\t{{.Label \"aiw.run\"}}\t{{.Label \"aiw.owner\"}}");
        java.util.Map<String, String> out = new java.util.HashMap<>();
        if (!r.ok()) return out;
        // No owner label: started before owners existed, so by the platform that has always run here.
        r.out().lines().map(l -> l.split("\t", -1)).filter(p -> p.length == 3 && (p[2].isEmpty() || p[2].equals(owner)))
                .forEach(p -> out.put(p[1], p[0]));
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
