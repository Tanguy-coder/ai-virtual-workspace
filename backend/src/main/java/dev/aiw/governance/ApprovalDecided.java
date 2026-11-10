package dev.aiw.governance;

/** Published in the deciding transaction, so the module that asked can act on the decision. */
public record ApprovalDecided(String workspaceId, String projectId, String approvalId, String action, String targetKind,
                              String targetId, boolean approved, String userId, String reason) {}
