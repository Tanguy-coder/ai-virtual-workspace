package dev.aiw.agentruntime;

import java.math.BigDecimal;
import java.time.Instant;

/** One recorded execution step of an agent (a model call today; tool calls from increment 4). */
public record AgentStep(String id, String agentId, String jobId, String kind, String summary, String model,
                        long inputTokens, long outputTokens, BigDecimal costUsd, long durationMs, String outcome,
                        String error, Instant createdAt) {}
