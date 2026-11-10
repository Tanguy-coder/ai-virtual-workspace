package dev.aiw.quality;

import dev.aiw.events.EventPublisher;
import dev.aiw.events.Subject;
import dev.aiw.execution.SandboxService;
import dev.aiw.shared.Actor;
import dev.aiw.shared.Ids;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a project's tests in a sandbox and records the result. The platform runs them, so a passing test is a fact,
 * not an agent's claim; runs cost no tokens.
 */
@Service
public class TestRunService {
    private final SandboxService sandboxes;
    private final JdbcClient jdbc;
    private final EventPublisher events;
    private final TransactionTemplate tx;

    TestRunService(SandboxService sandboxes, JdbcClient jdbc, EventPublisher events, TransactionTemplate tx) {
        this.sandboxes = sandboxes;
        this.jdbc = jdbc;
        this.events = events;
        this.tx = tx;
    }

    public record Context(String workspaceId, String projectId, String taskId, String runId, String mrId, String branch,
                          String commitSha, String trigger) {}

    /** Empty when the project has no test command yet. */
    public Optional<TestRun> run(Context c, String containerId, Path worktree) {
        TestOutput.Plan plan = TestOutput.plan(worktree);
        if (plan == null) return Optional.empty();
        StringBuilder log = new StringBuilder();
        long total = 0;
        for (String setup : plan.setup()) {
            SandboxService.Command s = sandboxes.exec(containerId, setup, Duration.ofMinutes(10));
            total += s.durationMs();
            log.append("$ ").append(setup).append('\n').append(s.output()).append('\n');
            if (!s.ok()) return Optional.of(record(c, plan.command(), "ERROR", null, null, total,
                    "Setup failed: " + setup + "\n" + TestOutput.failingExcerpt(s.output(), 30), log.toString()));
        }
        SandboxService.Command r = sandboxes.exec(containerId, plan.command(), null);
        total += r.durationMs();
        log.append("$ ").append(plan.command()).append('\n').append(r.output());
        TestOutput.Counts counts = TestOutput.counts(r.output());
        String status = r.timedOut() ? "ERROR" : r.ok() ? "PASSED" : "FAILED";
        String excerpt = r.ok() ? "" : r.timedOut() ? "The test command timed out." : TestOutput.failingExcerpt(r.output(), 40);
        if (r.ok() && counts.passed() != null && counts.passed() == 0) {
            status = "FAILED";
            excerpt = "The test command ran no test.";
        }
        return Optional.of(record(c, plan.command(), status, counts.passed(), counts.failed(), total, excerpt, log.toString()));
    }

    private TestRun record(Context c, String command, String status, Integer passed, Integer failed, long ms,
                           String excerpt, String output) {
        String id = Ids.newId();
        tx.executeWithoutResult(s -> {
            int number = jdbc.sql("SELECT coalesce(max(number), 0) + 1 FROM test_runs WHERE project_id = ?")
                    .param(c.projectId()).query(Integer.class).single();
            jdbc.sql("""
                    INSERT INTO test_runs (id, workspace_id, project_id, number, task_id, run_id, mr_id, branch, commit_sha,
                                           trigger, command, status, passed, failed, duration_ms, failing_excerpt, output)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """).params(id, c.workspaceId(), c.projectId(), number, c.taskId(), c.runId(), c.mrId(), c.branch(),
                    c.commitSha(), c.trigger(), command, status, passed, failed, ms, excerpt, output).update();
            Map<String, Object> payload = new HashMap<>();
            payload.put("number", number);
            payload.put("status", status);
            payload.put("branch", c.branch());
            payload.put("passed", passed);
            payload.put("failed", failed);
            payload.put("taskId", c.taskId());
            payload.put("trigger", c.trigger());
            events.publish(c.workspaceId(), c.projectId(), "test_run.completed", Actor.system(),
                    new Subject("test_run", id), payload);
        });
        return find(id).orElseThrow();
    }

    public Optional<TestRun> find(String id) {
        return jdbc.sql("SELECT * FROM test_runs WHERE id = ?").param(id).query(this::map).optional();
    }

    public Optional<String> output(String id) {
        return jdbc.sql("SELECT output FROM test_runs WHERE id = ?").param(id).query(String.class).optional();
    }

    public List<TestRun> list(String projectId, String taskId, int limit) {
        return jdbc.sql("""
                SELECT * FROM test_runs WHERE project_id = ? AND (CAST(? AS TEXT) IS NULL OR task_id = ?)
                ORDER BY created_at DESC LIMIT ?
                """).params(projectId, taskId, taskId, limit).query(this::map).list();
    }

    private TestRun map(ResultSet rs, int row) throws SQLException {
        return new TestRun(rs.getString("id"), rs.getString("project_id"), rs.getInt("number"), rs.getString("task_id"),
                rs.getString("run_id"), rs.getString("mr_id"), rs.getString("branch"), rs.getString("commit_sha"),
                rs.getString("trigger"), rs.getString("command"), rs.getString("status"),
                (Integer) rs.getObject("passed"), (Integer) rs.getObject("failed"), rs.getLong("duration_ms"),
                rs.getString("failing_excerpt"), rs.getTimestamp("created_at").toInstant());
    }
}
