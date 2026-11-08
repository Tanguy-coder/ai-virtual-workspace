package dev.aiw.governance;

import java.time.Instant;
import java.util.Map;

public record Approval(
        String id,
        String workspaceId,
        String projectId,
        String requestedByKind,
        String requestedById,
        String action,
        String targetKind,
        String targetId,
        String summary,
        Map<String, Object> details,
        String status,
        String decidedBy,
        Instant decidedAt,
        String reason,
        Instant createdAt) {}
