package dev.aiw.quality;

import java.time.Instant;

/** A security or quality finding (docs/18): proof, severity, what to do, and where it stands. */
public record Finding(String id, String projectId, int number, String kind, String tool, String rule, String severity,
                      String title, String file, Integer line, String location, String evidence, String reference,
                      String recommendation, String status, String taskId, String acceptedBy, String acceptedReason,
                      Instant createdAt, Instant updatedAt, Instant fixedAt) {

    /** Blocks develop → main while open: critical or high, neither fixed nor accepted. */
    public boolean blocking() {
        return ("CRITICAL".equals(severity) || "HIGH".equals(severity)) && ("OPEN".equals(status) || "FIX_TASK".equals(status));
    }

    public String label() {
        return "F-" + number + " " + severity + " " + title + (file == null ? location == null ? "" : " (" + location + ")"
                : " (" + file + (line == null ? "" : ":" + line) + ")");
    }
}
