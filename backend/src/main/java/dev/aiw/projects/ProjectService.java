package dev.aiw.projects;

import dev.aiw.events.EventPublisher;
import dev.aiw.events.Subject;
import dev.aiw.governance.AuditLog;
import dev.aiw.shared.Actor;
import dev.aiw.shared.ApiException;
import dev.aiw.shared.Ids;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {
    /** Text formats accepted for specifications. Binary formats (PDF, DOCX) are not supported yet. */
    static final Set<String> TEXT_MEDIA_TYPES = Set.of("text/markdown", "text/plain");
    static final int MAX_DOCUMENT_BYTES = 2 * 1024 * 1024;

    private final JdbcClient jdbc;
    private final EventPublisher events;
    private final AuditLog audit;
    private final ApplicationEventPublisher appEvents;

    ProjectService(JdbcClient jdbc, EventPublisher events, AuditLog audit, ApplicationEventPublisher appEvents) {
        this.jdbc = jdbc;
        this.events = events;
        this.audit = audit;
        this.appEvents = appEvents;
    }

    public List<Project> list(String workspaceId) {
        return jdbc.sql("SELECT * FROM projects WHERE workspace_id = ? ORDER BY created_at DESC")
                .param(workspaceId).query(this::map).list();
    }

    public Optional<Project> find(String projectId) {
        return jdbc.sql("SELECT * FROM projects WHERE id = ?").param(projectId).query(this::map).optional();
    }

    public long countActive(String workspaceId) {
        return jdbc.sql("SELECT count(*) FROM projects WHERE workspace_id = ? AND status = 'ACTIVE'")
                .param(workspaceId).query(Long.class).single();
    }

    @Transactional
    public Project create(String workspaceId, String userId, String name, String description) {
        String id = Ids.newId();
        jdbc.sql("INSERT INTO projects (id, workspace_id, name, description, created_by) VALUES (?, ?, ?, ?, ?)")
                .params(id, workspaceId, name.trim(), description == null ? "" : description.trim(), userId)
                .update();
        Actor actor = Actor.human(userId);
        events.publish(workspaceId, id, "project.created", actor, new Subject("project", id), Map.of("name", name.trim()));
        audit.record(workspaceId, actor, "project.create", "project", id, AuditLog.Decision.RECORDED,
                Map.of("name", name.trim()));
        appEvents.publishEvent(new ProjectCreated(workspaceId, id, name.trim(), userId));
        return find(id).orElseThrow();
    }

    @Transactional
    public ProjectDocument addDocument(String workspaceId, String projectId, String userId, String kind, String title,
                                       String mediaType, String content) {
        if (!TEXT_MEDIA_TYPES.contains(mediaType)) {
            throw ApiException.badRequest("unsupported_format",
                    "Only Markdown and plain text documents are supported for now");
        }
        int size = content.getBytes(StandardCharsets.UTF_8).length;
        if (size == 0) throw ApiException.badRequest("empty_document", "The document is empty");
        if (size > MAX_DOCUMENT_BYTES) {
            throw ApiException.badRequest("document_too_large", "Documents are limited to 2 MB");
        }
        String id = Ids.newId();
        jdbc.sql("""
                INSERT INTO project_documents (id, workspace_id, project_id, kind, title, content, media_type,
                                               size_bytes, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(id, workspaceId, projectId, kind, title.trim(), content, mediaType, size, userId).update();
        jdbc.sql("UPDATE projects SET updated_at = now() WHERE id = ?").param(projectId).update();
        Actor actor = Actor.human(userId);
        events.publish(workspaceId, projectId, "document.added", actor, new Subject("document", id),
                Map.of("kind", kind, "title", title.trim(), "sizeBytes", size));
        audit.record(workspaceId, actor, "document.add", "document", id, AuditLog.Decision.RECORDED,
                Map.of("kind", kind, "title", title.trim()));
        return document(projectId, id).orElseThrow();
    }

    /** Document metadata without content. */
    public List<ProjectDocument> documents(String projectId) {
        return jdbc.sql("""
                SELECT id, project_id, kind, title, media_type, size_bytes, created_by, created_at, NULL AS content
                FROM project_documents WHERE project_id = ? ORDER BY created_at
                """).param(projectId).query(this::mapDocument).list();
    }

    public Optional<ProjectDocument> document(String projectId, String documentId) {
        return jdbc.sql("SELECT * FROM project_documents WHERE project_id = ? AND id = ?")
                .params(projectId, documentId).query(this::mapDocument).optional();
    }

    /** Full specification documents, oldest first, for building agent context. */
    public List<ProjectDocument> specifications(String projectId) {
        return jdbc.sql("SELECT * FROM project_documents WHERE project_id = ? AND kind = 'SPECIFICATION' ORDER BY created_at")
                .param(projectId).query(this::mapDocument).list();
    }

    private Project map(ResultSet rs, int row) throws SQLException {
        return new Project(rs.getString("id"), rs.getString("workspace_id"), rs.getString("name"),
                rs.getString("description"), rs.getString("status"), rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private ProjectDocument mapDocument(ResultSet rs, int row) throws SQLException {
        return new ProjectDocument(rs.getString("id"), rs.getString("project_id"), rs.getString("kind"),
                rs.getString("title"), rs.getString("media_type"), rs.getInt("size_bytes"), rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("content"));
    }
}
