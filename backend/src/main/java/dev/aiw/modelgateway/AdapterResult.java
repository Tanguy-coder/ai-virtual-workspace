package dev.aiw.modelgateway;

record AdapterResult(String text, String servedModel, long inputTokens, long outputTokens, boolean refused,
                     String stopReason) {}
