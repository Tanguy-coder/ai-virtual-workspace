package dev.aiw.agentruntime;

import dev.aiw.events.EventPublisher;
import dev.aiw.events.Subject;
import dev.aiw.governance.AuditLog;
import dev.aiw.organization.AgentService;
import dev.aiw.shared.Actor;
import dev.aiw.shared.ApiException;
import dev.aiw.shared.Ids;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Persistent queue of agent work. Jobs are claimed by {@link AgentWorker} under a lease renewed by heartbeat. */
@Service
public class AgentJobs {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final EventPublisher events;
    private final AuditLog audit;
    private final AgentService agents;

    AgentJobs(JdbcClient jdbc, JsonMapper json, EventPublisher events, AuditLog audit, AgentService agents) {
        this.jdbc = jdbc;
        this.json = json;
        this.events = events;
        this.audit = audit;
        this.agents = agents;
    }

    /** Queues a job; call inside the transaction that creates the work so both commit together. */
    @Transactional
    public String enqueue(String workspaceId, String projectId, String agentId, String type, Map<String, ?> payload,
                          String correlationId) {
        String id = Ids.newId();
        jdbc.sql("""
                INSERT INTO agent_jobs (id, workspace_id, project_id, agent_id, type, payload, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                """).params(id, workspaceId, projectId, agentId, type, json.writeValueAsString(payload), correlationId)
                .update();
        events.publish(workspaceId, projectId, "job.queued", Actor.system(), new Subject("job", id),
                Map.of("type", type, "agentId", agentId));
        return id;
    }

    public List<JobView> list(String projectId, boolean openOnly) {
        String filter = openOnly ? " AND status IN ('QUEUED', 'RUNNING', 'FAILED')" : "";
        return jdbc.sql("SELECT * FROM agent_jobs WHERE project_id = ?" + filter + " ORDER BY created_at DESC LIMIT 200")
                .param(projectId).query(this::view).list();
    }

    public Optional<JobView> find(String jobId) {
        return jdbc.sql("SELECT * FROM agent_jobs WHERE id = ?").param(jobId).query(this::view).optional();
    }

    public List<AgentStep> steps(String agentId, int limit) {
        return jdbc.sql("SELECT * FROM agent_steps WHERE agent_id = ? ORDER BY created_at DESC LIMIT ?")
                .params(agentId, Math.min(Math.max(limit, 1), 500)).query(this::step).list();
    }

    /** A human retries a failed job: attempts are reset and the agent is no longer shown as blocked. */
    @Transactional
    public JobView retry(String jobId, String userId, JobHandler handler) {
        JobView job = find(jobId).orElseThrow(() -> ApiException.notFound("Job"));
        if (!"FAILED".equals(job.status())) throw ApiException.conflict("not_failed", "Only failed jobs can be retried");
        AgentJob agentJob = claimView(jobId);
        jdbc.sql("""
                UPDATE agent_jobs SET status = 'QUEUED', attempts = 0, run_after = now(), last_error = NULL,
                                      lease_owner = NULL, lease_until = NULL, finished_at = NULL WHERE id = ?
                """).param(jobId).update();
        agents.setResting(job.agentId(), "AVAILABLE", null);
        if (handler != null) handler.onRetry(agentJob);
        Actor actor = Actor.human(userId);
        events.publish(agentJob.workspaceId(), job.projectId(), "job.retried", actor, new Subject("job", jobId),
                Map.of("type", job.type(), "agentId", job.agentId()));
        audit.record(agentJob.workspaceId(), actor, "job.retry", "job", jobId, AuditLog.Decision.RECORDED,
                Map.of("type", job.type()));
        return find(jobId).orElseThrow();
    }

    @Transactional
    public void cancelOpen(String projectId, String type, String key, String value) {
        jdbc.sql("""
                UPDATE agent_jobs SET status = 'CANCELLED', finished_at = now()
                WHERE project_id = ? AND type = ? AND status IN ('QUEUED', 'FAILED') AND payload ->> ? = ?
                """).params(projectId, type, key, value).update();
    }

    String workspaceOf(String jobId) {
        return jdbc.sql("SELECT workspace_id FROM agent_jobs WHERE id = ?").param(jobId).query(String.class).single();
    }

    AgentJob claimView(String jobId) {
        return jdbc.sql("SELECT * FROM agent_jobs WHERE id = ?").param(jobId).query(this::job).single();
    }

    AgentJob job(ResultSet rs, int row) throws SQLException {
        return new AgentJob(rs.getString("id"), rs.getString("workspace_id"), rs.getString("project_id"),
                rs.getString("agent_id"), rs.getString("type"), json.readValue(rs.getString("payload"), MAP),
                rs.getInt("attempts"), rs.getInt("max_attempts"), rs.getString("correlation_id"));
    }

    private JobView view(ResultSet rs, int row) throws SQLException {
        return new JobView(rs.getString("id"), rs.getString("project_id"), rs.getString("agent_id"),
                rs.getString("type"), json.readValue(rs.getString("payload"), MAP), rs.getString("status"),
                rs.getInt("attempts"), rs.getInt("max_attempts"), rs.getString("last_error"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")));
    }

    private AgentStep step(ResultSet rs, int row) throws SQLException {
        BigDecimal cost = rs.getBigDecimal("cost_usd");
        return new AgentStep(rs.getString("id"), rs.getString("agent_id"), rs.getString("job_id"), rs.getString("kind"),
                rs.getString("summary"), rs.getString("model"), rs.getLong("input_tokens"), rs.getLong("output_tokens"),
                cost, rs.getLong("duration_ms"), rs.getString("outcome"), rs.getString("error"),
                instant(rs.getTimestamp("created_at")));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
