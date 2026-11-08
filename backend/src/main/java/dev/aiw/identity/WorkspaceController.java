package dev.aiw.identity;

import dev.aiw.access.WorkspaceAccess;
import dev.aiw.access.WorkspaceRole;
import dev.aiw.events.EventPublisher;
import dev.aiw.events.Subject;
import dev.aiw.governance.AuditLog;
import dev.aiw.shared.Actor;
import dev.aiw.shared.CurrentUser;
import dev.aiw.shared.Ids;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces")
class WorkspaceController {
    private final JdbcClient jdbc;
    private final WorkspaceAccess access;
    private final EventPublisher events;
    private final AuditLog audit;

    WorkspaceController(JdbcClient jdbc, WorkspaceAccess access, EventPublisher events, AuditLog audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.events = events;
        this.audit = audit;
    }

    record WorkspaceView(String id, String name, String role) {}

    @GetMapping
    List<WorkspaceView> list() {
        return jdbc.sql("""
                SELECT w.id, w.name, m.role FROM workspaces w
                JOIN memberships m ON m.workspace_id = w.id
                WHERE m.user_id = ? ORDER BY w.created_at
                """)
                .param(CurrentUser.id())
                .query((rs, n) -> new WorkspaceView(rs.getString("id"), rs.getString("name"), rs.getString("role")))
                .list();
    }

    @GetMapping("/{workspaceId}")
    WorkspaceView get(@PathVariable String workspaceId) {
        String userId = access.require(workspaceId, WorkspaceRole.VIEWER);
        return jdbc.sql("""
                SELECT w.id, w.name, m.role FROM workspaces w JOIN memberships m ON m.workspace_id = w.id
                WHERE w.id = ? AND m.user_id = ?
                """)
                .params(workspaceId, userId)
                .query((rs, n) -> new WorkspaceView(rs.getString("id"), rs.getString("name"), rs.getString("role")))
                .single();
    }

    record CreateWorkspace(@NotBlank @Size(max = 100) String name) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    WorkspaceView create(@Valid @RequestBody CreateWorkspace body) {
        String userId = CurrentUser.id();
        String id = Ids.newId();
        String name = body.name().trim();
        jdbc.sql("INSERT INTO workspaces (id, name, owner_id) VALUES (?, ?, ?)").params(id, name, userId).update();
        jdbc.sql("INSERT INTO memberships (workspace_id, user_id, role) VALUES (?, ?, 'OWNER')").params(id, userId).update();
        Actor actor = Actor.human(userId);
        events.publish(id, null, "workspace.created", actor, new Subject("workspace", id), Map.of("name", name));
        audit.record(id, actor, "workspace.create", "workspace", id, AuditLog.Decision.RECORDED, Map.of("name", name));
        return new WorkspaceView(id, name, "OWNER");
    }
}
