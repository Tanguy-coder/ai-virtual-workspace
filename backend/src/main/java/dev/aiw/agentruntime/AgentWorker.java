package dev.aiw.agentruntime;

import dev.aiw.events.EventPublisher;
import dev.aiw.events.Subject;
import dev.aiw.organization.AgentService;
import dev.aiw.modelgateway.ModelCallException;
import dev.aiw.shared.Actor;
import dev.aiw.shared.ApiException;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs agent jobs. A job is claimed with a lease that a heartbeat renews while it runs; if the process dies,
 * the lease expires and another worker (or this one after a restart) claims the job again. A watchdog fails
 * jobs that make no progress, and failed jobs mark their agent as blocked so a human sees it.
 * Only one job runs at a time per agent; jobs of paused agents wait.
 */
@Component
public class AgentWorker {
    private static final Logger log = LoggerFactory.getLogger(AgentWorker.class);

    private final JdbcClient jdbc;
    private final AgentJobs jobs;
    private final AgentService agents;
    private final EventPublisher events;
    private final TransactionTemplate tx;
    private final Map<String, JobHandler> handlers;
    private final boolean enabled;
    private final int leaseSeconds;
    private final int maxRuntimeSeconds;
    private final Semaphore slots;
    private final String owner = "worker-" + ManagementFactory.getRuntimeMXBean().getName();
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    AgentWorker(JdbcClient jdbc, AgentJobs jobs, AgentService agents, EventPublisher events, TransactionTemplate tx,
                List<JobHandler> handlers,
                @Value("${aiw.worker.enabled:true}") boolean enabled,
                @Value("${aiw.worker.concurrency:8}") int concurrency,
                @Value("${aiw.worker.lease-seconds:60}") int leaseSeconds,
                @Value("${aiw.worker.max-runtime-seconds:900}") int maxRuntimeSeconds) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.agents = agents;
        this.events = events;
        this.tx = tx;
        this.handlers = handlers.stream().collect(Collectors.toMap(JobHandler::type, Function.identity()));
        this.enabled = enabled;
        this.leaseSeconds = leaseSeconds;
        this.maxRuntimeSeconds = maxRuntimeSeconds;
        this.slots = new Semaphore(concurrency);
    }

    public Optional<JobHandler> handler(String type) {
        return Optional.ofNullable(handlers.get(type));
    }

    @Scheduled(fixedDelayString = "${aiw.worker.poll-ms:300}")
    void poll() {
        if (!enabled) return;
        while (slots.tryAcquire()) {
            Optional<AgentJob> claimed;
            try {
                claimed = claim();
            } catch (RuntimeException e) {
                slots.release();
                log.warn("Job claim failed", e);
                return;
            }
            if (claimed.isEmpty()) {
                slots.release();
                return;
            }
            AgentJob job = claimed.get();
            running.add(job.id());
            executor.submit(() -> {
                try {
                    run(job);
                } finally {
                    running.remove(job.id());
                    slots.release();
                }
            });
        }
    }

    private Optional<AgentJob> claim() {
        return jdbc.sql("""
                UPDATE agent_jobs j SET status = 'RUNNING', lease_owner = :owner,
                       lease_until = now() + make_interval(secs => :lease), attempts = attempts + 1, started_at = now()
                WHERE j.id = (
                    SELECT c.id FROM agent_jobs c JOIN agents a ON a.id = c.agent_id
                    WHERE ((c.status = 'QUEUED' AND c.run_after <= now()) OR (c.status = 'RUNNING' AND c.lease_until < now()))
                      AND a.status = 'ACTIVE'
                      AND NOT EXISTS (SELECT 1 FROM agent_jobs r WHERE r.agent_id = c.agent_id AND r.id <> c.id
                                      AND r.status = 'RUNNING' AND r.lease_until >= now())
                    ORDER BY c.created_at
                    FOR UPDATE OF c SKIP LOCKED LIMIT 1)
                RETURNING j.*
                """).param("owner", owner).param("lease", leaseSeconds).query(jobs::job).optional();
    }

    private void run(AgentJob job) {
        JobHandler handler = handlers.get(job.type());
        if (handler == null) {
            fail(job, "No handler for job type " + job.type(), false);
            return;
        }
        if (job.attempts() > job.maxAttempts()) {
            fail(job, "The job was interrupted too many times", false);
            return;
        }
        try {
            handler.handle(job);
            tx.executeWithoutResult(s -> {
                int updated = jdbc.sql("""
                        UPDATE agent_jobs SET status = 'DONE', finished_at = now(), lease_owner = NULL, lease_until = NULL
                        WHERE id = ? AND status = 'RUNNING' AND lease_owner = ?
                        """).params(job.id(), owner).update();
                if (updated == 1) {
                    events.publish(job.workspaceId(), job.projectId(), "job.completed", Actor.agent(job.agentId()),
                            new Subject("job", job.id()), Map.of("type", job.type(), "agentId", job.agentId()));
                }
            });
        } catch (JobFailure | ApiException e) {
            fail(job, e.getMessage(), false);
        } catch (RuntimeException e) {
            ModelCallException limit = providerLimit(e);
            if (limit != null) {
                defer(job, limit);
                return;
            }
            log.warn("Job {} ({}) attempt {} failed", job.id(), job.type(), job.attempts(), e);
            fail(job, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), true);
        }
    }

    /** A provider limit with a known wait longer than the gateway holds a call (Groq's daily quota, for instance). */
    private static ModelCallException providerLimit(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ModelCallException m && m.statusCode() == 429 && m.retryAfterSeconds() > 0) return m;
        }
        return null;
    }

    /** The job goes back to the queue until the provider accepts calls again; the attempt does not count. */
    private void defer(AgentJob job, ModelCallException limit) {
        long wait = Math.min(limit.retryAfterSeconds() + 5, 24 * 3600);
        tx.executeWithoutResult(s -> {
            int updated = jdbc.sql("""
                    UPDATE agent_jobs SET status = 'QUEUED', attempts = greatest(attempts - 1, 0), last_error = ?,
                                          lease_owner = NULL, lease_until = NULL, run_after = now() + make_interval(secs => ?)
                    WHERE id = ? AND status = 'RUNNING'
                    """).params(limit.getMessage(), wait, job.id()).update();
            if (updated == 0) return;
            events.publish(job.workspaceId(), job.projectId(), "job.deferred", Actor.agent(job.agentId()),
                    new Subject("job", job.id()), Map.of("type", job.type(), "agentId", job.agentId(), "reason", limit.getMessage(),
                            "waitSeconds", wait));
            JobHandler handler = handlers.get(job.type());
            if (handler != null) handler.onDeferred(job, limit.getMessage());
        });
    }

    private void fail(AgentJob job, String error, boolean retryable) {
        if (retryable && job.attempts() < job.maxAttempts()) {
            jdbc.sql("""
                    UPDATE agent_jobs SET status = 'QUEUED', last_error = ?, lease_owner = NULL, lease_until = NULL,
                                          run_after = now() + make_interval(secs => ?)
                    WHERE id = ? AND status = 'RUNNING'
                    """).params(error, 5 * job.attempts(), job.id()).update();
            return;
        }
        tx.executeWithoutResult(s -> {
            int updated = jdbc.sql("""
                    UPDATE agent_jobs SET status = 'FAILED', last_error = ?, finished_at = now(), lease_owner = NULL,
                                          lease_until = NULL
                    WHERE id = ? AND status IN ('RUNNING', 'QUEUED')
                    """).params(error, job.id()).update();
            if (updated == 0) return;
            agents.setResting(job.agentId(), "BLOCKED", error);
            events.publish(job.workspaceId(), job.projectId(), "job.failed", Actor.agent(job.agentId()),
                    new Subject("job", job.id()), Map.of("type", job.type(), "agentId", job.agentId(), "error", error));
            JobHandler handler = handlers.get(job.type());
            if (handler != null) handler.onFailed(job, error);
        });
    }

    @Scheduled(fixedDelayString = "${aiw.worker.heartbeat-ms:15000}")
    void heartbeat() {
        if (running.isEmpty()) return;
        jdbc.sql("""
                UPDATE agent_jobs SET lease_until = now() + make_interval(secs => :lease)
                WHERE lease_owner = :owner AND status = 'RUNNING' AND id IN (:ids)
                """).param("lease", leaseSeconds).param("owner", owner).param("ids", List.copyOf(running)).update();
    }

    /** Fails jobs running for too long and jobs whose agent was dismissed. */
    @Scheduled(fixedDelayString = "${aiw.worker.watchdog-ms:30000}")
    void watchdog() {
        if (!enabled) return;
        List<String> stuck = jdbc.sql("""
                SELECT id FROM agent_jobs
                WHERE status = 'RUNNING' AND started_at < now() - make_interval(secs => ?)
                """).param(maxRuntimeSeconds).query(String.class).list();
        for (String id : stuck) {
            fail(jobs.claimView(id), "Watchdog: no result after " + maxRuntimeSeconds / 60 + " minutes", false);
        }
        List<String> orphaned = jdbc.sql("""
                SELECT j.id FROM agent_jobs j JOIN agents a ON a.id = j.agent_id
                WHERE j.status = 'QUEUED' AND a.status = 'DISMISSED'
                """).query(String.class).list();
        for (String id : orphaned) {
            AgentJob job = jobs.claimView(id);
            tx.executeWithoutResult(s -> {
                jdbc.sql("UPDATE agent_jobs SET status = 'CANCELLED', finished_at = now(), last_error = ? WHERE id = ?")
                        .params("The agent was dismissed", id).update();
                JobHandler handler = handlers.get(job.type());
                if (handler != null) handler.onFailed(job, "The agent was dismissed");
            });
        }
    }
}
