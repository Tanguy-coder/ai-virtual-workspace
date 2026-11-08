package dev.aiw.access;

import dev.aiw.shared.ApiException;
import dev.aiw.shared.CurrentUser;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Authorization checks on workspace membership. Every endpoint that touches tenant data
 * goes through here. A user who is not a member gets 404, not 403, so ids do not leak.
 */
@Service
public class WorkspaceAccess {
    private final JdbcClient jdbc;

    public WorkspaceAccess(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<WorkspaceRole> roleOf(String workspaceId, String userId) {
        return jdbc.sql("SELECT role FROM memberships WHERE workspace_id = ? AND user_id = ?")
                .params(workspaceId, userId)
                .query(String.class)
                .optional()
                .map(WorkspaceRole::valueOf);
    }

    /** Requires the current user to hold at least {@code minRole}; returns the user id. */
    public String require(String workspaceId, WorkspaceRole minRole) {
        String userId = CurrentUser.id();
        WorkspaceRole role = roleOf(workspaceId, userId).orElseThrow(() -> ApiException.notFound("Workspace"));
        if (!role.atLeast(minRole)) {
            throw ApiException.forbidden();
        }
        return userId;
    }

    public boolean isMember(String workspaceId, String userId) {
        return roleOf(workspaceId, userId).isPresent();
    }

    /** Resolves the workspace owning a project, or 404. */
    public String workspaceOfProject(String projectId) {
        return jdbc.sql("SELECT workspace_id FROM projects WHERE id = ?")
                .param(projectId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> ApiException.notFound("Project"));
    }

    /** Requires access to the project's workspace; returns the workspace id. */
    public String requireProject(String projectId, WorkspaceRole minRole) {
        String workspaceId = workspaceOfProject(projectId);
        try {
            require(workspaceId, minRole);
        } catch (ApiException e) {
            if ("not_found".equals(e.code())) throw ApiException.notFound("Project");
            throw e;
        }
        return workspaceId;
    }
}
