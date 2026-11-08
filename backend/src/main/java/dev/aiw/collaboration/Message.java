package dev.aiw.collaboration;

import java.time.Instant;

/** A typed message in a thread. {@code toAgentId} is set when the message is addressed to one agent. */
public record Message(String id, String threadId, String authorKind, String authorId, String toAgentId, String type,
                      String content, Instant createdAt) {}
