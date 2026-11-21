package dev.aiw.delivery;

import dev.aiw.events.EventPublisher;
import dev.aiw.events.Subject;
import dev.aiw.execution.SandboxService;
import dev.aiw.governance.ApprovalDecided;
import dev.aiw.governance.ApprovalService;
import dev.aiw.governance.AuditLog;
import dev.aiw.orchestration.MainMerged;
import dev.aiw.organization.Agent;
import dev.aiw.organization.AgentService;
import dev.aiw.quality.SecurityService;
import dev.aiw.scm.GitService;
import dev.aiw.shared.Actor;
import dev.aiw.shared.ApiException;
import dev.aiw.shared.Ids;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deployment Center (docs/18). A project's test environment is a container on the host, built from the project's own
 * Dockerfile at an approved commit. Every deployment is approved by a human: the Lead asks after each merge into
 * main, the CTO's own click is his approval (audited). A healthy deployment is then tested live: DAST, load, E2E.
 */
@Service
public class DeliveryService {
    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);
    static final String DEPLOY = "deployment.start";
    static final String TEST = "test";
    private static final Pattern EXPOSE = Pattern.compile("(?im)^\\s*EXPOSE\\s+(\\d+)");

    private final JdbcClient jdbc;
    private final GitService git;
    private final SandboxService sandboxes;
    private final SecurityService security;
    private final ApprovalService approvals;
    private final AuditLog audit;
    private final EventPublisher events;
    private final AgentService agents;
    private final TransactionTemplate tx;
    private final int portStart;
    private final Duration healthTimeout;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    DeliveryService(JdbcClient jdbc, GitService git, SandboxService sandboxes, SecurityService security, ApprovalService approvals,
                    AuditLog audit, EventPublisher events, AgentService agents, TransactionTemplate tx,
                    @Value("${aiw.delivery.port-range-start:18000}") int portStart,
                    @Value("${aiw.delivery.health-timeout-seconds:90}") int healthSeconds) {
        this.jdbc = jdbc;
        this.git = git;
        this.sandboxes = sandboxes;
        this.security = security;
        this.approvals = approvals;
        this.audit = audit;
        this.events = events;
        this.agents = agents;
        this.tx = tx;
        this.portStart = portStart;
        this.healthTimeout = Duration.ofSeconds(healthSeconds);
    }

    @EventListener(ApplicationReadyEvent.class)
    void interrupted() {
        jdbc.sql("""
                UPDATE deployments SET status = 'FAILED', error = 'Interrupted by a restart of the platform', finished_at = now()
                WHERE status IN ('QUEUED', 'BUILDING', 'STARTING')
                """).update();
        jdbc.sql("UPDATE environments SET status = 'UNHEALTHY', updated_at = now() WHERE status = 'DEPLOYING'").update();
    }

    // ================================================================== environments

    /** The project's test environment, created on first use with a free host port. */
    public Environment testEnvironment(String workspaceId, String projectId) {
        Optional<Environment> existing = environment(projectId, TEST);
        if (existing.isPresent()) return existing.get();
        return tx.execute(s -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('env-ports'))").query().singleRow();
            Optional<Environment> again = environment(projectId, TEST);
            if (again.isPresent()) return again.get();
            List<Integer> used = jdbc.sql("SELECT host_port FROM environments").query(Integer.class).list();
            int port = portStart;
            while (used.contains(port)) port++;
            if (port > portStart + 999) throw ApiException.conflict("no_port", "No free port left for a test environment");
            String id = Ids.newId();
            jdbc.sql("INSERT INTO environments (id, workspace_id, project_id, name, host_port, url) VALUES (?, ?, ?, ?, ?, ?)")
                    .params(id, workspaceId, projectId, TEST, port, "http://localhost:" + port).update();
            events.publish(workspaceId, projectId, "environment.created", Actor.system(), new Subject("environment", id),
                    Map.of("name", TEST, "url", "http://localhost:" + port));
            return environment(projectId, TEST).orElseThrow();
        });
    }

    public Optional<Environment> environment(String projectId, String name) {
        return jdbc.sql("SELECT * FROM environments WHERE project_id = ? AND name = ?").params(projectId, name).query(this::env).optional();
    }

    public List<Environment> environments(String projectId) {
        return jdbc.sql("SELECT * FROM environments WHERE project_id = ? ORDER BY name").param(projectId).query(this::env).list();
    }

    // ================================================================== deployments

    /** develop reached main: the Lead asks the CTO to deploy it to the test environment. */
    @EventListener
    void onMainMerged(MainMerged e) {
        try {
            Actor lead = agents.lead(e.projectId()).map(Agent::id).map(Actor::agent).orElse(Actor.system());
            request(e.workspaceId(), e.projectId(), "main", e.commit(), lead, null, null);
        } catch (RuntimeException ex) {
            log.warn("Could not request the deployment of main for project {}", e.projectId(), ex);
        }
    }

    /**
     * A deployment of {@code ref} (or of {@code commit}). A human's request is his approval (audited) and starts at
     * once; an agent's request waits for a human in the Inbox.
     */
    public Deployment request(String workspaceId, String projectId, String ref, String commit, Actor actor, String releaseId,
                              String rollbackOf) {
        Path repo = git.repo(projectId).orElseThrow(() -> ApiException.conflict("no_repository", "The project has no repository yet"));
        String sha;
        try {
            sha = git.revParse(repo, commit != null ? commit : ref);
        } catch (GitService.GitException e) {
            throw ApiException.badRequest("unknown_ref", "No branch or commit " + (commit != null ? commit : ref));
        }
        Environment env = testEnvironment(workspaceId, projectId);
        boolean human = actor.kind() == Actor.Kind.HUMAN;
        String id = Ids.newId();
        Deployment d = tx.execute(s -> {
            if (!human) {
                Optional<String> waiting = jdbc.sql("SELECT id FROM deployments WHERE project_id = ? AND status = 'PENDING_APPROVAL' AND commit_sha = ?")
                        .params(projectId, sha).query(String.class).optional();
                if (waiting.isPresent()) return find(waiting.get()).orElseThrow();
            }
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('deploy' || ?))").param(projectId).query().singleRow();
            int number = jdbc.sql("SELECT coalesce(max(number), 0) + 1 FROM deployments WHERE project_id = ?").param(projectId)
                    .query(Integer.class).single();
            jdbc.sql("""
                    INSERT INTO deployments (id, workspace_id, project_id, environment_id, number, ref, commit_sha, release_id, rollback_of,
                                             status, approved_by, requested_by_kind, requested_by_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """).params(id, workspaceId, projectId, env.id(), number, ref, sha, releaseId, rollbackOf,
                    human ? "QUEUED" : "PENDING_APPROVAL", human ? actor.id() : null, actor.kind().name(),
                    actor.id() == null ? "platform" : actor.id()).update();
            Map<String, Object> details = new HashMap<>();
            details.put("number", number);
            details.put("environment", TEST);
            details.put("url", env.url());
            details.put("ref", ref);
            details.put("commit", sha);
            if (rollbackOf != null) details.put("rollbackOf", rollbackOf);
            String what = (rollbackOf != null ? "Roll back the test environment to " : "Deploy " + ref + " ") + sha.substring(0, 7)
                    + (rollbackOf != null ? "" : " to the test environment") + " (d-" + number + ")";
            if (human) {
                audit.record(workspaceId, actor, DEPLOY, "deployment", id, AuditLog.Decision.APPROVED, details);
            } else {
                String approval = approvals.request(workspaceId, projectId, actor, DEPLOY, "deployment", id, what, details);
                jdbc.sql("UPDATE deployments SET approval_id = ? WHERE id = ?").params(approval, id).update();
            }
            events.publish(workspaceId, projectId, "deployment.requested", actor, new Subject("deployment", id),
                    Map.of("number", number, "ref", ref, "commit", sha, "approved", human));
            return find(id).orElseThrow();
        });
        if (human && "QUEUED".equals(d.status())) afterCommit(() -> start(d.id()));
        return d;
    }

    @EventListener
    void onApproval(ApprovalDecided e) {
        if (!DEPLOY.equals(e.action())) return;
        Deployment d = find(e.targetId()).orElse(null);
        if (d == null || !"PENDING_APPROVAL".equals(d.status())) return;
        jdbc.sql("UPDATE deployments SET status = ?, approved_by = ? WHERE id = ?")
                .params(e.approved() ? "QUEUED" : "REJECTED", e.userId(), d.id()).update();
        events.publish(e.workspaceId(), d.projectId(), e.approved() ? "deployment.approved" : "deployment.rejected", Actor.human(e.userId()),
                new Subject("deployment", d.id()), Map.of("number", d.number()));
        if (e.approved()) afterCommit(() -> start(d.id()));
    }

    /** Redeploys the commit of an earlier healthy deployment (the CTO's click is the approval). */
    public Deployment rollback(String workspaceId, String projectId, String deploymentId, String userId) {
        Deployment target = find(deploymentId).filter(d -> d.projectId().equals(projectId)).orElseThrow(() -> ApiException.notFound("Deployment"));
        if (target.image() == null || !List.of("HEALTHY", "SUPERSEDED").contains(target.status())) {
            throw ApiException.conflict("not_deployable", "Only a deployment that was healthy can be rolled back to");
        }
        return request(workspaceId, projectId, target.ref(), target.commitSha(), Actor.human(userId), target.releaseId(), target.id());
    }

    public Environment stop(String workspaceId, String projectId, String userId) {
        Environment env = environment(projectId, TEST).orElseThrow(() -> ApiException.notFound("Environment"));
        sandboxes.stopApp(containerName(projectId));
        tx.executeWithoutResult(s -> {
            jdbc.sql("UPDATE environments SET status = 'STOPPED', updated_at = now() WHERE id = ?").param(env.id()).update();
            jdbc.sql("UPDATE deployments SET status = 'STOPPED', finished_at = coalesce(finished_at, now()) WHERE environment_id = ? AND status = 'HEALTHY'")
                    .param(env.id()).update();
            audit.record(workspaceId, Actor.human(userId), "environment.stop", "environment", env.id(), AuditLog.Decision.APPROVED, Map.of("name", TEST));
            events.publish(workspaceId, projectId, "environment.stopped", Actor.human(userId), new Subject("environment", env.id()), Map.of("name", TEST));
        });
        return environment(projectId, TEST).orElseThrow();
    }

    private void start(String deploymentId) {
        executor.submit(() -> {
            try {
                execute(deploymentId);
            } catch (RuntimeException e) {
                log.warn("Deployment {} failed", deploymentId, e);
                fail(find(deploymentId).orElseThrow(), "The platform failed: " + e.getMessage(), "");
            }
        });
    }

    /** Snapshot → docker build → replace the container → health check → live tests. */
    void execute(String deploymentId) {
        Deployment d = find(deploymentId).orElseThrow();
        if (!"QUEUED".equals(d.status())) return;
        String ws = workspaceOf(d);
        Environment env = environment(d.projectId(), TEST).orElseThrow();
        Path repo = git.repo(d.projectId()).orElseThrow();
        StringBuilder logText = new StringBuilder();
        phase(d, env, "BUILDING", "DEPLOYING");
        Path snap = security.snapshot(repo, d.commitSha());
        boolean handedOver = false;
        try {
            Path dockerfile = snap.resolve("Dockerfile");
            if (!Files.isRegularFile(dockerfile)) {
                fail(d, "The project has no Dockerfile at its root: the test environment is built from it. Add one (with EXPOSE <port>) "
                        + "and a /health route, merge it, then deploy again.", "");
                return;
            }
            int containerPort = exposedPort(Files.readString(dockerfile));
            String image = "aiw-app-" + d.projectId().toLowerCase(Locale.ROOT) + ":d" + d.number();
            log(d, logText, "$ docker build -t " + image + " (commit " + d.commitSha().substring(0, 7) + ")");
            SandboxService.Command build = sandboxes.build(snap, image, Duration.ofMinutes(15));
            log(d, logText, tail(build.output(), 60));
            if (!build.ok()) {
                fail(d, build.timedOut() ? "docker build timed out" : "docker build failed (exit " + build.exit() + ")", logText.toString());
                return;
            }
            jdbc.sql("UPDATE deployments SET image = ?, container_port = ?, status = 'STARTING' WHERE id = ?")
                    .params(image, containerPort, d.id()).update();
            tx.executeWithoutResult(s -> events.publish(ws, d.projectId(), "deployment.status_changed", Actor.system(),
                    new Subject("deployment", d.id()), Map.of("number", d.number(), "status", "STARTING")));
            String name = containerName(d.projectId());
            log(d, logText, "$ docker run " + name + " -p " + env.hostPort() + ":" + containerPort);
            SandboxService.Command run = sandboxes.runApp(name, image, env.hostPort(), containerPort,
                    Map.of("PORT", String.valueOf(containerPort), "NODE_ENV", "production"));
            if (!run.ok()) {
                fail(d, "The container did not start: " + tail(run.output(), 10), logText.toString());
                restorePrevious(d, env);
                return;
            }
            String internal = "http://" + name + ":" + containerPort;
            int status = waitHealthy(internal);
            if (status == 0 || status >= 500) {
                log(d, logText, "Health check: " + (status == 0 ? "no answer" : "HTTP " + status) + " within " + healthTimeout.toSeconds() + " s");
                log(d, logText, "--- application log ---\n" + tail(sandboxes.appLogs(name, 40), 40));
                fail(d, "The application did not answer its health check (" + (status == 0 ? "no answer" : "HTTP " + status) + ")", logText.toString());
                restorePrevious(d, env);
                return;
            }
            log(d, logText, "Health check: HTTP " + status + " · live at " + env.url());
            tx.executeWithoutResult(s -> {
                jdbc.sql("UPDATE deployments SET status = 'SUPERSEDED' WHERE environment_id = ? AND status = 'HEALTHY' AND id <> ?")
                        .params(env.id(), d.id()).update();
                jdbc.sql("UPDATE deployments SET status = 'HEALTHY', url = ?, log = ?, finished_at = now() WHERE id = ?")
                        .params(env.url(), logText.toString(), d.id()).update();
                jdbc.sql("UPDATE environments SET status = 'HEALTHY', current_deployment_id = ?, updated_at = now() WHERE id = ?")
                        .params(d.id(), env.id()).update();
                events.publish(ws, d.projectId(), "deployment.healthy", Actor.system(), new Subject("deployment", d.id()),
                        Map.of("number", d.number(), "url", env.url(), "commit", d.commitSha()));
            });
            // Live tests of what was just deployed (they cost no tokens); the snapshot is theirs now.
            security.requestDynamicScan(ws, d.projectId(), d.commitSha(), internal, snap, "deployment d-" + d.number());
            handedOver = true;
        } catch (IOException e) {
            fail(d, "Could not read the snapshot: " + e.getMessage(), logText.toString());
        } finally {
            if (!handedOver) SecurityService.delete(snap);
        }
    }

    /** The new version failed: the last healthy one is put back, so the environment keeps working. */
    private void restorePrevious(Deployment failed, Environment env) {
        Optional<Deployment> previous = env.currentDeploymentId() == null ? Optional.empty() : find(env.currentDeploymentId());
        String ws = workspaceOf(failed);
        if (previous.isEmpty() || previous.get().image() == null) {
            jdbc.sql("UPDATE environments SET status = 'UNHEALTHY', updated_at = now() WHERE id = ?").param(env.id()).update();
            return;
        }
        Deployment p = previous.get();
        SandboxService.Command again = sandboxes.runApp(containerName(p.projectId()), p.image(), env.hostPort(), p.containerPort(),
                Map.of("PORT", String.valueOf(p.containerPort()), "NODE_ENV", "production"));
        tx.executeWithoutResult(s -> {
            jdbc.sql("UPDATE environments SET status = ?, updated_at = now() WHERE id = ?").params(again.ok() ? "HEALTHY" : "UNHEALTHY", env.id()).update();
            events.publish(ws, p.projectId(), "deployment.restored", Actor.system(), new Subject("deployment", p.id()),
                    Map.of("number", p.number(), "failed", failed.number(), "ok", again.ok()));
        });
    }

    private int waitHealthy(String internal) {
        sandboxes.ensureProbeImage();
        Instant end = Instant.now().plus(healthTimeout);
        int last = 0;
        while (Instant.now().isBefore(end)) {
            int health = sandboxes.probe(internal + "/health");
            if (health >= 200 && health < 400) return health;
            int home = sandboxes.probe(internal + "/");
            if (home >= 200 && home < 500) return home;
            last = Math.max(health, home);
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return last;
            }
        }
        return last;
    }

    private void phase(Deployment d, Environment env, String status, String envStatus) {
        tx.executeWithoutResult(s -> {
            jdbc.sql("UPDATE deployments SET status = ?, started_at = coalesce(started_at, now()) WHERE id = ?").params(status, d.id()).update();
            jdbc.sql("UPDATE environments SET status = ?, updated_at = now() WHERE id = ?").params(envStatus, env.id()).update();
            events.publish(workspaceOf(d), d.projectId(), "deployment.status_changed", Actor.system(), new Subject("deployment", d.id()),
                    Map.of("number", d.number(), "status", status));
        });
    }

    private void fail(Deployment d, String error, String logText) {
        tx.executeWithoutResult(s -> {
            jdbc.sql("UPDATE deployments SET status = 'FAILED', error = ?, log = ?, finished_at = now() WHERE id = ?")
                    .params(clip(error, 2_000), logText, d.id()).update();
            jdbc.sql("UPDATE environments SET status = CASE WHEN current_deployment_id IS NULL THEN 'EMPTY' ELSE status END, updated_at = now() WHERE id = ? AND status = 'DEPLOYING'")
                    .param(d.environmentId()).update();
            events.publish(workspaceOf(d), d.projectId(), "deployment.failed", Actor.system(), new Subject("deployment", d.id()),
                    Map.of("number", d.number(), "reason", clip(error, 300)));
        });
    }

    private void log(Deployment d, StringBuilder sb, String line) {
        sb.append(line).append('\n');
        jdbc.sql("UPDATE deployments SET log = ? WHERE id = ?").params(sb.toString(), d.id()).update();
    }

    static int exposedPort(String dockerfile) {
        Matcher m = EXPOSE.matcher(dockerfile);
        return m.find() ? Integer.parseInt(m.group(1)) : 3000;
    }

    static String containerName(String projectId) {
        return "aiw-env-" + projectId.toLowerCase(Locale.ROOT) + "-test";
    }

    private static String tail(String s, int lines) {
        if (s == null) return "";
        List<String> all = s.lines().toList();
        return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
    }

    private static String clip(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    r.run();
                }
            });
        } else {
            r.run();
        }
    }

    // ================================================================== queries

    public Optional<Deployment> find(String id) {
        return jdbc.sql("SELECT * FROM deployments WHERE id = ?").param(id).query(this::deployment).optional();
    }

    public List<Deployment> deployments(String projectId, int limit) {
        return jdbc.sql("SELECT * FROM deployments WHERE project_id = ? ORDER BY number DESC LIMIT ?").params(projectId, limit)
                .query(this::deployment).list();
    }

    public Optional<String> log(String deploymentId) {
        return jdbc.sql("SELECT log FROM deployments WHERE id = ?").param(deploymentId).query(String.class).optional();
    }

    String workspaceOf(Deployment d) {
        return jdbc.sql("SELECT workspace_id FROM deployments WHERE id = ?").param(d.id()).query(String.class).single();
    }

    private Environment env(ResultSet rs, int i) throws SQLException {
        return new Environment(rs.getString("id"), rs.getString("project_id"), rs.getString("name"), rs.getString("kind"),
                rs.getInt("host_port"), rs.getString("url"), rs.getString("status"), rs.getString("current_deployment_id"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private Deployment deployment(ResultSet rs, int i) throws SQLException {
        var started = rs.getTimestamp("started_at");
        var finished = rs.getTimestamp("finished_at");
        return new Deployment(rs.getString("id"), rs.getString("project_id"), rs.getString("environment_id"), rs.getInt("number"),
                rs.getString("ref"), rs.getString("commit_sha"), rs.getString("release_id"), rs.getString("rollback_of"), rs.getString("status"),
                rs.getString("approval_id"), rs.getString("approved_by"), rs.getString("requested_by_kind"), rs.getString("requested_by_id"),
                rs.getString("image"), (Integer) rs.getObject("container_port"), rs.getString("url"), rs.getString("error"),
                rs.getTimestamp("created_at").toInstant(), started == null ? null : started.toInstant(), finished == null ? null : finished.toInstant());
    }
}
