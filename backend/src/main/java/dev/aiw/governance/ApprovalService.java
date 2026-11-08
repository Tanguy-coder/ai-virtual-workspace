package dev.aiw.governance;

import dev.aiw.events.EventPublisher;
import dev.aiw.events.Subject;
import dev.aiw.shared.Actor;
import dev.aiw.shared.ApiException;
import dev.aiw.shared.Ids;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Queue of sensitive actions waiting for a human decision (the Inbox). */
@Service
public class ApprovalService {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final EventPublisher events;
    private final AuditLog audit;

    public ApprovalService(JdbcClient jdbc, JsonMapper json, EventPublisher events, AuditLog audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.events = events;
        this.audit = audit;
    }

    @Transactional
    public String request(String workspaceId, String projectId, Actor requester, String action,
                          String targetKind, String targetId, String summary, Map<String, ?> details) {
        String id = Ids.newId();
        jdbc.sql("""
                INSERT INTO approvals (id, workspace_id, project_id, requested_by_kind, requested_by_id, action,
                                       target_kind, target_id, summary, details)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """)
                .params(id, workspaceId, projectId, requester.kind().name(), requester.id(), action, targetKind,
                        targetId, summary, json.writeValueAsString(details))
                .update();
        events.publish(workspaceId, projectId, "approval.requested", requester, new Subject("approval", id),
                Map.of("action", action, "summary", summary));
        return id;
    }

    public List<Approval> list(String workspaceId, String status) {
        return jdbc.sql("SELECT * FROM approvals WHERE workspace_id = ? AND status = ? ORDER BY created_at DESC LIMIT 200")
                .params(workspaceId, status).query(this::map).list();
    }

    public long countPending(String workspaceId, String projectId) {
        return jdbc.sql("""
                SELECT count(*) FROM approvals WHERE workspace_id = ? AND status = 'PENDING'
                AND (CAST(? AS TEXT) IS NULL OR project_id = ?)
                """).params(workspaceId, projectId, projectId).query(Long.class).single();
    }

    @Transactional
    public Approval decide(String workspaceId, String approvalId, String userId, boolean approve, String reason) {
        Approval current = jdbc.sql("SELECT * FROM approvals WHERE id = ? AND workspace_id = ? FOR UPDATE")
                .params(approvalId, workspaceId).query(this::map).optional()
                .orElseThrow(() -> ApiException.notFound("Approval"));
        if (!"PENDING".equals(current.status())) {
            throw ApiException.conflict("already_decided", "This approval was already decided");
        }
        String status = approve ? "APPROVED" : "REJECTED";
        jdbc.sql("UPDATE approvals SET status = ?, decided_by = ?, decided_at = now(), reason = ? WHERE id = ?")
                .params(status, userId, reason, approvalId).update();
        Actor actor = Actor.human(userId);
        events.publish(workspaceId, current.projectId(), approve ? "approval.granted" : "approval.denied", actor,
                new Subject("approval", approvalId), Map.of("action", current.action()));
        audit.record(workspaceId, actor, current.action(), current.targetKind(), current.targetId(),
                approve ? AuditLog.Decision.APPROVED : AuditLog.Decision.REJECTED,
                reason == null ? Map.of("approvalId", approvalId) : Map.of("approvalId", approvalId, "reason", reason));
        return jdbc.sql("SELECT * FROM approvals WHERE id = ?").param(approvalId).query(this::map).single();
    }

    private Approval map(ResultSet rs, int row) throws SQLException {
        var decidedAt = rs.getTimestamp("decided_at");
        return new Approval(rs.getString("id"), rs.getString("workspace_id"), rs.getString("project_id"),
                rs.getString("requested_by_kind"), rs.getString("requested_by_id"), rs.getString("action"),
                rs.getString("target_kind"), rs.getString("target_id"), rs.getString("summary"),
                json.readValue(rs.getString("details"), MAP), rs.getString("status"), rs.getString("decided_by"),
                decidedAt == null ? null : decidedAt.toInstant(), rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant());
    }
}
