package dev.aiw.scm;

import dev.aiw.events.EventPublisher;
import dev.aiw.events.Subject;
import dev.aiw.shared.Actor;
import dev.aiw.shared.Ids;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Merge requests and their review comments. The workflow that moves them lives in orchestration. */
@Service
public class MergeRequestService {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private final JdbcClient jdbc;
    private final EventPublisher events;
    private final JsonMapper json;

    MergeRequestService(JdbcClient jdbc, EventPublisher events, JsonMapper json) {
        this.jdbc = jdbc;
        this.events = events;
        this.json = json;
    }

    public MergeRequest open(String workspaceId, String projectId, String taskId, String runId, String branch, String target,
                             String authorId, String reviewerId, String title, String summary, String head) {
        String id = Ids.newId();
        int number = jdbc.sql("SELECT coalesce(max(number), 0) + 1 FROM merge_requests WHERE project_id = ?")
                .param(projectId).query(Integer.class).single();
        jdbc.sql("""
                INSERT INTO merge_requests (id, workspace_id, project_id, number, task_id, run_id, source_branch, target_branch,
                                            author_agent_id, reviewer_agent_id, title, summary, status, head_commit)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'IN_REVIEW', ?)
                """).params(id, workspaceId, projectId, number, taskId, runId, branch, target, authorId, reviewerId, title,
                summary, head).update();
        Map<String, Object> payload = new HashMap<>();
        payload.put("number", number);
        payload.put("title", title);
        payload.put("taskId", taskId);
        payload.put("branch", branch);
        payload.put("target", target);
        payload.put("reviewerId", reviewerId);
        events.publish(workspaceId, projectId, "merge_request.opened", Actor.agent(authorId), new Subject("merge_request", id), payload);
        return find(id).orElseThrow();
    }

    public Optional<MergeRequest> find(String id) {
        return jdbc.sql("SELECT * FROM merge_requests WHERE id = ?").param(id).query(this::map).optional();
    }

    public Optional<MergeRequest> openForTask(String taskId) {
        return jdbc.sql("""
                SELECT * FROM merge_requests WHERE task_id = ? AND status NOT IN ('MERGED', 'CLOSED')
                ORDER BY created_at DESC LIMIT 1
                """).param(taskId).query(this::map).optional();
    }

    public List<MergeRequest> list(String projectId) {
        return jdbc.sql("SELECT * FROM merge_requests WHERE project_id = ? ORDER BY number DESC LIMIT 200")
                .param(projectId).query(this::map).list();
    }

    public void update(String id, String status, String headCommit, Map<String, Object> checks) {
        jdbc.sql("""
                UPDATE merge_requests SET status = coalesce(?, status), head_commit = coalesce(?, head_commit),
                       checks = coalesce(CAST(? AS jsonb), checks), updated_at = now() WHERE id = ?
                """).params(status, headCommit, checks == null ? null : json.writeValueAsString(checks), id).update();
    }

    /** A resubmitted merge request goes where new work goes (develop once the project has it). */
    public void retarget(String id, String target) {
        jdbc.sql("UPDATE merge_requests SET target_branch = ?, updated_at = now() WHERE id = ?").params(target, id).update();
    }

    public void nextRound(String id, String reviewerId) {
        jdbc.sql("UPDATE merge_requests SET review_rounds = review_rounds + 1, reviewer_agent_id = ?, updated_at = now() WHERE id = ?")
                .params(reviewerId, id).update();
    }

    /** No agent reviews any more: the review is the CTO's. */
    public void handToHuman(String id) {
        jdbc.sql("UPDATE merge_requests SET status = 'IN_REVIEW', reviewer_agent_id = NULL, updated_at = now() WHERE id = ?")
                .param(id).update();
    }

    public void setApproval(String id, String approvalId) {
        jdbc.sql("UPDATE merge_requests SET approval_id = ?, updated_at = now() WHERE id = ?").params(approvalId, id).update();
    }

    public void markMerged(String id, String commit, String userId) {
        jdbc.sql("""
                UPDATE merge_requests SET status = 'MERGED', merged_commit = ?, merged_by = ?, merged_at = now(),
                       updated_at = now() WHERE id = ?
                """).params(commit, userId, id).update();
    }

    public void comment(String mrId, int round, Actor author, String file, Integer line, String body) {
        jdbc.sql("""
                INSERT INTO review_comments (id, mr_id, round, author_kind, author_id, file, line, body)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """).params(Ids.newId(), mrId, round, author.kind() == Actor.Kind.HUMAN ? "HUMAN" : "AGENT", author.id(),
                file, line, body).update();
    }

    public List<ReviewComment> comments(String mrId) {
        return jdbc.sql("SELECT * FROM review_comments WHERE mr_id = ? ORDER BY created_at").param(mrId)
                .query((rs, i) -> new ReviewComment(rs.getString("id"), rs.getString("mr_id"), rs.getInt("round"),
                        rs.getString("author_kind"), rs.getString("author_id"), rs.getString("file"),
                        (Integer) rs.getObject("line"), rs.getString("body"), rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    private MergeRequest map(ResultSet rs, int row) throws SQLException {
        var merged = rs.getTimestamp("merged_at");
        return new MergeRequest(rs.getString("id"), rs.getString("project_id"), rs.getInt("number"), rs.getString("task_id"),
                rs.getString("run_id"), rs.getString("source_branch"), rs.getString("target_branch"),
                rs.getString("author_agent_id"), rs.getString("reviewer_agent_id"), rs.getString("title"),
                rs.getString("summary"), rs.getString("status"), rs.getInt("review_rounds"), rs.getString("head_commit"),
                json.readValue(rs.getString("checks"), MAP), rs.getString("approval_id"), rs.getString("merged_commit"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                merged == null ? null : merged.toInstant());
    }
}
