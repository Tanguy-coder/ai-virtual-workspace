package dev.aiw.projects;

import java.time.Instant;

public record Project(String id, String workspaceId, String name, String description, String status,
                      String createdBy, Instant createdAt, Instant updatedAt) {}
