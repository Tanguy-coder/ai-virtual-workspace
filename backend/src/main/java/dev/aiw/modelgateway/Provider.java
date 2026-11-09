package dev.aiw.modelgateway;

import java.time.Instant;

/**
 * A configured model provider. The API key is never part of this view.
 * {@code defaultModel}: model bound to new agents when this provider is the workspace default ({@code isDefault}).
 * {@code economyModel}: model for light calls (summaries, minutes); null means each agent's own model.
 * {@code preset}: the ready-made settings it was created from (docs: {@link ProviderPresets}), null for older rows.
 */
public record Provider(String id, String workspaceId, ProviderKind kind, String name, String baseUrl,
                       boolean hasKey, Instant createdAt, String economyModel, String defaultModel, boolean isDefault,
                       String preset) {}
