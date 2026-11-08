package dev.aiw.orchestration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A team proposed by the Lead. Each line names a catalog role, a count and the justification tied to the
 * specification. {@code estimatedDailyUsd} is an estimate from list prices and an assumed daily volume.
 */
public record HiringProposal(String id, String projectId, String briefingId, int version, String status,
                             String rationale, List<Map<String, Object>> lines, BigDecimal estimatedDailyUsd,
                             String revisionComment, String decidedBy, Instant decidedAt, Instant createdAt) {}
