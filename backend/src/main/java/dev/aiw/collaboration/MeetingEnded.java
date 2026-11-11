package dev.aiw.collaboration;

/**
 * Published in the transaction that closes a meeting: {@code outcome} is ENDED (minutes stored) or CANCELLED.
 */
public record MeetingEnded(String workspaceId, String projectId, String meetingId, String purpose, String outcome) {
    public boolean ended() {
        return "ENDED".equals(outcome);
    }
}
