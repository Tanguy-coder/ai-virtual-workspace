package dev.aiw.modelgateway;

public record ChatTurn(Role role, String text) {
    public enum Role { USER, ASSISTANT }
}
