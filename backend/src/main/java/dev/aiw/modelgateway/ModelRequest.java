package dev.aiw.modelgateway;

import java.util.List;

/**
 * One model call. {@code effort} is low|medium|high|xhigh|max (Anthropic only; ignored elsewhere).
 * projectId and agentId attribute usage and cost.
 *
 * <p>Token sobriety (docs/09): {@code systemPrefix} is the stable part of the system prompt (instructions, project,
 * specification) sent first and marked for the provider's prompt cache; {@code system} is the variable rest.
 * {@code tier} LIGHT routes the call to the provider's economy model. {@code cache} says whether and how far
 * the prompt is marked for the provider's cache.
 */
public record ModelRequest(String workspaceId, String projectId, String agentId, String providerId, String model,
                           String systemPrefix, String system, List<ChatTurn> turns, long maxTokens, String effort,
                           Tier tier, Cache cache) {
    public enum Tier { STANDARD, LIGHT }

    /**
     * Provider prompt cache. Writing costs 1.25x, reading 0.1x: only worth it when the same prefix comes back.
     * NONE for one-shot calls, PREFIX when the same agent repeats the system prompt (meeting rounds),
     * CONVERSATION when the next request repeats the whole conversation (chat).
     */
    public enum Cache { NONE, PREFIX, CONVERSATION }

    public ModelRequest(String workspaceId, String projectId, String agentId, String providerId, String model,
                        String system, List<ChatTurn> turns, long maxTokens, String effort) {
        this(workspaceId, projectId, agentId, providerId, model, null, system, turns, maxTokens, effort, Tier.STANDARD, Cache.NONE);
    }

    ModelRequest withMaxTokens(long max) {
        return new ModelRequest(workspaceId, projectId, agentId, providerId, model, systemPrefix, system, turns, max,
                effort, tier, cache);
    }

    ModelRequest withModel(String other) {
        return new ModelRequest(workspaceId, projectId, agentId, providerId, other, systemPrefix, system, turns, maxTokens,
                effort, tier, cache);
    }

    /** Whole system prompt as one string, for providers without explicit cache breakpoints. */
    String fullSystem() {
        boolean p = systemPrefix != null && !systemPrefix.isBlank(), s = system != null && !system.isBlank();
        return p && s ? systemPrefix + "\n" + system : p ? systemPrefix : s ? system : null;
    }
}
