package dev.aiw.orchestration;

import dev.aiw.events.EventPublisher;
import dev.aiw.events.Subject;
import dev.aiw.shared.Actor;
import dev.aiw.shared.Ids;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Persistence of runs and their steps. Every step is also an event, so the UI follows the work live. */
@Component
class TaskRuns {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private final JdbcClient jdbc;
    private final EventPublisher events;
    private final JsonMapper json;

    TaskRuns(JdbcClient jdbc, EventPublisher events, JsonMapper json) {
        this.jdbc = jdbc;
        this.events = events;
        this.json = json;
    }

    TaskRun create(String workspaceId, String projectId, String taskId, String agentId, String branch, String worktree,
                   String image, long budget) {
        String id = Ids.newId();
        jdbc.sql("""
                INSERT INTO task_runs (id, workspace_id, project_id, task_id, agent_id, branch, worktree, image, status, token_budget)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'RUNNING', ?)
                """).params(id, workspaceId, projectId, taskId, agentId, branch, worktree, image, budget).update();
        return find(id).orElseThrow();
    }

    Optional<TaskRun> find(String id) {
        return jdbc.sql("SELECT * FROM task_runs WHERE id = ?").param(id).query(this::map).optional();
    }

    Optional<TaskRun> openForTask(String taskId) {
        return jdbc.sql("SELECT * FROM task_runs WHERE task_id = ? AND status IN ('RUNNING', 'PAUSED', 'SUBMITTED', 'BLOCKED') ORDER BY started_at DESC LIMIT 1")
                .param(taskId).query(this::map).optional();
    }

    Optional<TaskRun> latestForTask(String taskId) {
        return jdbc.sql("SELECT * FROM task_runs WHERE task_id = ? ORDER BY started_at DESC LIMIT 1")
                .param(taskId).query(this::map).optional();
    }

    List<TaskRun> list(String projectId) {
        return jdbc.sql("SELECT * FROM task_runs WHERE project_id = ? ORDER BY started_at DESC LIMIT 200")
                .param(projectId).query(this::map).list();
    }

    /** Agents busy on a run (an agent works on one task at a time; a submitted run frees it). */
    boolean agentBusy(String agentId) {
        return jdbc.sql("SELECT count(*) FROM task_runs WHERE agent_id = ? AND status IN ('RUNNING', 'PAUSED')")
                .param(agentId).query(Long.class).single() > 0;
    }

    void status(TaskRun run, String status, String reason) {
        jdbc.sql("""
                UPDATE task_runs SET status = ?, blocked_reason = ?, pause_requested = false, updated_at = now(),
                       ended_at = CASE WHEN ? IN ('DONE', 'CANCELLED') THEN now() ELSE NULL END,
                       segment_steps = CASE WHEN ? = 'RUNNING' AND status <> 'RUNNING' THEN 0 ELSE segment_steps END
                WHERE id = ?
                """).params(status, reason, status, status, run.id()).update();
        Map<String, Object> payload = new HashMap<>();
        payload.put("taskId", run.taskId());
        payload.put("agentId", run.agentId());
        payload.put("status", status);
        if (reason != null) payload.put("reason", reason);
        events.publish(workspaceOf(run), run.projectId(), "task_run.status_changed", Actor.system(),
                new Subject("task_run", run.id()), payload);
    }

    void requestPause(String runId, boolean pause) {
        jdbc.sql("UPDATE task_runs SET pause_requested = ?, updated_at = now() WHERE id = ?").params(pause, runId).update();
    }

    void sandbox(String runId, String sandboxId, String image) {
        jdbc.sql("UPDATE task_runs SET sandbox_id = ?, image = ?, updated_at = now() WHERE id = ?").params(sandboxId, image, runId).update();
    }

    void usage(String runId, long in, long out, long cacheRead, BigDecimal cost) {
        jdbc.sql("""
                UPDATE task_runs SET input_tokens = input_tokens + ?, output_tokens = output_tokens + ?,
                       cache_read_tokens = cache_read_tokens + ?, cost_usd = cost_usd + ?, steps = steps + 1,
                       segment_steps = segment_steps + 1, updated_at = now() WHERE id = ?
                """).params(in, out, cacheRead, cost, runId).update();
    }

    RunStep step(TaskRun run, String kind, String tool, String summary, Map<String, ?> detail, String outcome,
                 long in, long out, long ms) {
        String id = Ids.newId();
        int seq = jdbc.sql("SELECT coalesce(max(seq), 0) + 1 FROM run_steps WHERE run_id = ?").param(run.id())
                .query(Integer.class).single();
        String clipped = summary.length() > 500 ? summary.substring(0, 500) + "…" : summary;
        jdbc.sql("""
                INSERT INTO run_steps (id, run_id, seq, kind, tool, summary, detail, outcome, input_tokens, output_tokens, duration_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                """).params(id, run.id(), seq, kind, tool, clipped, json.writeValueAsString(detail == null ? Map.of() : detail),
                outcome, in, out, ms).update();
        Map<String, Object> payload = new HashMap<>();
        payload.put("runId", run.id());
        payload.put("taskId", run.taskId());
        payload.put("agentId", run.agentId());
        payload.put("seq", seq);
        payload.put("kind", kind);
        payload.put("summary", clipped);
        payload.put("outcome", outcome);
        if (tool != null) payload.put("tool", tool);
        events.publish(workspaceOf(run), run.projectId(), "task_run.step", Actor.agent(run.agentId()),
                new Subject("task_run", run.id()), payload);
        return new RunStep(id, run.id(), seq, kind, tool, clipped, Map.copyOf(detail == null ? Map.of() : detail), outcome, in, out, ms, java.time.Instant.now());
    }

    List<RunStep> steps(String runId) {
        return jdbc.sql("SELECT * FROM run_steps WHERE run_id = ? ORDER BY seq").param(runId).query((rs, i) ->
                new RunStep(rs.getString("id"), rs.getString("run_id"), rs.getInt("seq"), rs.getString("kind"),
                        rs.getString("tool"), rs.getString("summary"), json.readValue(rs.getString("detail"), MAP),
                        rs.getString("outcome"), rs.getLong("input_tokens"), rs.getLong("output_tokens"),
                        rs.getLong("duration_ms"), rs.getTimestamp("created_at").toInstant())).list();
    }

    String workspaceOf(TaskRun run) {
        return jdbc.sql("SELECT workspace_id FROM task_runs WHERE id = ?").param(run.id()).query(String.class).single();
    }

    private TaskRun map(ResultSet rs, int row) throws SQLException {
        var ended = rs.getTimestamp("ended_at");
        return new TaskRun(rs.getString("id"), rs.getString("project_id"), rs.getString("task_id"), rs.getString("agent_id"),
                rs.getString("branch"), rs.getString("worktree"), rs.getString("sandbox_id"), rs.getString("image"),
                rs.getString("status"), rs.getBoolean("pause_requested"), rs.getInt("steps"), rs.getInt("segment_steps"),
                rs.getLong("input_tokens"), rs.getLong("output_tokens"), rs.getLong("cache_read_tokens"),
                rs.getLong("token_budget"), rs.getBigDecimal("cost_usd"), rs.getString("blocked_reason"),
                rs.getTimestamp("started_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                ended == null ? null : ended.toInstant());
    }
}
