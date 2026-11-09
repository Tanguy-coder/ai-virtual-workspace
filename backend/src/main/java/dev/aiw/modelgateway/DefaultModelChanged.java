package dev.aiw.modelgateway;

/**
 * Published (in-transaction) when the workspace's default provider or its default model changes: agents without a
 * model are bound to it.
 */
public record DefaultModelChanged(String workspaceId, String providerId, String model) {}
