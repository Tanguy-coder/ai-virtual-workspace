package dev.aiw.quality;

/**
 * The static scans of develop (secrets, code, dependencies) finished for {@code commit}: the branch flow may now ask
 * for the merge into main when nothing blocks (docs/18). Also published when scans are disabled.
 */
public record StaticScanCompleted(String workspaceId, String projectId, String commit, int blocking) {}
