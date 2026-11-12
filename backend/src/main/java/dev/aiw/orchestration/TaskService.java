package dev.aiw.orchestration;

import dev.aiw.collaboration.CollaborationService;
import dev.aiw.events.EventPublisher;
import dev.aiw.events.Subject;
import dev.aiw.governance.AuditLog;
import dev.aiw.organization.Agent;
import dev.aiw.organization.AgentService;
import dev.aiw.shared.Actor;
import dev.aiw.shared.ApiException;
import dev.aiw.shared.Ids;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The task graph. A task is READY only when every dependency is DONE, and becomes ASSIGNED when it is ready and
 * has an assignee. Humans can create, edit, assign, block and cancel; in-progress, review, test and validation
 * states are reached only through execution and the validation engine (increment 4).
 */
@Service
public class TaskService {
    private static final TypeReference<List<Task.Criterion>> CRITERIA = new TypeReference<>() {};
    static final Set<String> CLOSED = Set.of("DONE", "CANCELLED");

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final EventPublisher events;
    private final AuditLog audit;
    private final AgentService agents;
    private final CollaborationService collaboration;

    TaskService(JdbcClient jdbc, JsonMapper json, EventPublisher events, AuditLog audit, AgentService agents,
                CollaborationService collaboration) {
        this.jdbc = jdbc;
        this.json = json;
        this.events = events;
        this.audit = audit;
        this.agents = agents;
        this.collaboration = collaboration;
    }

    public record Draft(String title, String description, List<String> acceptanceCriteria, String priority,
                        BigDecimal estimateDays, String assigneeId) {}

    // ---- queries ----

    public List<Task> list(String projectId) {
        Map<String, List<String>> deps = dependencies(projectId);
        return jdbc.sql("SELECT * FROM tasks WHERE project_id = ? ORDER BY number").param(projectId)
                .query((rs, row) -> map(rs, deps)).list();
    }

    public Optional<Task> find(String taskId) {
        Optional<String> project = jdbc.sql("SELECT project_id FROM tasks WHERE id = ?").param(taskId).query(String.class).optional();
        if (project.isEmpty()) return Optional.empty();
        Map<String, List<String>> deps = dependencies(project.get());
        return jdbc.sql("SELECT * FROM tasks WHERE id = ?").param(taskId).query((rs, row) -> map(rs, deps)).optional();
    }

    public Map<String, Long> countByStatus(String projectId) {
        Map<String, Long> counts = new HashMap<>();
        jdbc.sql("SELECT status, count(*) FROM tasks WHERE project_id = ? GROUP BY status").param(projectId)
                .query((ResultSet rs) -> {
                    counts.put(rs.getString(1), rs.getLong(2));
                });
        return counts;
    }

    public boolean hasPlanFrom(String projectId, String sourceId) {
        return jdbc.sql("SELECT count(*) FROM tasks WHERE project_id = ? AND source_kind = 'PLAN' AND source_id = ?")
                .params(projectId, sourceId).query(Long.class).single() > 0;
    }

    // ---- commands ----

    @Transactional
    public Task create(String workspaceId, String projectId, Draft d, String sourceKind, String sourceId, Actor actor) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?))").param(projectId).query().singleRow();
        int number = jdbc.sql("SELECT coalesce(max(number), 0) + 1 FROM tasks WHERE project_id = ?").param(projectId)
                .query(Integer.class).single();
        if (d.assigneeId() != null) requireAssignable(projectId, d.assigneeId());
        String id = Ids.newId();
        List<Task.Criterion> criteria = d.acceptanceCriteria() == null ? List.of()
                : d.acceptanceCriteria().stream().filter(c -> !c.isBlank()).map(c -> new Task.Criterion(c.trim(), false)).toList();
        jdbc.sql("""
                INSERT INTO tasks (id, workspace_id, project_id, number, title, description, acceptance_criteria, status,
                                   priority, estimate_days, assignee_id, source_kind, source_id, created_by_kind, created_by_id)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, 'CREATED', ?, ?, ?, ?, ?, ?, ?)
                """).params(id, workspaceId, projectId, number, d.title().trim(),
                d.description() == null ? "" : d.description().trim(), json.writeValueAsString(criteria),
                d.priority() == null ? "MEDIUM" : d.priority(), d.estimateDays(), d.assigneeId(), sourceKind, sourceId,
                actor.kind().name(), actor.id()).update();
        Map<String, Object> payload = new HashMap<>();
        payload.put("number", number);
        payload.put("title", d.title().trim());
        payload.put("source", sourceKind);
        if (d.assigneeId() != null) payload.put("assigneeId", d.assigneeId());
        events.publish(workspaceId, projectId, "task.created", actor, new Subject("task", id), payload);
        if (d.assigneeId() != null) {
            events.publish(workspaceId, projectId, "task.assigned", actor, new Subject("task", id),
                    Map.of("number", number, "title", d.title().trim(), "assigneeId", d.assigneeId()));
        }
        return find(id).orElseThrow();
    }

    /** Adds dependencies and then settles the task's status. Rejects any edge that would create a cycle. */
    @Transactional
    public void addDependencies(String taskId, List<String> dependsOn, Actor actor) {
        Task task = find(taskId).orElseThrow(() -> ApiException.notFound("Task"));
        for (String dep : dependsOn) {
            Task other = find(dep).filter(t -> t.projectId().equals(task.projectId()))
                    .orElseThrow(() -> ApiException.badRequest("invalid_dependency", "Unknown task " + dep));
            if (task.dependsOn().contains(dep)) continue;
            if (dep.equals(taskId) || reaches(task.projectId(), dep, taskId)) {
                throw ApiException.conflict("dependency_cycle",
                        "T-" + task.number() + " cannot depend on T-" + other.number() + ": it would create a cycle");
            }
            jdbc.sql("INSERT INTO task_dependencies (task_id, depends_on_id) VALUES (?, ?)").params(taskId, dep).update();
            events.publish(workspaceOf(task), task.projectId(), "task.dependency_added", actor, new Subject("task", taskId),
                    Map.of("dependsOn", dep, "dependsOnNumber", other.number()));
        }
        settle(taskId, actor);
    }

    @Transactional
    public Task removeDependency(String taskId, String dependsOn, Actor actor) {
        Task task = find(taskId).orElseThrow(() -> ApiException.notFound("Task"));
        int removed = jdbc.sql("DELETE FROM task_dependencies WHERE task_id = ? AND depends_on_id = ?")
                .params(taskId, dependsOn).update();
        if (removed > 0) {
            events.publish(workspaceOf(task), task.projectId(), "task.dependency_removed", actor,
                    new Subject("task", taskId), Map.of("dependsOn", dependsOn));
            settle(taskId, actor);
        }
        return find(taskId).orElseThrow();
    }

    @Transactional
    public Task update(String taskId, Draft d, Actor actor) {
        Task task = open(taskId);
        List<Task.Criterion> criteria = d.acceptanceCriteria() == null ? task.acceptanceCriteria()
                : d.acceptanceCriteria().stream().filter(c -> !c.isBlank()).map(c -> new Task.Criterion(c.trim(), false)).toList();
        jdbc.sql("""
                UPDATE tasks SET title = ?, description = ?, acceptance_criteria = ?::jsonb, priority = ?, estimate_days = ?,
                                 updated_at = now() WHERE id = ?
                """).params(d.title() == null ? task.title() : d.title().trim(),
                d.description() == null ? task.description() : d.description().trim(), json.writeValueAsString(criteria),
                d.priority() == null ? task.priority() : d.priority(),
                d.estimateDays() == null ? task.estimateDays() : d.estimateDays(), taskId).update();
        events.publish(workspaceOf(task), task.projectId(), "task.updated", actor, new Subject("task", taskId),
                Map.of("number", task.number()));
        return find(taskId).orElseThrow();
    }

    /** Sets or clears the assignee. A ready task becomes ASSIGNED; a task waiting on dependencies keeps it for later. */
    @Transactional
    public Task assign(String taskId, String agentId, Actor actor) {
        Task task = open(taskId);
        if (List.of("IN_PROGRESS", "IN_REVIEW", "IN_TEST", "VALIDATION").contains(task.status())) {
            throw ApiException.conflict("task_running", "Work on this task has started; it cannot be reassigned yet");
        }
        if (agentId != null) requireAssignable(task.projectId(), agentId);
        jdbc.sql("UPDATE tasks SET assignee_id = ?, updated_at = now() WHERE id = ?").params(agentId, taskId).update();
        if (agentId == null && "ASSIGNED".equals(task.status())) {
            setStatus(task, "READY", actor, Map.of());
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("number", task.number());
        payload.put("title", task.title());
        payload.put("assigneeId", agentId);
        if (task.assigneeId() != null) payload.put("previousAssigneeId", task.assigneeId());
        events.publish(workspaceOf(task), task.projectId(), agentId == null ? "task.unassigned" : "task.assigned", actor,
                new Subject("task", taskId), payload);
        if (actor.kind() == Actor.Kind.HUMAN) {
            audit.record(workspaceOf(task), actor, "task.assign", "task", taskId, AuditLog.Decision.RECORDED,
                    Map.of("assigneeId", String.valueOf(agentId)));
        }
        settle(taskId, actor);
        return find(taskId).orElseThrow();
    }

    @Transactional
    public Task block(String taskId, String reason, Actor actor) {
        Task task = open(taskId);
        if ("BLOCKED".equals(task.status())) return task;
        jdbc.sql("UPDATE tasks SET status_before_block = status, blocked_reason = ? WHERE id = ?").params(reason, taskId).update();
        setStatus(task, "BLOCKED", actor, Map.of("reason", reason));
        events.publish(workspaceOf(task), task.projectId(), "task.blocked", actor, new Subject("task", taskId),
                Map.of("number", task.number(), "reason", reason));
        return find(taskId).orElseThrow();
    }

    @Transactional
    public Task unblock(String taskId, Actor actor) {
        Task task = open(taskId);
        if (!"BLOCKED".equals(task.status())) throw ApiException.conflict("not_blocked", "The task is not blocked");
        String before = jdbc.sql("SELECT coalesce(status_before_block, 'CREATED') FROM tasks WHERE id = ?").param(taskId)
                .query(String.class).single();
        jdbc.sql("UPDATE tasks SET blocked_reason = NULL, status_before_block = NULL WHERE id = ?").param(taskId).update();
        setStatus(task, before, actor, Map.of("reason", "unblocked"));
        settle(taskId, actor);
        return find(taskId).orElseThrow();
    }

    @Transactional
    public Task cancel(String taskId, Actor actor) {
        Task task = open(taskId);
        setStatus(task, "CANCELLED", actor, Map.of());
        audit.record(workspaceOf(task), actor, "task.cancel", "task", taskId, AuditLog.Decision.RECORDED,
                Map.of("number", String.valueOf(task.number())));
        // A cancelled task is no longer awaited: its dependents may be ready now.
        jdbc.sql("SELECT task_id FROM task_dependencies WHERE depends_on_id = ?").param(taskId).query(String.class).list()
                .forEach(id -> settle(id, actor));
        return find(taskId).orElseThrow();
    }

    /** Called when a task reaches DONE (validation engine): dependents whose dependencies are all done become ready. */
    @Transactional
    public void onTaskDone(String taskId) {
        List<String> dependents = jdbc.sql("SELECT task_id FROM task_dependencies WHERE depends_on_id = ?").param(taskId)
                .query(String.class).list();
        dependents.forEach(id -> settle(id, Actor.system()));
    }

    /**
     * The tasks that waited for {@code fromTaskId} now wait for {@code toTaskIds} instead (the task was split).
     * The new edges cannot create a cycle: the new tasks only depend on what the original depended on.
     */
    void transferDependents(String fromTaskId, List<String> toTaskIds, Actor actor) {
        List<String> dependents = jdbc.sql("SELECT task_id FROM task_dependencies WHERE depends_on_id = ?").param(fromTaskId)
                .query(String.class).list();
        jdbc.sql("DELETE FROM task_dependencies WHERE depends_on_id = ?").param(fromTaskId).update();
        for (String dependent : dependents) {
            Task task = find(dependent).orElseThrow();
            if (CLOSED.contains(task.status())) continue;
            addDependencies(dependent, toTaskIds, actor);
        }
    }

    /** The branch a task's run starts on instead of a new one (a split task continuing committed work). */
    void baseBranch(String taskId, String branch) {
        jdbc.sql("UPDATE tasks SET base_branch = ? WHERE id = ?").params(branch, taskId).update();
    }

    /** Moves a task through the execution states (increment 4). Only the execution engine calls this. */
    void advance(String taskId, String to, Actor actor, Map<String, ?> extra) {
        Task task = find(taskId).orElseThrow();
        if (task.status().equals(to) || CLOSED.contains(task.status())) return;
        setStatus(task, to, actor, extra);
    }

    /** The validation engine closes the task; dependents whose dependencies are all done become ready. */
    void complete(String taskId, Actor actor, Map<String, ?> extra) {
        advance(taskId, "DONE", actor, extra);
        onTaskDone(taskId);
    }

    void setCriteria(String taskId, List<Task.Criterion> criteria) {
        jdbc.sql("UPDATE tasks SET acceptance_criteria = ?::jsonb, updated_at = now() WHERE id = ?")
                .params(json.writeValueAsString(criteria), taskId).update();
    }

    /** Recomputes CREATED / READY / ASSIGNED from dependencies and assignee. Other states are left alone. */
    void settle(String taskId, Actor actor) {
        Task task = find(taskId).orElseThrow();
        if (!List.of("CREATED", "READY", "ASSIGNED").contains(task.status())) return;
        long pending = jdbc.sql("""
                SELECT count(*) FROM task_dependencies d JOIN tasks t ON t.id = d.depends_on_id
                WHERE d.task_id = ? AND t.status NOT IN ('DONE', 'CANCELLED')
                """).param(taskId).query(Long.class).single();
        String target = pending > 0 ? "CREATED" : task.assigneeId() == null ? "READY" : "ASSIGNED";
        if (!target.equals(task.status())) setStatus(task, target, actor, Map.of());
    }

    private void setStatus(Task task, String to, Actor actor, Map<String, ?> extra) {
        jdbc.sql("UPDATE tasks SET status = ?, updated_at = now() WHERE id = ?").params(to, task.id()).update();
        Map<String, Object> payload = new HashMap<>(extra);
        payload.put("number", task.number());
        payload.put("from", task.status());
        payload.put("to", to);
        events.publish(workspaceOf(task), task.projectId(), "task.status_changed", actor, new Subject("task", task.id()),
                payload);
    }

    private Task open(String taskId) {
        Task task = find(taskId).orElseThrow(() -> ApiException.notFound("Task"));
        if (CLOSED.contains(task.status())) throw ApiException.conflict("task_closed", "The task is " + task.status().toLowerCase());
        return task;
    }

    private void requireAssignable(String projectId, String agentId) {
        Agent agent = agents.find(agentId).filter(a -> a.projectId().equals(projectId))
                .orElseThrow(() -> ApiException.badRequest("invalid_assignee", "The assignee is not in this project"));
        if ("DISMISSED".equals(agent.status())) {
            throw ApiException.badRequest("invalid_assignee", agent.name() + " was dismissed");
        }
    }

    /** True when {@code from} reaches {@code to} following dependency edges (from depends on ... on to). */
    private boolean reaches(String projectId, String from, String to) {
        Map<String, List<String>> deps = dependencies(projectId);
        Deque<String> stack = new ArrayDeque<>(List.of(from));
        Set<String> seen = new HashSet<>();
        while (!stack.isEmpty()) {
            String current = stack.pop();
            if (current.equals(to)) return true;
            if (seen.add(current)) stack.addAll(deps.getOrDefault(current, List.of()));
        }
        return false;
    }

    private Map<String, List<String>> dependencies(String projectId) {
        Map<String, List<String>> deps = new HashMap<>();
        jdbc.sql("""
                SELECT d.task_id, d.depends_on_id FROM task_dependencies d JOIN tasks t ON t.id = d.task_id
                WHERE t.project_id = ?
                """).param(projectId).query((ResultSet rs) -> {
                    deps.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
                });
        return deps;
    }

    String workspaceOf(Task task) {
        return jdbc.sql("SELECT workspace_id FROM tasks WHERE id = ?").param(task.id()).query(String.class).single();
    }

    /** Posts a message in the task's thread (created on first use). */
    void message(Task task, Actor author, String toAgentId, String type, String content) {
        String thread = collaboration.thread(workspaceOf(task), task.projectId(), "TASK", task.id(),
                "T-" + task.number() + " " + task.title());
        collaboration.post(thread, author, toAgentId, type, content);
    }

    private Task map(ResultSet rs, Map<String, List<String>> deps) throws SQLException {
        String id = rs.getString("id");
        return new Task(id, rs.getString("project_id"), rs.getInt("number"), rs.getString("title"),
                rs.getString("description"), json.readValue(rs.getString("acceptance_criteria"), CRITERIA),
                rs.getString("status"), rs.getString("priority"), rs.getBigDecimal("estimate_days"),
                rs.getString("assignee_id"), rs.getString("blocked_reason"), rs.getString("source_kind"),
                rs.getString("source_id"), rs.getString("created_by_kind"), rs.getString("created_by_id"),
                deps.getOrDefault(id, List.of()), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
