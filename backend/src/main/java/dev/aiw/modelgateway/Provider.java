package dev.aiw.modelgateway;

import java.time.Instant;

/** A configured model provider. The API key is never part of this view. */
public record Provider(String id, String workspaceId, ProviderKind kind, String name, String baseUrl,
                       boolean hasKey, Instant createdAt) {}
