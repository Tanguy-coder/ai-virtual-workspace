package dev.aiw.organization;

import java.time.Instant;
import java.util.List;

/**
 * An agent of a project. {@code activity} is what the agent is doing right now, derived from real runtime
 * events (AVAILABLE, THINKING, IN_MEETING, BLOCKED); {@code status} is its employment state (ACTIVE, PAUSED, DISMISSED).
 */
public record Agent(String id, String workspaceId, String projectId, String roleTemplateId, String name, String title,
                    Department department, String seniority, int simulatedYears, List<String> skills,
                    List<String> responsibilities, String systemInstructions, String providerId, String model,
                    boolean lead, String status, String activity, String blockedReason,
                    Instant createdAt) {}
