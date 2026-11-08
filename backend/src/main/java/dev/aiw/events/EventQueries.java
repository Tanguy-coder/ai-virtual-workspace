package dev.aiw.events;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
public class EventQueries {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public EventQueries(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Events after {@code afterSeq}, oldest first. Pass afterSeq < 0 for the most recent ones. */
    public List<EventRecord> list(String workspaceId, String projectId, long afterSeq, int limit) {
        int capped = Math.min(Math.max(limit, 1), 500);
        String scope = projectId == null ? "workspace_id = :ws" : "workspace_id = :ws AND project_id = :project";
        if (afterSeq < 0) {
            List<EventRecord> latest = jdbc.sql("SELECT * FROM events WHERE " + scope + " ORDER BY seq DESC LIMIT :limit")
                    .param("ws", workspaceId).param("project", projectId).param("limit", capped)
                    .query(this::map).list();
            return latest.reversed();
        }
        return jdbc.sql("SELECT * FROM events WHERE " + scope + " AND seq > :after ORDER BY seq LIMIT :limit")
                .param("ws", workspaceId).param("project", projectId).param("after", afterSeq).param("limit", capped)
                .query(this::map).list();
    }

    /** History of one object (task, meeting, agent...), oldest first. */
    public List<EventRecord> forSubject(String workspaceId, String subjectId, int limit) {
        return jdbc.sql("SELECT * FROM events WHERE workspace_id = ? AND subject_id = ? ORDER BY seq DESC LIMIT ?")
                .params(workspaceId, subjectId, Math.min(Math.max(limit, 1), 500)).query(this::map).list().reversed();
    }

    EventRecord map(ResultSet rs, int row) throws SQLException {
        return new EventRecord(
                rs.getString("id"),
                rs.getLong("seq"),
                rs.getString("type"),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("workspace_id"),
                rs.getString("project_id"),
                new EventRecord.ActorRef(rs.getString("actor_kind"), rs.getString("actor_id")),
                new Subject(rs.getString("subject_kind"), rs.getString("subject_id")),
                json.readValue(rs.getString("payload"), MAP));
    }
}
