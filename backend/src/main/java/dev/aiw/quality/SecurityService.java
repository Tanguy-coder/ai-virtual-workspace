package dev.aiw.quality;

import dev.aiw.events.EventPublisher;
import dev.aiw.events.Subject;
import dev.aiw.execution.SandboxService;
import dev.aiw.governance.AuditLog;
import dev.aiw.scm.GitService;
import dev.aiw.shared.Actor;
import dev.aiw.shared.ApiException;
import dev.aiw.shared.Ids;
import dev.aiw.shared.Processes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Security Center (docs/18): runs recognised scanners in throwaway containers on a frozen snapshot of the code, or
 * against the deployed test application, and keeps the findings with their proof. No model call: a scan costs CPU,
 * never tokens. Critical and high findings get a fix task, and keep develop out of main until fixed or accepted.
 */
@Service
public class SecurityService {
    private static final Logger log = LoggerFactory.getLogger(SecurityService.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    public static final List<String> STATIC = List.of("SECRETS", "SAST", "DEPENDENCIES");
    public static final List<String> DYNAMIC = List.of("DAST", "LOAD", "E2E");
    static final String GITLEAKS = "zricethezav/gitleaks:v8.21.2";
    static final String SEMGREP = "semgrep/semgrep:1.99.0";
    static final String OSV = "ghcr.io/google/osv-scanner:v1.9.2";
    static final String ZAP = "ghcr.io/zaproxy/zaproxy:2.15.0";
    static final String K6 = "grafana/k6:0.55.0";
    static final String PLAYWRIGHT = "mcr.microsoft.com/playwright:v1.49.1-noble";
    /** Fix tasks open at once per project: each costs tokens, and the next scan tops them up. */
    static final int MAX_OPEN_FIX_TASKS = 3;
    static final int LOAD_VUS = 10;
    static final int LOAD_SECONDS = 20;

    private final SandboxService sandboxes;
    private final GitService git;
    private final JdbcClient jdbc;
    private final EventPublisher events;
    private final AuditLog audit;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final ApplicationEventPublisher appEvents;
    private final Path dataDir;
    private final boolean enabled;
    private final List<String> staticKinds;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    /** Projects whose static scan is running → whether another was asked meanwhile (one rerun, not one per merge). */
    private final Map<String, Boolean> running = new ConcurrentHashMap<>();

    SecurityService(SandboxService sandboxes, GitService git, JdbcClient jdbc, EventPublisher events, AuditLog audit,
                    TransactionTemplate tx, JsonMapper json, ApplicationEventPublisher appEvents,
                    @Value("${aiw.data-dir}") String dataDir, @Value("${aiw.security.scans-enabled:true}") boolean enabled,
                    @Value("${aiw.security.static-kinds:SECRETS,SAST,DEPENDENCIES}") List<String> staticKinds) {
        this.sandboxes = sandboxes;
        this.git = git;
        this.jdbc = jdbc;
        this.events = events;
        this.audit = audit;
        this.tx = tx;
        this.json = json;
        this.appEvents = appEvents;
        this.dataDir = Path.of(dataDir).toAbsolutePath().normalize();
        this.enabled = enabled && sandboxes.enabled();
        this.staticKinds = staticKinds.stream().map(String::strip).filter(STATIC::contains).toList();
    }

    public boolean enabled() {
        return enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    void interrupted() {
        jdbc.sql("UPDATE quality_scans SET status = 'FAILED', error = 'Interrupted by a restart of the platform', ended_at = now() WHERE status = 'RUNNING'")
                .update();
    }

    // ================================================================== static scans (develop)

    /**
     * Scans develop after a merge, in the background. A request while the project's scan runs makes one more scan
     * at its end. When scans are disabled, the completion is announced at once (the branch flow goes on).
     */
    public void requestStaticScan(String workspaceId, String projectId, String trigger) {
        if (!enabled) {
            appEvents.publishEvent(new StaticScanCompleted(workspaceId, projectId, null, blocking(projectId).size()));
            return;
        }
        if (running.putIfAbsent(projectId, false) != null) {
            running.put(projectId, true);
            return;
        }
        executor.submit(() -> {
            try {
                boolean again;
                do {
                    running.put(projectId, false);
                    staticScan(workspaceId, projectId, trigger);
                    again = Boolean.TRUE.equals(running.get(projectId));
                } while (again);
            } catch (RuntimeException e) {
                log.warn("Static scan of project {} failed", projectId, e);
            } finally {
                running.remove(projectId);
            }
        });
    }

    /** The static scans of develop's current commit, now (also the CTO's "Scan now"). Returns the scans. */
    public List<QualityScan> staticScan(String workspaceId, String projectId, String trigger) {
        Path repo = git.repo(projectId).orElseThrow(() -> ApiException.notFound("Repository"));
        String ref = git.integrationBranch(repo);
        String commit = git.revParse(repo, ref);
        Path snap = snapshot(repo, commit);
        List<QualityScan> done = new ArrayList<>();
        try {
            for (String kind : staticKinds) done.add(scanAndRecord(workspaceId, projectId, kind, ref, commit, null, trigger, snap, null));
        } finally {
            delete(snap);
        }
        topUpFixTasks(workspaceId, projectId);
        appEvents.publishEvent(new StaticScanCompleted(workspaceId, projectId, commit, blocking(projectId).size()));
        return done;
    }

    // ================================================================== dynamic scans (deployed test application)

    /**
     * DAST, load and E2E against a deployment, in the background. {@code url}: as seen on the applications' network;
     * {@code snapshot}: the deployed files (the E2E suite), deleted once the scans are done.
     */
    public void requestDynamicScan(String workspaceId, String projectId, String commit, String url, Path snapshot, String trigger) {
        if (!enabled) {
            delete(snapshot);
            return;
        }
        executor.submit(() -> {
            try {
                for (String kind : DYNAMIC) scanAndRecord(workspaceId, projectId, kind, "deployment", commit, url, trigger, snapshot, url);
                topUpFixTasks(workspaceId, projectId);
            } catch (RuntimeException e) {
                log.warn("Dynamic scan of project {} failed", projectId, e);
            } finally {
                delete(snapshot);
            }
        });
    }

    // ================================================================== verification of a fix (a task's branch)

    /**
     * Reruns the finding's tool on a branch: the problem text when the finding is still there, empty when it is gone or
     * cannot be checked on a branch (dynamic findings are checked at the next deployment).
     */
    public Optional<String> stillPresent(String findingId, Path repoOrWorktree, String ref) {
        Finding f = find(findingId).orElse(null);
        if (f == null || !enabled || !STATIC.contains(f.kind())) return Optional.empty();
        String commit = git.revParse(repoOrWorktree, ref);
        Path snap = snapshot(repoOrWorktree, commit);
        try {
            Outcome o = run(f.kind(), snap, null);
            if (!"DONE".equals(o.status())) return Optional.empty();
            String fp = fingerprint(f);
            return o.drafts().stream().filter(d -> d.fingerprint().equals(fp)).findFirst().map(d ->
                    "The platform ran " + d.tool() + " on your branch: finding F-" + f.number() + " is still there"
                            + (d.file() == null ? "" : " (" + d.file() + (d.line() == null ? "" : ":" + d.line()) + ")")
                            + ".\nProof: " + d.evidence() + "\nWhat to do: " + f.recommendation());
        } finally {
            delete(snap);
        }
    }

    private String fingerprint(Finding f) {
        return jdbc.sql("SELECT fingerprint FROM security_findings WHERE id = ?").param(f.id()).query(String.class).single();
    }

    // ================================================================== running a tool

    record Outcome(String status, List<ScanParsers.Draft> drafts, Map<String, Object> metrics, String error) {}

    private QualityScan scanAndRecord(String ws, String projectId, String kind, String ref, String commit, String url,
                                      String trigger, Path snapshot, String target) {
        String id = Ids.newId();
        String tool = toolOf(kind);
        tx.executeWithoutResult(s -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('scan' || ?))").param(projectId).query().singleRow();
            int number = jdbc.sql("SELECT coalesce(max(number), 0) + 1 FROM quality_scans WHERE project_id = ?").param(projectId)
                    .query(Integer.class).single();
            jdbc.sql("""
                    INSERT INTO quality_scans (id, workspace_id, project_id, number, kind, tool, ref, commit_sha, target_url, trigger, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RUNNING')
                    """).params(id, ws, projectId, number, kind, tool, ref, commit, url, trigger).update();
            events.publish(ws, projectId, "security_scan.started", Actor.system(), new Subject("quality_scan", id),
                    Map.of("kind", kind, "tool", tool, "number", number));
        });
        long start = System.nanoTime();
        Outcome o;
        try {
            o = run(kind, snapshot, target);
        } catch (RuntimeException e) {
            o = new Outcome("FAILED", List.of(), Map.of(), ScanParsers.clip(String.valueOf(e.getMessage()), 1_000));
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        Outcome outcome = o;
        tx.executeWithoutResult(s -> record(ws, projectId, id, kind, outcome, ms));
        return scan(id).orElseThrow();
    }

    private String toolOf(String kind) {
        return switch (kind) {
            case "SECRETS" -> "gitleaks";
            case "SAST" -> "semgrep";
            case "DEPENDENCIES" -> "osv-scanner";
            case "DAST" -> "zap";
            case "LOAD" -> "k6";
            default -> "playwright";
        };
    }

    /** Runs one tool on a snapshot (static) or against an application URL (dynamic). */
    Outcome run(String kind, Path snap, String url) {
        return switch (kind) {
            case "SECRETS" -> {
                Processes.Result r = sandboxes.runTool(new SandboxService.ToolRun(GITLEAKS, List.of("dir", "/src", "--report-format", "json",
                        "--report-path", "/dev/stdout", "--no-banner", "--redact", "--exit-code", "0", "--log-level", "error"),
                        snap, "/src", null, null, "none", Duration.ofMinutes(5)));
                if (!r.ok()) yield failed(r);
                yield new Outcome("DONE", ScanParsers.gitleaks(readJson(r.out(), "[]"), snap, "/src/"), Map.of(), null);
            }
            case "SAST" -> {
                Processes.Result r = sandboxes.runTool(new SandboxService.ToolRun(SEMGREP, List.of("semgrep", "scan", "--config", "p/default",
                        "--json", "--metrics=off", "--quiet", "--disable-version-check", "--exclude", "node_modules", "/src"),
                        snap, "/src", null, null, "bridge", Duration.ofMinutes(10)));
                if (r.exit() != 0 && r.exit() != 1) yield failed(r);
                yield new Outcome("DONE", ScanParsers.semgrep(readJson(r.out(), "{}"), snap, "/src/"), Map.of(), null);
            }
            case "DEPENDENCIES" -> {
                Processes.Result r = sandboxes.runTool(new SandboxService.ToolRun(OSV, List.of("--format", "json", "-r", "/src"),
                        snap, "/src", null, null, "bridge", Duration.ofMinutes(5)));
                if (r.out().isBlank() && r.combined().contains("No package sources found")) {
                    yield new Outcome("SKIPPED", List.of(), Map.of("reason", "no lockfile (package-lock.json, poetry.lock, pom.xml…)"), null);
                }
                if (r.exit() != 0 && r.exit() != 1) yield failed(r);
                yield new Outcome("DONE", ScanParsers.osv(readJson(r.out(), "{}")), Map.of(), null);
            }
            case "DAST" -> {
                Path out = workDir("dast");
                try {
                    Processes.Result r = sandboxes.runTool(new SandboxService.ToolRun(ZAP, List.of("zap-baseline.py", "-t", url, "-J", "report.json",
                            "-I", "-m", "1"), null, null, out, "/zap/wrk", sandboxes.appNetwork(), Duration.ofMinutes(10)));
                    Path report = out.resolve("report.json");
                    if (!Files.isRegularFile(report)) yield failed(r);
                    yield new Outcome("DONE", ScanParsers.zap(json.readTree(Files.readString(report))), Map.of(), null);
                } catch (IOException e) {
                    yield new Outcome("FAILED", List.of(), Map.of(), e.getMessage());
                } finally {
                    delete(out);
                }
            }
            case "LOAD" -> {
                Path out = workDir("load");
                try {
                    Files.writeString(out.resolve("load.js"), loadScript(url));
                    Processes.Result r = sandboxes.runTool(new SandboxService.ToolRun(K6, List.of("run", "--quiet", "--summary-export=/out/summary.json",
                            "/out/load.js"), null, null, out, "/out", sandboxes.appNetwork(), Duration.ofMinutes(5)));
                    Path summary = out.resolve("summary.json");
                    if (!Files.isRegularFile(summary)) yield failed(r);
                    ScanParsers.Load l = ScanParsers.k6(json.readTree(Files.readString(summary)));
                    Map<String, Object> metrics = new LinkedHashMap<>();
                    metrics.put("p95Ms", l.p95Ms());
                    metrics.put("errorRate", l.errorRate());
                    metrics.put("rps", l.rps());
                    metrics.put("requests", l.requests());
                    metrics.put("vus", LOAD_VUS);
                    metrics.put("seconds", LOAD_SECONDS);
                    yield new Outcome("DONE", ScanParsers.loadFindings(l, LOAD_VUS, LOAD_SECONDS, url), metrics, null);
                } catch (IOException e) {
                    yield new Outcome("FAILED", List.of(), Map.of(), e.getMessage());
                } finally {
                    delete(out);
                }
            }
            case "E2E" -> e2e(snap, url);
            default -> throw new IllegalArgumentException("Unknown scan kind " + kind);
        };
    }

    /** The project's own end-to-end tests (script test:e2e), against the deployed application. Skipped when there are none. */
    private Outcome e2e(Path snap, String url) {
        if (snap == null || !hasE2eScript(snap)) {
            return new Outcome("SKIPPED", List.of(), Map.of("reason", "the project has no test:e2e script"), null);
        }
        Path work = workDir("e2e");
        try {
            Processes.Result copy = Processes.run(List.of("cp", "-a", snap + "/.", work.toString()), null, Duration.ofMinutes(2));
            if (!copy.ok()) return new Outcome("FAILED", List.of(), Map.of(), copy.combined());
            Processes.Result r = sandboxes.runTool(new SandboxService.ToolRun(PLAYWRIGHT, List.of("sh", "-c",
                    "cd /work && (npm ci --no-audit --no-fund || npm install --no-audit --no-fund) >/dev/null 2>&1; BASE_URL='" + url
                            + "' CI=true npm run test:e2e"), null, null, work, "/work", sandboxes.appNetwork(), Duration.ofMinutes(15)));
            if (r.ok()) return new Outcome("DONE", List.of(), Map.of("passed", true), null);
            String excerpt = TestOutput.failingExcerpt(r.combined(), 40);
            return new Outcome("DONE", List.of(new ScanParsers.Draft("E2E", "playwright", "test:e2e", "HIGH",
                    "End-to-end tests fail against the deployed application", null, null, url, ScanParsers.clip(excerpt, 1_500), null,
                    "Run npm run test:e2e against the test environment, fix the failing journey, and keep the E2E suite green.",
                    ScanParsers.hash("E2E", "test:e2e"))), Map.of("passed", false), null);
        } finally {
            delete(work);
        }
    }

    private boolean hasE2eScript(Path snap) {
        try {
            Path pkg = snap.resolve("package.json");
            return Files.isRegularFile(pkg) && json.readTree(Files.readString(pkg)).path("scripts").has("test:e2e");
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** A k6 script by code: every virtual user reads the application's health and home pages. */
    static String loadScript(String url) {
        String base = url.replaceAll("/+$", "");
        return """
                import http from 'k6/http';
                import { sleep } from 'k6';
                export const options = { vus: %d, duration: '%ds' };
                export default function () {
                  http.get('%s/health');
                  http.get('%s/');
                  sleep(0.1);
                }
                """.formatted(LOAD_VUS, LOAD_SECONDS, base, base);
    }

    private Outcome failed(Processes.Result r) {
        String why = r.timedOut() ? "The tool timed out" : "The tool failed (exit " + r.exit() + "): " + r.combined().strip();
        return new Outcome("FAILED", List.of(), Map.of(), ScanParsers.clip(why, 1_000));
    }

    private JsonNode readJson(String out, String empty) {
        String s = out == null ? "" : out.strip();
        int a = s.indexOf(empty.charAt(0));
        if (a < 0) return json.readTree(empty);
        try {
            return json.readTree(s.substring(a));
        } catch (RuntimeException e) {
            return json.readTree(empty);
        }
    }

    // ================================================================== recording findings

    private void record(String ws, String projectId, String scanId, String kind, Outcome o, long ms) {
        int opened = 0, reopened = 0, fixed = 0;
        if ("DONE".equals(o.status())) {
            for (ScanParsers.Draft d : o.drafts()) {
                Optional<Map<String, Object>> existing = jdbc.sql("SELECT id, status FROM security_findings WHERE project_id = ? AND fingerprint = ?")
                        .params(projectId, d.fingerprint()).query((rs, i) -> Map.<String, Object>of("id", rs.getString(1), "status", rs.getString(2)))
                        .optional();
                if (existing.isEmpty()) {
                    String id = Ids.newId();
                    jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('finding' || ?))").param(projectId).query().singleRow();
                    int number = jdbc.sql("SELECT coalesce(max(number), 0) + 1 FROM security_findings WHERE project_id = ?")
                            .param(projectId).query(Integer.class).single();
                    jdbc.sql("""
                            INSERT INTO security_findings (id, workspace_id, project_id, number, kind, tool, rule, severity, title, file, line,
                                   location, evidence, reference, recommendation, fingerprint, first_scan_id, last_scan_id)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """).params(id, ws, projectId, number, d.kind(), d.tool(), d.rule(), d.severity(), ScanParsers.clip(d.title(), 300),
                            d.file(), d.line(), d.location(), d.evidence(), d.reference(), d.recommendation(), d.fingerprint(), scanId, scanId).update();
                    opened++;
                    if (opened <= 20) {
                        events.publish(ws, projectId, "finding.opened", Actor.system(), new Subject("finding", id),
                                Map.of("number", number, "severity", d.severity(), "title", ScanParsers.clip(d.title(), 200), "kind", d.kind()));
                    }
                } else {
                    boolean back = "FIXED".equals(existing.get().get("status"));
                    jdbc.sql("""
                            UPDATE security_findings SET last_scan_id = ?, severity = ?, file = ?, line = ?, location = ?, evidence = ?,
                                   status = CASE WHEN status = 'FIXED' THEN 'OPEN' ELSE status END,
                                   fixed_at = CASE WHEN status = 'FIXED' THEN NULL ELSE fixed_at END, updated_at = now()
                            WHERE id = ?
                            """).params(scanId, d.severity(), d.file(), d.line(), d.location(), d.evidence(), existing.get().get("id")).update();
                    if (back) {
                        reopened++;
                        events.publish(ws, projectId, "finding.reopened", Actor.system(), new Subject("finding", (String) existing.get().get("id")),
                                Map.of("title", ScanParsers.clip(d.title(), 200), "severity", d.severity()));
                    }
                }
            }
            // What this kind of scan no longer sees is fixed (accepted risks stay accepted).
            List<String> gone = jdbc.sql("""
                    SELECT id FROM security_findings WHERE project_id = ? AND kind = ? AND status IN ('OPEN', 'FIX_TASK')
                      AND last_scan_id IS DISTINCT FROM ?
                    """).params(projectId, kind, scanId).query(String.class).list();
            for (String id : gone) {
                jdbc.sql("UPDATE security_findings SET status = 'FIXED', fixed_at = now(), updated_at = now() WHERE id = ?").param(id).update();
                events.publish(ws, projectId, "finding.fixed", Actor.system(), new Subject("finding", id), Map.of("kind", kind));
                fixed++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("counts", ScanParsers.counts(o.drafts()));
        summary.put("findings", o.drafts().size());
        summary.put("opened", opened);
        summary.put("reopened", reopened);
        summary.put("fixed", fixed);
        if (!o.metrics().isEmpty()) summary.put("metrics", o.metrics());
        jdbc.sql("UPDATE quality_scans SET status = ?, summary = ?::jsonb, error = ?, ended_at = now(), duration_ms = ? WHERE id = ?")
                .params(o.status(), json.writeValueAsString(summary), o.error(), ms, scanId).update();
        Map<String, Object> payload = new HashMap<>(summary);
        payload.put("kind", kind);
        payload.put("status", o.status());
        events.publish(ws, projectId, "security_scan.completed", Actor.system(), new Subject("quality_scan", scanId), payload);
    }

    // ================================================================== fix tasks

    /** Critical and high open findings without a task get one, up to {@link #MAX_OPEN_FIX_TASKS} open at once. */
    void topUpFixTasks(String ws, String projectId) {
        long open = jdbc.sql("""
                SELECT count(*) FROM security_findings f JOIN tasks t ON t.id = f.task_id
                WHERE f.project_id = ? AND f.status = 'FIX_TASK' AND t.status NOT IN ('DONE', 'CANCELLED')
                """).param(projectId).query(Long.class).single();
        if (open >= MAX_OPEN_FIX_TASKS) return;
        List<Finding> waiting = findings(projectId, "OPEN").stream().filter(Finding::blocking)
                .sorted(Comparator.comparingInt((Finding f) -> ScanParsers.rank(f.severity())).thenComparingInt(Finding::number))
                .limit(MAX_OPEN_FIX_TASKS - open).toList();
        for (Finding f : waiting) requestFix(ws, f, Actor.system());
    }

    /** The CTO (or the platform) asks for a fix task; orchestration creates it and links it back. */
    public Finding requestFix(String workspaceId, String projectId, String findingId, Actor actor) {
        Finding f = find(findingId).filter(x -> x.projectId().equals(projectId)).orElseThrow(() -> ApiException.notFound("Finding"));
        if (!"OPEN".equals(f.status())) throw ApiException.conflict("not_open", "F-" + f.number() + " is " + f.status().toLowerCase().replace('_', ' '));
        requestFix(workspaceId, f, actor);
        return find(findingId).orElseThrow();
    }

    private void requestFix(String ws, Finding f, Actor actor) {
        appEvents.publishEvent(new FixRequested(ws, f.projectId(), f.id(), fixTitle(f), fixDescription(f), fixCriteria(f),
                "CRITICAL".equals(f.severity()) ? "CRITICAL" : "HIGH".equals(f.severity()) ? "HIGH" : "MEDIUM", departments(f),
                actor.kind() == Actor.Kind.HUMAN ? actor.id() : null));
    }

    public void linkTask(String findingId, String taskId, int taskNumber) {
        Finding f = find(findingId).orElseThrow();
        tx.executeWithoutResult(s -> {
            jdbc.sql("UPDATE security_findings SET status = 'FIX_TASK', task_id = ?, updated_at = now() WHERE id = ?").params(taskId, findingId).update();
            events.publish(workspaceOf(f), f.projectId(), "finding.fix_task", Actor.system(), new Subject("finding", findingId),
                    Map.of("number", f.number(), "taskNumber", taskNumber, "title", ScanParsers.clip(f.title(), 200)));
        });
    }

    /** A fix task was cancelled: its finding is open again (and may get another task). */
    public void taskClosedWithoutFix(String taskId) {
        jdbc.sql("UPDATE security_findings SET status = 'OPEN', task_id = NULL, updated_at = now() WHERE task_id = ? AND status = 'FIX_TASK'")
                .param(taskId).update();
    }

    static String fixTitle(Finding f) {
        return ScanParsers.clip("Fix F-" + f.number() + ": " + f.title(), 160);
    }

    static String fixDescription(Finding f) {
        StringBuilder sb = new StringBuilder();
        sb.append("Security finding F-").append(f.number()).append(" (").append(f.severity()).append(", found by ").append(f.tool())
                .append(", rule ").append(f.rule()).append(").\n");
        if (f.file() != null) sb.append("Where: ").append(f.file()).append(f.line() == null ? "" : ":" + f.line()).append("\n");
        if (f.location() != null) sb.append("Where: ").append(f.location()).append("\n");
        sb.append("Proof:\n").append(f.evidence()).append("\n");
        if (f.reference() != null) sb.append("Reference: ").append(f.reference()).append("\n");
        sb.append("What to do: ").append(f.recommendation()).append("\n");
        sb.append(STATIC.contains(f.kind())
                ? "When you submit, the platform runs " + f.tool() + " on your branch: the finding must be gone. Change only what the fix needs."
                : "The platform checks it on the next deployment to the test environment. Change only what the fix needs.");
        return sb.toString();
    }

    static List<String> fixCriteria(Finding f) {
        String where = f.file() == null ? "" : " in " + f.file();
        return switch (f.kind()) {
            case "SECRETS" -> List.of("No credential is written" + where + ": the value is read from an environment variable",
                    "The existing tests still pass");
            case "DEPENDENCIES" -> {
                String pkg = f.location() == null ? "the dependency" : f.location().replaceFirst("^[^:]*:", "").replaceFirst("@[^@]*$", "");
                yield List.of(pkg + " is upgraded to a version without the advisories, in package.json and the lockfile",
                        "The existing tests still pass");
            }
            case "SAST" -> List.of("The code" + where + " no longer has the flaw: " + f.title(),
                    "A test proves the fixed behaviour (unsafe input is rejected or escaped)");
            case "DAST" -> List.of("The application no longer shows: " + f.title(), "A test proves it (for example the response carries the expected header)");
            case "LOAD" -> List.of("The application meets its budget under load: " + f.title(), "The existing tests still pass");
            default -> List.of("The end-to-end tests pass against the application", "The existing tests still pass");
        };
    }

    static List<String> departments(Finding f) {
        return switch (f.kind()) {
            case "DAST" -> List.of("SECURITY", "BACKEND", "FRONTEND", "DEVOPS");
            case "LOAD" -> List.of("BACKEND", "DEVOPS", "SECURITY");
            case "E2E" -> List.of("QA", "FRONTEND", "BACKEND");
            case "DEPENDENCIES" -> List.of("SECURITY", "DEVOPS", "BACKEND", "FRONTEND");
            default -> List.of("SECURITY", "BACKEND", "FRONTEND");
        };
    }

    // ================================================================== CTO decisions

    public Finding acceptRisk(String workspaceId, String projectId, String findingId, String userId, String reason) {
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("reason_required", "Say why the risk is accepted");
        Finding f = find(findingId).filter(x -> x.projectId().equals(projectId)).orElseThrow(() -> ApiException.notFound("Finding"));
        if ("FIXED".equals(f.status()) || "RISK_ACCEPTED".equals(f.status())) {
            throw ApiException.conflict("not_open", "F-" + f.number() + " is " + f.status().toLowerCase().replace('_', ' '));
        }
        tx.executeWithoutResult(s -> {
            jdbc.sql("UPDATE security_findings SET status = 'RISK_ACCEPTED', accepted_by = ?, accepted_reason = ?, updated_at = now() WHERE id = ?")
                    .params(userId, ScanParsers.clip(reason.strip(), 2_000), findingId).update();
            audit.record(workspaceId, Actor.human(userId), "finding.accept_risk", "finding", findingId, AuditLog.Decision.APPROVED,
                    Map.of("number", f.number(), "severity", f.severity(), "title", ScanParsers.clip(f.title(), 200), "reason", ScanParsers.clip(reason.strip(), 500)));
            events.publish(workspaceId, projectId, "finding.risk_accepted", Actor.human(userId), new Subject("finding", findingId),
                    Map.of("number", f.number(), "severity", f.severity(), "title", ScanParsers.clip(f.title(), 200)));
        });
        return find(findingId).orElseThrow();
    }

    // ================================================================== queries

    public List<Finding> blocking(String projectId) {
        return findings(projectId, null).stream().filter(Finding::blocking).toList();
    }

    public Optional<Finding> find(String id) {
        return jdbc.sql("SELECT * FROM security_findings WHERE id = ?").param(id).query(this::finding).optional();
    }

    public Optional<Finding> forTask(String taskId) {
        return jdbc.sql("SELECT * FROM security_findings WHERE task_id = ?").param(taskId).query(this::finding).optional();
    }

    public List<Finding> findings(String projectId, String status) {
        return jdbc.sql("""
                SELECT * FROM security_findings WHERE project_id = ? AND (CAST(? AS TEXT) IS NULL OR status = ?)
                ORDER BY CASE severity WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 WHEN 'LOW' THEN 3 ELSE 4 END, number
                LIMIT 500
                """).params(projectId, status, status).query(this::finding).list();
    }

    public List<QualityScan> scans(String projectId, int limit) {
        return jdbc.sql("SELECT * FROM quality_scans WHERE project_id = ? ORDER BY started_at DESC LIMIT ?").params(projectId, limit)
                .query(this::scanRow).list();
    }

    public Optional<QualityScan> scan(String id) {
        return jdbc.sql("SELECT * FROM quality_scans WHERE id = ?").param(id).query(this::scanRow).optional();
    }

    public boolean scanning(String projectId) {
        return running.containsKey(projectId);
    }

    /** Open findings per severity, and the latest scan of each kind. */
    public Map<String, Object> summary(String projectId) {
        Map<String, Integer> open = new LinkedHashMap<>();
        ScanParsers.SEVERITIES.forEach(s -> open.put(s, 0));
        jdbc.sql("SELECT severity, count(*) FROM security_findings WHERE project_id = ? AND status IN ('OPEN', 'FIX_TASK') GROUP BY severity")
                .param(projectId).query((ResultSet rs) -> {
                    open.put(rs.getString(1), rs.getInt(2));
                });
        Map<String, Object> latest = new LinkedHashMap<>();
        for (String kind : List.of("SECRETS", "SAST", "DEPENDENCIES", "DAST", "LOAD", "E2E")) {
            jdbc.sql("SELECT * FROM quality_scans WHERE project_id = ? AND kind = ? ORDER BY started_at DESC LIMIT 1").params(projectId, kind)
                    .query(this::scanRow).optional().ifPresent(s -> latest.put(kind, s));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("scanning", scanning(projectId));
        out.put("open", open);
        out.put("blocking", blocking(projectId).size());
        out.put("latest", latest);
        return out;
    }

    private String workspaceOf(Finding f) {
        return jdbc.sql("SELECT workspace_id FROM security_findings WHERE id = ?").param(f.id()).query(String.class).single();
    }

    private Finding finding(ResultSet rs, int i) throws SQLException {
        var fixed = rs.getTimestamp("fixed_at");
        return new Finding(rs.getString("id"), rs.getString("project_id"), rs.getInt("number"), rs.getString("kind"), rs.getString("tool"),
                rs.getString("rule"), rs.getString("severity"), rs.getString("title"), rs.getString("file"), (Integer) rs.getObject("line"),
                rs.getString("location"), rs.getString("evidence"), rs.getString("reference"), rs.getString("recommendation"),
                rs.getString("status"), rs.getString("task_id"), rs.getString("accepted_by"), rs.getString("accepted_reason"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(), fixed == null ? null : fixed.toInstant());
    }

    private QualityScan scanRow(ResultSet rs, int i) throws SQLException {
        var ended = rs.getTimestamp("ended_at");
        return new QualityScan(rs.getString("id"), rs.getString("project_id"), rs.getInt("number"), rs.getString("kind"), rs.getString("tool"),
                rs.getString("ref"), rs.getString("commit_sha"), rs.getString("target_url"), rs.getString("trigger"), rs.getString("status"),
                json.readValue(rs.getString("summary"), MAP), rs.getString("error"), rs.getTimestamp("started_at").toInstant(),
                ended == null ? null : ended.toInstant(), (Long) rs.getObject("duration_ms"));
    }

    // ================================================================== files

    /** A frozen copy of a commit's files, under the data dir (sandboxes mount it from the data volume). */
    public Path snapshot(Path repo, String commit) {
        Path dir = dataDir.resolve("snapshots").resolve(Ids.newId().toLowerCase());
        git.export(repo, commit, dir);
        return dir;
    }

    private Path workDir(String kind) {
        try {
            Path dir = Files.createDirectories(dataDir.resolve("scans").resolve(kind + "-" + Ids.newId().toLowerCase()));
            try {
                // The tool's image runs as its own user (ZAP: zap, k6: k6): it must be able to write its report.
                Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxrwx"));
            } catch (UnsupportedOperationException ignored) {
                // not a POSIX file system
            }
            return dir;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    public static void delete(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // a file written by a tool's user may resist: the directory is left for the next cleanup
                }
            });
        } catch (IOException ignored) {
            // nothing to clean
        }
    }

    static final Set<String> KINDS = Set.of("SECRETS", "SAST", "DEPENDENCIES", "DAST", "LOAD", "E2E");
}
