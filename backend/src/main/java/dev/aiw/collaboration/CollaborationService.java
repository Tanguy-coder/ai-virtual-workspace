package dev.aiw.collaboration;

import dev.aiw.events.EventPublisher;
import dev.aiw.events.Subject;
import dev.aiw.shared.Actor;
import dev.aiw.shared.ApiException;
import dev.aiw.shared.Ids;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Threads and typed messages between agents and humans. Every message is an event the office can draw. */
@Service
public class CollaborationService {
    public static final Set<String> TYPES = Set.of("QUESTION", "ANSWER", "RECOMMENDATION", "PROBLEM", "DEPENDENCY",
            "REVIEW_REQUEST", "ASSIGNMENT", "CONTRIBUTION");

    private final JdbcClient jdbc;
    private final EventPublisher events;

    CollaborationService(JdbcClient jdbc, EventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    /** The thread attached to a subject (task, meeting, briefing), created on first use. */
    @Transactional
    public String thread(String workspaceId, String projectId, String kind, String subjectId, String title) {
        Optional<String> existing = subjectId == null ? Optional.empty()
                : jdbc.sql("SELECT id FROM threads WHERE kind = ? AND subject_id = ?").params(kind, subjectId)
                        .query(String.class).optional();
        if (existing.isPresent()) return existing.get();
        String id = Ids.newId();
        jdbc.sql("INSERT INTO threads (id, workspace_id, project_id, kind, subject_id, title) VALUES (?, ?, ?, ?, ?, ?)")
                .params(id, workspaceId, projectId, kind, subjectId, title).update();
        return id;
    }

    @Transactional
    public Message post(String threadId, Actor author, String toAgentId, String type, String content) {
        if (!TYPES.contains(type)) throw ApiException.badRequest("invalid_type", "Unknown message type " + type);
        Map<String, Object> thread = jdbc.sql("SELECT workspace_id, project_id, kind, subject_id FROM threads WHERE id = ?")
                .param(threadId).query().singleRow();
        String id = Ids.newId();
        jdbc.sql("""
                INSERT INTO messages (id, workspace_id, project_id, thread_id, author_kind, author_id, to_agent_id, type, content)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(id, thread.get("workspace_id"), thread.get("project_id"), threadId, author.kind().name(),
                author.id(), toAgentId, type, content).update();
        Map<String, Object> payload = new HashMap<>();
        payload.put("messageId", id);
        payload.put("threadId", threadId);
        payload.put("threadKind", thread.get("kind"));
        if (thread.get("subject_id") != null) payload.put("subjectId", thread.get("subject_id"));
        payload.put("type", type);
        if (author.kind() == Actor.Kind.AGENT) payload.put("fromAgentId", author.id());
        if (toAgentId != null) payload.put("toAgentId", toAgentId);
        payload.put("preview", content.length() > 140 ? content.substring(0, 140) + "…" : content);
        events.publish((String) thread.get("workspace_id"), (String) thread.get("project_id"), "message.sent", author,
                new Subject("thread", threadId), payload);
        return message(id);
    }

    public List<Message> messages(String threadId) {
        return jdbc.sql("SELECT * FROM messages WHERE thread_id = ? ORDER BY created_at, id").param(threadId)
                .query(this::map).list();
    }

    public List<ThreadView> threads(String projectId) {
        return jdbc.sql("""
                SELECT t.*, count(m.id) AS message_count, max(m.created_at) AS last_message_at
                FROM threads t LEFT JOIN messages m ON m.thread_id = t.id
                WHERE t.project_id = ? GROUP BY t.id ORDER BY coalesce(max(m.created_at), t.created_at) DESC LIMIT 200
                """).param(projectId).query(this::mapThread).list();
    }

    public Optional<ThreadView> findThread(String threadId) {
        return jdbc.sql("""
                SELECT t.*, count(m.id) AS message_count, max(m.created_at) AS last_message_at
                FROM threads t LEFT JOIN messages m ON m.thread_id = t.id WHERE t.id = ? GROUP BY t.id
                """).param(threadId).query(this::mapThread).optional();
    }

    public Optional<String> threadOf(String kind, String subjectId) {
        return jdbc.sql("SELECT id FROM threads WHERE kind = ? AND subject_id = ?").params(kind, subjectId)
                .query(String.class).optional();
    }

    /** Recent messages written by agents in a project, newest first (the office "Conversations" panel). */
    public List<Message> recentAgentMessages(String projectId, int limit) {
        return jdbc.sql("""
                SELECT * FROM messages WHERE project_id = ? AND author_kind = 'AGENT'
                ORDER BY created_at DESC LIMIT ?
                """).params(projectId, Math.min(Math.max(limit, 1), 200)).query(this::map).list();
    }

    String projectOfThread(String threadId) {
        return jdbc.sql("SELECT project_id FROM threads WHERE id = ?").param(threadId).query(String.class).optional()
                .orElseThrow(() -> ApiException.notFound("Thread"));
    }

    private Message message(String id) {
        return jdbc.sql("SELECT * FROM messages WHERE id = ?").param(id).query(this::map).single();
    }

    private Message map(ResultSet rs, int row) throws SQLException {
        return new Message(rs.getString("id"), rs.getString("thread_id"), rs.getString("author_kind"),
                rs.getString("author_id"), rs.getString("to_agent_id"), rs.getString("type"), rs.getString("content"),
                rs.getTimestamp("created_at").toInstant());
    }

    private ThreadView mapThread(ResultSet rs, int row) throws SQLException {
        Timestamp last = rs.getTimestamp("last_message_at");
        return new ThreadView(rs.getString("id"), rs.getString("project_id"), rs.getString("kind"),
                rs.getString("subject_id"), rs.getString("title"), rs.getLong("message_count"),
                last == null ? null : last.toInstant(), rs.getTimestamp("created_at").toInstant());
    }
}
