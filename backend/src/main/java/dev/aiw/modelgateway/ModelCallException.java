package dev.aiw.modelgateway;

/** A provider call failed (network, auth, rate limit, invalid request...). Message is safe to show users. */
public class ModelCallException extends RuntimeException {
    private final int statusCode;
    /** Seconds the provider asked to wait (Retry-After header), or -1. */
    private final long retryAfterSeconds;

    public ModelCallException(String message, int statusCode, Throwable cause) {
        this(message, statusCode, cause, -1);
    }

    public ModelCallException(String message, int statusCode, Throwable cause, long retryAfterSeconds) {
        super(message, cause);
        this.statusCode = statusCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int statusCode() { return statusCode; }

    public long retryAfterSeconds() { return retryAfterSeconds; }
}
