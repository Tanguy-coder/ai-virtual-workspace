package dev.aiw.modelgateway;

import java.math.BigDecimal;

/**
 * Outcome of a call. {@code costUsd} is null when no price is known for the model. {@code reused}: the answer of an
 * identical earlier call (memoized); nothing was sent to the provider. {@code requestHash} identifies the
 * memoized entry, so a caller that rejects the answer can {@link ModelGateway#forget} it. {@code providerId}: the
 * provider that answered, which differs from the requested one when the router moved the call (docs/15).
 */
public record ModelResult(String text, String servedModel, long inputTokens, long outputTokens, boolean refused,
                          String stopReason, long latencyMs, BigDecimal costUsd, long cacheWriteTokens,
                          long cacheReadTokens, boolean reused, String projectId, String requestHash,
                          String providerId) {}
