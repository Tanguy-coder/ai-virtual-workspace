package dev.aiw.projects;

/** Published synchronously inside the creating transaction; listeners join that transaction. */
public record ProjectCreated(String workspaceId, String projectId, String name, String createdBy) {}
