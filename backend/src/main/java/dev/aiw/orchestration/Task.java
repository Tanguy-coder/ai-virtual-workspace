package dev.aiw.orchestration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** A task of the project plan. Only the validation engine (increment 4) can move a task to DONE. */
public record Task(String id, String projectId, int number, String title, String description,
                   List<Criterion> acceptanceCriteria, String status, String priority, BigDecimal estimateDays,
                   String assigneeId, String blockedReason, String sourceKind, String sourceId, String createdByKind,
                   String createdById, List<String> dependsOn, Instant createdAt, Instant updatedAt) {
    /** An acceptance criterion. {@code evidence}: what proves it (a test, a run), set by the review. */
    public record Criterion(String text, boolean met, String evidence) {
        public Criterion(String text, boolean met) {
            this(text, met, null);
        }
    }
}
