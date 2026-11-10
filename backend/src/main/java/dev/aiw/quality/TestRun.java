package dev.aiw.quality;

import java.time.Instant;

/** A test run executed by the platform in a sandbox. */
public record TestRun(String id, String projectId, int number, String taskId, String runId, String mrId, String branch,
                      String commitSha, String trigger, String command, String status, Integer passed, Integer failed,
                      long durationMs, String failingExcerpt, Instant createdAt) {
    public boolean succeeded() {
        return "PASSED".equals(status);
    }
}
