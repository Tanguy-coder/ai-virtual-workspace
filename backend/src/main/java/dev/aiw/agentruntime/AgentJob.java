package dev.aiw.agentruntime;

import java.util.Map;

/** A unit of agent work claimed by a worker under a lease. Handlers must be safe to run again after a crash. */
public record AgentJob(String id, String workspaceId, String projectId, String agentId, String type,
                       Map<String, Object> payload, int attempts, int maxAttempts, String correlationId) {
    public String string(String key) {
        Object v = payload.get(key);
        return v == null ? null : v.toString();
    }

    public int integer(String key, int fallback) {
        Object v = payload.get(key);
        return v instanceof Number n ? n.intValue() : fallback;
    }
}
