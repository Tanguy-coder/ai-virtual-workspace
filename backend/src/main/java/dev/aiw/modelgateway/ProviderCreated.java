package dev.aiw.modelgateway;

/** Published (in-transaction) when a provider is added to a workspace. */
public record ProviderCreated(String workspaceId, String providerId, ProviderKind kind, String defaultModel) {}
