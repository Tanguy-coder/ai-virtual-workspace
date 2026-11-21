package dev.aiw.orchestration;

/** develop reached main with the CTO's approval (docs/14); delivery deploys it to the test environment (docs/18). */
public record MainMerged(String workspaceId, String projectId, String commit, String approvedBy) {}
