package dev.aiw.collaboration;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** A meeting. {@code minutes} holds summary, decisions, disagreements and action items once it has ended. */
public record Meeting(String id, String workspaceId, String projectId, String purpose, String title, String agenda,
                      String organizerId, List<String> participantIds, String convenedByKind, String status, int rounds, int currentRound,
                      int turnIndex, String threadId, Map<String, Object> minutes, String error, Instant startedAt,
                      Instant endedAt) {}
