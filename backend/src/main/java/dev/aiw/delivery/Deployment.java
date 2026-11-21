package dev.aiw.delivery;

import java.time.Instant;

/** One deployment of a commit to an environment; always approved by a human (docs/18). */
public record Deployment(String id, String projectId, String environmentId, int number, String ref, String commitSha,
                         String releaseId, String rollbackOf, String status, String approvalId, String approvedBy,
                         String requestedByKind, String requestedById, String image, Integer containerPort, String url,
                         String error, Instant createdAt, Instant startedAt, Instant finishedAt) {}
