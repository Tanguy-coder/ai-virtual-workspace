package dev.aiw.shared;

/** Who performed an action: a human user, an agent, or the platform itself. */
public record Actor(Kind kind, String id) {
    public enum Kind { HUMAN, AGENT, SYSTEM }

    public static Actor human(String userId) { return new Actor(Kind.HUMAN, userId); }
    public static Actor agent(String agentId) { return new Actor(Kind.AGENT, agentId); }
    public static Actor system() { return new Actor(Kind.SYSTEM, null); }
}
