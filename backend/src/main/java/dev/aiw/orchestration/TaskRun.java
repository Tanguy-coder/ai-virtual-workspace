package dev.aiw.orchestration;

import java.math.BigDecimal;
import java.time.Instant;

/** One agent working on one task, on its branch and in its sandbox (docs/10). */
public record TaskRun(String id, String projectId, String taskId, String agentId, String branch, String worktree,
                      String sandboxId, String image, String status, boolean pauseRequested, int steps, int segmentSteps,
                      long inputTokens, long outputTokens, long cacheReadTokens, long tokenBudget, BigDecimal costUsd,
                      String blockedReason, Instant startedAt, Instant updatedAt, Instant endedAt) {
    public long tokens() {
        return inputTokens + outputTokens;
    }
}
