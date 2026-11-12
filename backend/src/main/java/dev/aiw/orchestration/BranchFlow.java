package dev.aiw.orchestration;

import dev.aiw.events.EventPublisher;
import dev.aiw.events.Subject;
import dev.aiw.governance.ApprovalDecided;
import dev.aiw.governance.ApprovalService;
import dev.aiw.governance.AuditLog;
import dev.aiw.shared.ApiException;
import dev.aiw.organization.Agent;
import dev.aiw.organization.AgentService;
import dev.aiw.scm.GitService;
import dev.aiw.shared.Actor;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The project's branch model (CTO requirement, 2026-11-11): the Lead keeps a {@code develop} branch; agents branch
 * from it and their merge requests go into it once the Lead approves. {@code develop} reaches {@code main} only when
 * the Lead asks and the CTO approves.
 */
@Service
public class BranchFlow {
    private static final Logger log = LoggerFactory.getLogger(BranchFlow.class);
    static final String MERGE_MAIN = "branch.merge_main";

    private final GitService git;
    private final ApprovalService approvals;
    private final EventPublisher events;
    private final AgentService agents;
    private final TaskService tasks;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final AuditLog audit;

    BranchFlow(GitService git, ApprovalService approvals, EventPublisher events, AgentService agents, TaskService tasks,
               JdbcClient jdbc, TransactionTemplate tx, AuditLog audit) {
        this.audit = audit;
        this.git = git;
        this.approvals = approvals;
        this.events = events;
        this.agents = agents;
        this.tasks = tasks;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** The Lead creates develop from main the first time the project needs it. Returns the integration branch. */
    public String ensureDevelop(String workspaceId, String projectId, Path repo) {
        if (git.ensureDevelop(repo)) {
            Actor lead = leadActor(projectId);
            tx.executeWithoutResult(s -> events.publish(workspaceId, projectId, "branch.created", lead,
                    new Subject("project", projectId), Map.of("branch", GitService.DEVELOP, "from", GitService.MAIN)));
        }
        return GitService.DEVELOP;
    }

    public record MainMerge(boolean requested, String detail, String approvalId) {}

    /**
     * The Lead asks the CTO to merge develop into main. Refused (with the reason) when develop brings nothing, when a
     * request is already waiting, or when main moved in a way that conflicts with develop.
     */
    public MainMerge requestMainMerge(String workspaceId, String projectId, String note) {
        Optional<Path> repo = git.repo(projectId);
        if (repo.isEmpty() || !git.hasBranch(repo.get(), GitService.DEVELOP)) {
            return new MainMerge(false, "The project has no develop branch yet: it is created when the first task starts", null);
        }
        Optional<String> pending = jdbc.sql("""
                SELECT id FROM approvals WHERE project_id = ? AND action = ? AND status = 'PENDING' LIMIT 1
                """).params(projectId, MERGE_MAIN).query(String.class).optional();
        if (pending.isPresent()) return new MainMerge(false, "A merge of develop into main is already waiting for the CTO", pending.get());
        int ahead = git.ahead(repo.get(), GitService.MAIN, GitService.DEVELOP);
        if (ahead == 0) return new MainMerge(false, "develop has nothing that main does not already have", null);
        List<String> conflicts = git.conflicts(repo.get(), GitService.MAIN, GitService.DEVELOP);
        if (!conflicts.isEmpty()) {
            return new MainMerge(false, "develop conflicts with main in " + String.join(", ", conflicts), null);
        }
        String commit = git.revParse(repo.get(), GitService.DEVELOP);
        List<String> merged = jdbc.sql("""
                SELECT 'T-' || t.number || ' ' || t.title FROM merge_requests m JOIN tasks t ON t.id = m.task_id
                WHERE m.project_id = ? AND m.status = 'MERGED' AND m.target_branch = 'develop'
                  AND m.merged_at > coalesce((SELECT max(decided_at) FROM approvals
                                              WHERE project_id = ? AND action = ? AND status = 'APPROVED'), 'epoch')
                ORDER BY m.merged_at DESC LIMIT 200
                """).params(projectId, projectId, MERGE_MAIN).query(String.class).list();
        Map<String, Object> details = new HashMap<>();
        details.put("from", GitService.DEVELOP);
        details.put("into", GitService.MAIN);
        details.put("commit", commit);
        details.put("commits", ahead);
        details.put("tasks", merged.stream().limit(50).toList());
        if (note != null && !note.isBlank()) details.put("note", AgentTools.clip(note.strip(), 2_000));
        String id = approvals.request(workspaceId, projectId, leadActor(projectId), MERGE_MAIN, "project", projectId,
                "Merge develop into main (" + ahead + " commit" + (ahead == 1 ? "" : "s") + ", " + commit.substring(0, 7) + ")", details);
        return new MainMerge(true, "Merge of develop into main (" + ahead + " commits) sent to the CTO for approval", id);
    }

    /** Called after a task merged into develop: when no task is left open, the Lead asks for the merge into main. */
    void afterTaskMerged(String workspaceId, String projectId) {
        boolean open = tasks.list(projectId).stream().anyMatch(t -> !TaskService.CLOSED.contains(t.status()));
        if (open) return;
        try {
            MainMerge m = requestMainMerge(workspaceId, projectId, "Every task of the project is merged into develop.");
            log.info("Project {}: all tasks merged, main merge request: {}", projectId, m.detail());
        } catch (RuntimeException e) {
            log.warn("Could not request the merge of develop into main for project {}", projectId, e);
        }
    }

    /** The CTO decided: on approval, the commit of develop the Lead proposed is merged into main. */
    @EventListener
    void onApproval(ApprovalDecided e) {
        if (!MERGE_MAIN.equals(e.action()) || !e.approved()) return;
        String commit = jdbc.sql("SELECT details->>'commit' FROM approvals WHERE id = ?").param(e.approvalId())
                .query(String.class).optional().orElse(null);
        Path repo = git.repo(e.projectId()).orElse(null);
        Map<String, Object> payload = new HashMap<>();
        payload.put("from", GitService.DEVELOP);
        payload.put("into", GitService.MAIN);
        payload.put("approvalId", e.approvalId());
        try {
            if (repo == null || commit == null) throw new GitService.GitException("the repository or the proposed commit is missing");
            List<String> conflicts = git.conflicts(repo, GitService.MAIN, commit);
            if (!conflicts.isEmpty()) throw new GitService.GitException("main now conflicts with develop in " + String.join(", ", conflicts));
            String merged = git.mergeIntoMain(repo, commit, "Merge develop into main (" + commit.substring(0, 7) + ")", GitService.Author.PLATFORM);
            payload.put("commit", merged);
            payload.put("source", commit);
            events.publish(e.workspaceId(), e.projectId(), "branch.merged", Actor.human(e.userId()), new Subject("project", e.projectId()), payload);
        } catch (RuntimeException ex) {
            log.warn("Merge of develop into main failed for project {}", e.projectId(), ex);
            payload.put("reason", AgentTools.clip(ex.getMessage() == null ? "unknown error" : ex.getMessage(), 500));
            events.publish(e.workspaceId(), e.projectId(), "branch.merge_failed", Actor.system(), new Subject("project", e.projectId()), payload);
        }
    }

    /** Runs whose work is not over: their branch must stay. */
    private static final String OPEN_RUN = "('RUNNING', 'PAUSED', 'SUBMITTED', 'BLOCKED')";

    /**
     * A task branch merged into develop (or main) has nothing left to give: the merge commit keeps its history. It is
     * deleted with its worktree, unless an open task still works on it (a split task continues on its parent's branch).
     */
    public boolean deleteMergedTaskBranch(String workspaceId, String projectId, String branch, String into, int mrNumber) {
        Path repo = git.repo(projectId).orElse(null);
        if (repo == null || !git.hasBranch(repo, branch) || !git.mergedInto(repo, branch, into) || stillUsed(projectId, branch)) return false;
        if (!git.deleteBranch(repo, branch)) return false;
        tx.executeWithoutResult(s -> events.publish(workspaceId, projectId, "branch.deleted", Actor.system(),
                new Subject("project", projectId), Map.of("branch", branch, "mergedInto", into, "mr", mrNumber)));
        return true;
    }

    /** Merged task branches left from before branches were deleted on merge. */
    void deleteLeftoverMergedBranches() {
        record Merged(String ws, String project, String branch, String into, int number) {}
        jdbc.sql("""
                SELECT workspace_id, project_id, source_branch, target_branch, number FROM merge_requests m WHERE status = 'MERGED'
                  AND NOT EXISTS (SELECT 1 FROM merge_requests o WHERE o.source_branch = m.source_branch
                                  AND o.project_id = m.project_id AND o.status NOT IN ('MERGED', 'CLOSED'))
                """).query((rs, i) -> new Merged(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5)))
                .list().forEach(m -> {
                    try {
                        deleteMergedTaskBranch(m.ws(), m.project(), m.branch(), m.into(), m.number());
                    } catch (RuntimeException e) {
                        log.warn("Could not delete merged branch {} of project {}", m.branch(), m.project(), e);
                    }
                });
    }

    /**
     * The CTO deletes a branch from the Branches page: the click is the explicit approval (audited). Refused for main,
     * develop and any branch an open task still works on. An unmerged branch loses its commits.
     */
    public Map<String, Object> deleteBranch(String workspaceId, String projectId, String branch, String userId) {
        if (GitService.MAIN.equals(branch) || GitService.DEVELOP.equals(branch)) {
            throw ApiException.conflict("protected_branch", branch + " is never deleted");
        }
        Path repo = git.repo(projectId).filter(r -> git.hasBranch(r, branch))
                .orElseThrow(() -> ApiException.notFound("Branch"));
        if (stillUsed(projectId, branch)) {
            throw ApiException.conflict("branch_in_use", "An open task still works on " + branch + ": cancel it or let it finish first");
        }
        boolean merged = git.mergedInto(repo, branch, git.integrationBranch(repo)) || git.mergedInto(repo, branch, GitService.MAIN);
        String head = git.revParse(repo, branch);
        if (!git.deleteBranch(repo, branch)) throw ApiException.conflict("delete_failed", "Git could not delete " + branch);
        Map<String, Object> details = Map.of("branch", branch, "head", head, "merged", merged);
        tx.executeWithoutResult(s -> {
            jdbc.sql("UPDATE merge_requests SET status = 'CLOSED', updated_at = now() WHERE project_id = ? AND source_branch = ? AND status NOT IN ('MERGED', 'CLOSED')")
                    .params(projectId, branch).update();
            audit.record(workspaceId, Actor.human(userId), "branch.delete", "project", projectId, AuditLog.Decision.APPROVED, details);
            events.publish(workspaceId, projectId, "branch.deleted", Actor.human(userId), new Subject("project", projectId), details);
        });
        return details;
    }

    private boolean stillUsed(String projectId, String branch) {
        return jdbc.sql("SELECT count(*) FROM task_runs WHERE project_id = ? AND branch = ? AND status IN " + OPEN_RUN)
                .params(projectId, branch).query(Long.class).single() > 0
                || jdbc.sql("""
                        SELECT count(*) FROM tasks WHERE project_id = ? AND base_branch = ? AND status NOT IN ('DONE', 'CANCELLED')
                        """).params(projectId, branch).query(Long.class).single() > 0;
    }

    private Actor leadActor(String projectId) {
        return agents.lead(projectId).map(Agent::id).map(Actor::agent).orElse(Actor.system());
    }
}
