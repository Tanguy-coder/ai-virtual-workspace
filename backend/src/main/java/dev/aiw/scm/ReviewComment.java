package dev.aiw.scm;

import java.time.Instant;

public record ReviewComment(String id, String mrId, int round, String authorKind, String authorId, String file,
                            Integer line, String body, Instant createdAt) {}
