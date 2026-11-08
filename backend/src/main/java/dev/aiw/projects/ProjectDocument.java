package dev.aiw.projects;

import java.time.Instant;

public record ProjectDocument(String id, String projectId, String kind, String title, String mediaType,
                              int sizeBytes, String createdBy, Instant createdAt, String content) {}
