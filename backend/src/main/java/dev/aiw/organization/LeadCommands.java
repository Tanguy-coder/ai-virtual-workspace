package dev.aiw.organization;

import tools.jackson.databind.JsonNode;

/**
 * What the Lead can do from its chat with the CTO (CTO requirement, 2026-11-11: the Lead executes orders). Implemented
 * by the orchestration module; the chat only knows this interface, so organization does not depend on orchestration.
 */
public interface LeadCommands {
    /** Stable manual of the orders, appended to the Lead's chat system prompt (cacheable). */
    String manual();

    /** The project's state as recorded by the platform: tasks, runs, blockers, team activity. */
    String state(String projectId);

    /**
     * Carries out one order the CTO gave through the Lead, with the CTO's rights. Never throws: a refused or failed
     * order comes back with {@code ok = false} and the platform's reason.
     */
    Outcome execute(String projectId, String userId, String leadId, JsonNode order);

    record Outcome(String action, boolean ok, String detail) {}
}
