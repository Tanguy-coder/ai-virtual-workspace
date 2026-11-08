package dev.aiw.organization;

import java.time.Instant;

/** status: PENDING (waiting for the agent), ANSWERED, FAILED (error shown), FINAL (agent reply). */
public record AgentMessage(String id, String agentId, String authorKind, String authorId, String content,
                           String status, String error, Instant createdAt) {}
