package dev.aiw.access;

/** Roles ordered from least to most privileged. */
public enum WorkspaceRole {
    VIEWER, MEMBER, ADMIN, OWNER;

    public boolean atLeast(WorkspaceRole other) {
        return ordinal() >= other.ordinal();
    }
}
