package dev.aiw.agentruntime;

/**
 * Executes one job type. Implemented by the modules that own the work (orchestration, collaboration).
 * {@link #handle} may be called again for the same job after a crash or a failed attempt, so it must
 * write its results in a single transaction at the end and check whether the work was already done.
 */
public interface JobHandler {
    String type();

    void handle(AgentJob job);

    /** Called once when the job has failed for good, so the owner can record the failure on its own objects. */
    default void onFailed(AgentJob job, String error) {}

    /** Called when a human retries a failed job, before it is queued again. */
    default void onRetry(AgentJob job) {}
}
