package dev.aiw.modelgateway;

/** A provider call failed (network, auth, rate limit, invalid request...). Message is safe to show users. */
public class ModelCallException extends RuntimeException {
    private final int statusCode;

    public ModelCallException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int statusCode() { return statusCode; }
}
