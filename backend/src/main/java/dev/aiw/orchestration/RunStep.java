package dev.aiw.orchestration;

import java.time.Instant;
import java.util.Map;

/**
 * A recorded step of a run. MODEL: the agent's decision; TOOL: one tool call and its (bounded) result;
 * PLATFORM: what the platform did or reports to the agent (tests, conflicts, review); MESSAGE: from a person.
 */
public record RunStep(String id, String runId, int seq, String kind, String tool, String summary, Map<String, Object> detail,
                      String outcome, long inputTokens, long outputTokens, long durationMs, Instant createdAt) {}
