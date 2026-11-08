package dev.aiw.agentruntime;

import java.time.Instant;
import java.util.Map;

public record JobView(String id, String projectId, String agentId, String type, Map<String, Object> payload,
                      String status, int attempts, int maxAttempts, String lastError, Instant createdAt,
                      Instant startedAt, Instant finishedAt) {}
