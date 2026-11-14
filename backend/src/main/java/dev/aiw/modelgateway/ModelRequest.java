package dev.aiw.modelgateway;

import java.util.List;

/**
 * One model call. {@code effort} is low|medium|high|xhigh|max (Anthropic only; ignored elsewhere).
 * projectId and agentId attribute usage and cost.
 *
 * <p>Token sobriety (docs/09): {@code systemPrefix} is the stable part of the system prompt (instructions, project,
 * specification) sent first and marked for the provider's prompt cache; {@code system} is the variable rest.
 * {@code tier} LIGHT routes the call to the provider's economy model. {@code cache} says whether and how far
 * the prompt is marked for the provider's cache. {@code need}: the kind of work, so that the router can move the call
 * to a fitting model when this one is out of quota (docs/15); null means CODING, or LIGHT for the LIGHT tier.
 */
public record ModelRequest(String workspaceId, String projectId, String agentId, String providerId, String model,
                           String systemPrefix, String system, List<ChatTurn> turns, long maxTokens, String effort,
                           Tier tier, Cache cache, ModelProfiles.Need need) {
    public enum Tier { STANDARD, LIGHT }

    /**
     * Provider prompt cache. Writing costs 1.25x, reading 0.1x: only worth it when the same prefix comes back.
     * NONE for one-shot calls, PREFIX when the same agent repeats the system prompt (meeting rounds),
     * CONVERSATION when the next request repeats the whole conversation (chat).
     */
    public enum Cache { NONE, PREFIX, CONVERSATION }

    public ModelRequest(String workspaceId, String projectId, String agentId, String providerId, String model,
                        String system, List<ChatTurn> turns, long maxTokens, String effort) {
        this(workspaceId, projectId, agentId, providerId, model, null, system, turns, maxTokens, effort, Tier.STANDARD,
                Cache.NONE, null);
    }

    public ModelRequest(String workspaceId, String projectId, String agentId, String providerId, String model,
                        String systemPrefix, String system, List<ChatTurn> turns, long maxTokens, String effort,
                        Tier tier, Cache cache) {
        this(workspaceId, projectId, agentId, providerId, model, systemPrefix, system, turns, maxTokens, effort, tier,
                cache, null);
    }

    public ModelProfiles.Need effectiveNeed() {
        return tier == Tier.LIGHT ? ModelProfiles.Need.LIGHT : need == null ? ModelProfiles.Need.CODING : need;
    }

    ModelRequest withMaxTokens(long max) {
        return new ModelRequest(workspaceId, projectId, agentId, providerId, model, systemPrefix, system, turns, max,
                effort, tier, cache, need);
    }

    ModelRequest withModel(String other) {
        return new ModelRequest(workspaceId, projectId, agentId, providerId, other, systemPrefix, system, turns, maxTokens,
                effort, tier, cache, need);
    }

    ModelRequest withProvider(String otherProvider, String otherModel) {
        return new ModelRequest(workspaceId, projectId, agentId, otherProvider, otherModel, systemPrefix, system, turns,
                maxTokens, effort, tier, cache, need);
    }

    /** Rough size of the prompt in tokens (4 characters per token), to skip models whose window is too small. */
    long promptTokensEstimate() {
        long chars = (systemPrefix == null ? 0 : systemPrefix.length()) + (system == null ? 0 : system.length());
        for (ChatTurn t : turns) chars += t.text().length();
        return chars / 4;
    }

    /** Whole system prompt as one string, for providers without explicit cache breakpoints. */
    String fullSystem() {
        boolean p = systemPrefix != null && !systemPrefix.isBlank(), s = system != null && !system.isBlank();
        return p && s ? systemPrefix + "\n" + system : p ? systemPrefix : s ? system : null;
    }
}
