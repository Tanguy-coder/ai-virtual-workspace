package dev.aiw.scm;

import java.time.Instant;
import java.util.Map;

/**
 * A task branch proposed for main. {@code checks} holds the last validation result (docs/10): tests, secrets,
 * conflicts, review, criteria, each {status, detail}.
 */
public record MergeRequest(String id, String projectId, int number, String taskId, String runId, String sourceBranch,
                           String targetBranch, String authorAgentId, String reviewerAgentId, String title, String summary,
                           String status, int reviewRounds, String headCommit, Map<String, Object> checks,
                           String approvalId, String mergedCommit, Instant createdAt, Instant updatedAt, Instant mergedAt) {}
