package dev.aiw.orchestration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** A task of the project plan. Only the validation engine (increment 4) can move a task to DONE. */
public record Task(String id, String projectId, int number, String title, String description,
                   List<Criterion> acceptanceCriteria, String status, String priority, BigDecimal estimateDays,
                   String assigneeId, String blockedReason, String sourceKind, String sourceId, String createdByKind,
                   String createdById, List<String> dependsOn, Instant createdAt, Instant updatedAt) {
    public record Criterion(String text, boolean met) {}
}
