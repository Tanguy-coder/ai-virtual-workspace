package dev.aiw.collaboration;

/**
 * What a participant is working on, given to them in a realignment meeting so they can expose real problems.
 * Implemented by the module that owns the work (orchestration); collaboration does not depend on it.
 */
public interface ParticipantStatus {
    /** A few lines built by code (task, state, block reason, budget), or an empty string when there is no open work. */
    String of(String agentId);
}
