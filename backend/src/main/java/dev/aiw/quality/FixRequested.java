package dev.aiw.quality;

import java.util.List;

/**
 * A finding needs a fix task (docs/18). Orchestration creates the task (it owns tasks and their assignment) and links
 * it back with {@link SecurityService#linkTask}. {@code department}: who fits best (SECURITY, BACKEND, FRONTEND).
 */
public record FixRequested(String workspaceId, String projectId, String findingId, String title, String description,
                           List<String> criteria, String priority, List<String> departments, String requestedBy) {}
