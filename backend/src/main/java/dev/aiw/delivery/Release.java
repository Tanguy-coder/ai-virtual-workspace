package dev.aiw.delivery;

import java.time.Instant;

/** A version of main, tagged once a human approves it (docs/18). Notes come from the merged tasks, not from a model. */
public record Release(String id, String projectId, String version, String commitSha, String notes, String status,
                      String approvalId, String requestedByKind, String requestedById, String error, Instant createdAt,
                      Instant releasedAt) {}
