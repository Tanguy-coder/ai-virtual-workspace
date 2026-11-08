package dev.aiw.memory;

import java.time.Instant;

/** A versioned memory entry. {@code agentId} is null for the project's shared memory. */
public record MemoryEntry(String id, String projectId, String agentId, String entryKey, int version, String kind,
                          String title, String content, String sourceKind, String sourceId, String authorKind,
                          String authorId, Instant createdAt) {}
