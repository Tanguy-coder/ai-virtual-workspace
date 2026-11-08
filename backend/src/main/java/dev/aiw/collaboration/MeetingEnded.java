package dev.aiw.collaboration;

/** Published in the transaction that stores a meeting's minutes. */
public record MeetingEnded(String workspaceId, String projectId, String meetingId, String purpose) {}
