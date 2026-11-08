package dev.aiw.modelgateway;

import java.util.List;

/**
 * One model call. {@code effort} is low|medium|high|xhigh|max (Anthropic only; ignored elsewhere).
 * projectId and agentId attribute usage and cost.
 */
public record ModelRequest(String workspaceId, String projectId, String agentId, String providerId, String model,
                           String system, List<ChatTurn> turns, long maxTokens, String effort) {}
