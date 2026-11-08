package dev.aiw.agentruntime;

/** A failure that retrying cannot fix (no model configured, budget exhausted, agent paused). */
public class JobFailure extends RuntimeException {
    public JobFailure(String message) {
        super(message);
    }
}
