package dev.aiw.orchestration;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** The Lead's analysis of the specification, its questions and the CTO's answers, and the architecture note. */
public record Briefing(String id, String projectId, String leadId, String status, Map<String, Object> analysis,
                       List<Map<String, Object>> questions, String architecture, String error, Instant createdAt,
                       Instant updatedAt) {}
