package dev.aiw.collaboration;

import java.time.Instant;

public record ThreadView(String id, String projectId, String kind, String subjectId, String title, long messageCount,
                         Instant lastMessageAt, Instant createdAt) {}
