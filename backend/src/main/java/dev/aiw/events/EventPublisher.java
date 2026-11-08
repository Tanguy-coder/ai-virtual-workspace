package dev.aiw.events;

import dev.aiw.shared.Actor;
import dev.aiw.shared.Ids;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records a domain event. The insert joins the caller's transaction, so the event exists
 * if and only if the state change it describes was committed (transactional outbox).
 */
@Service
public class EventPublisher {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public EventPublisher(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String workspaceId, String projectId, String type, Actor actor, Subject subject,
                        Map<String, ?> payload) {
        jdbc.sql("""
                INSERT INTO events (id, workspace_id, project_id, type, actor_kind, actor_id,
                                    subject_kind, subject_id, payload)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """)
                .params(Ids.newId(), workspaceId, projectId, type, actor.kind().name(), actor.id(),
                        subject.kind(), subject.id(), json.writeValueAsString(payload))
                .update();
    }
}
