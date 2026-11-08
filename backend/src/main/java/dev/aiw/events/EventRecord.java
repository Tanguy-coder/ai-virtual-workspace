package dev.aiw.events;

import java.time.Instant;
import java.util.Map;

/** Event envelope as stored and as sent to clients. */
public record EventRecord(
        String id,
        long seq,
        String type,
        Instant occurredAt,
        String workspaceId,
        String projectId,
        ActorRef actor,
        Subject subject,
        Map<String, Object> payload) {

    public record ActorRef(String kind, String id) {}
}
