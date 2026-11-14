package dev.aiw.organization;

import dev.aiw.modelgateway.ModelProfiles;
import java.time.Instant;
import java.util.List;

/**
 * An agent of a project. {@code activity} is what the agent is doing right now, derived from real runtime
 * events (AVAILABLE, THINKING, IN_MEETING, BLOCKED); {@code status} is its employment state (ACTIVE, PAUSED, DISMISSED).
 * {@code modelSource}: who chose its model (CTO, LEAD, SYSTEM), {@code modelReason}: why, when the platform chose.
 */
public record Agent(String id, String workspaceId, String projectId, String roleTemplateId, String name, String title,
                    Department department, String seniority, int simulatedYears, List<String> skills,
                    List<String> responsibilities, String systemInstructions, String providerId, String model,
                    boolean lead, String status, String activity, String blockedReason,
                    Instant createdAt, String modelSource, String modelReason) {

    /** The kind of work its model calls do (docs/15): deciding and reviewing, or writing code. */
    public ModelProfiles.Need need() {
        return needOf(department, lead);
    }

    public static ModelProfiles.Need needOf(Department department, boolean lead) {
        return lead || department == Department.DIRECTION || department == Department.ARCHITECTURE
                || department == Department.PRODUCT || department == Department.SECURITY
                ? ModelProfiles.Need.REASONING : ModelProfiles.Need.CODING;
    }
}
