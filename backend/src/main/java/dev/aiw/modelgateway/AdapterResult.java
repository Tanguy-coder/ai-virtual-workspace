package dev.aiw.modelgateway;

/** Provider answer. {@code inputTokens} excludes tokens written to or read from the prompt cache. */
record AdapterResult(String text, String servedModel, long inputTokens, long outputTokens, boolean refused,
                     String stopReason, long cacheWriteTokens, long cacheReadTokens) {
    AdapterResult(String text, String servedModel, long inputTokens, long outputTokens, boolean refused, String stopReason) {
        this(text, servedModel, inputTokens, outputTokens, refused, stopReason, 0, 0);
    }
}
