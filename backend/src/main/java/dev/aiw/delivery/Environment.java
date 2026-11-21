package dev.aiw.delivery;

import java.time.Instant;

/** Where a project's application runs (docs/18): the test environment is a container on the host, on its own port. */
public record Environment(String id, String projectId, String name, String kind, int hostPort, String url, String status,
                          String currentDeploymentId, Instant createdAt, Instant updatedAt) {}
