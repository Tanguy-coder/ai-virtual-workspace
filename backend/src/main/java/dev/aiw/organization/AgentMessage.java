package dev.aiw.organization;

import java.time.Instant;
import java.util.List;

/**
 * status: PENDING (waiting for the agent), ANSWERED, FAILED (error shown), FINAL (agent reply). {@code actions}: for a
 * Lead reply, the outcome of each order it gave, recorded by the platform (null otherwise).
 */
public record AgentMessage(String id, String agentId, String authorKind, String authorId, String content,
                           String status, String error, Instant createdAt, List<LeadCommands.Outcome> actions) {}
