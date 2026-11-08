package dev.aiw.modelgateway;

import java.math.BigDecimal;

/** Outcome of a call. {@code costUsd} is null when no price is known for the model. */
public record ModelResult(String text, String servedModel, long inputTokens, long outputTokens, boolean refused,
                          String stopReason, long latencyMs, BigDecimal costUsd) {}
