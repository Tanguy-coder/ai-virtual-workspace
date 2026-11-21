package dev.aiw.quality;

import java.time.Instant;
import java.util.Map;

/** One run of a scanning tool on a project (docs/18). */
public record QualityScan(String id, String projectId, int number, String kind, String tool, String ref, String commitSha,
                          String targetUrl, String trigger, String status, Map<String, Object> summary, String error,
                          Instant startedAt, Instant endedAt, Long durationMs) {}
