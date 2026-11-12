package dev.aiw.orchestration;

import dev.aiw.access.WorkspaceAccess;
import dev.aiw.access.WorkspaceRole;
import dev.aiw.quality.TestRun;
import dev.aiw.quality.TestRunService;
import dev.aiw.scm.GitService;
import dev.aiw.scm.MergeRequest;
import dev.aiw.scm.MergeRequestService;
import dev.aiw.scm.ReviewComment;
import dev.aiw.shared.Actor;
import dev.aiw.shared.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Engineering Workspace API (docs/10): runs and their steps, merge requests, the repository, test runs. */
@RestController
class ExecutionController {
    private final WorkspaceAccess access;
    private final TaskService tasks;
    private final TaskRuns runs;
    private final TaskExecution execution;
    private final MergeRequestService mrs;
    private final GitService git;
    private final TestRunService tests;
    private final BranchFlow branchFlow;

    ExecutionController(WorkspaceAccess access, TaskService tasks, TaskRuns runs, TaskExecution execution,
                        MergeRequestService mrs, GitService git, TestRunService tests, BranchFlow branchFlow) {
        this.access = access;
        this.branchFlow = branchFlow;
        this.tasks = tasks;
        this.runs = runs;
        this.execution = execution;
        this.mrs = mrs;
        this.git = git;
        this.tests = tests;
    }

    // ---- runs

    record RunView(TaskRun run, List<RunStep> steps) {}

    @GetMapping("/api/v1/projects/{projectId}/runs")
    List<TaskRun> runs(@PathVariable String projectId) {
        access.requireProject(projectId, WorkspaceRole.VIEWER);
        return runs.list(projectId);
    }

    @GetMapping("/api/v1/tasks/{taskId}/run")
    RunView run(@PathVariable String taskId) {
        task(taskId, WorkspaceRole.VIEWER);
        return runs.latestForTask(taskId).map(r -> new RunView(r, runs.steps(r.id()))).orElse(new RunView(null, List.of()));
    }

    @PostMapping("/api/v1/tasks/{taskId}/run")
    TaskRun start(@PathVariable String taskId) {
        Task t = task(taskId, WorkspaceRole.MEMBER);
        return execution.start(taskId, Actor.human(member(t.projectId())));
    }

    @PostMapping("/api/v1/tasks/{taskId}/run/pause")
    TaskRun pause(@PathVariable String taskId) {
        Task t = task(taskId, WorkspaceRole.MEMBER);
        return execution.pause(taskId, member(t.projectId()));
    }

    @PostMapping("/api/v1/tasks/{taskId}/run/resume")
    TaskRun resume(@PathVariable String taskId) {
        Task t = task(taskId, WorkspaceRole.MEMBER);
        return execution.resume(taskId, Actor.human(member(t.projectId())));
    }

    record Budget(@jakarta.validation.constraints.Min(10_000) @jakarta.validation.constraints.Max(50_000_000) long tokenBudget) {}

    /** The CTO changes the token budget of the task's open run; a run stopped by its budget resumes. */
    @PutMapping("/api/v1/tasks/{taskId}/run/budget")
    TaskRun budget(@PathVariable String taskId, @Valid @RequestBody Budget body) {
        Task t = task(taskId, WorkspaceRole.MEMBER);
        return execution.setBudget(taskId, member(t.projectId()), body.tokenBudget());
    }

    record Content(@NotBlank @Size(max = 10_000) String content) {}

    @PostMapping("/api/v1/tasks/{taskId}/run/messages")
    RunStep message(@PathVariable String taskId, @Valid @RequestBody Content body) {
        Task t = task(taskId, WorkspaceRole.MEMBER);
        return execution.message(taskId, member(t.projectId()), body.content());
    }

    // ---- merge requests

    record MergeRequestView(MergeRequest mergeRequest, List<ReviewComment> comments, List<GitService.FileChange> files,
                            List<TestRun> testRuns) {}

    @GetMapping("/api/v1/projects/{projectId}/merge-requests")
    List<MergeRequest> mergeRequests(@PathVariable String projectId) {
        access.requireProject(projectId, WorkspaceRole.VIEWER);
        return mrs.list(projectId);
    }

    @GetMapping("/api/v1/merge-requests/{mrId}")
    MergeRequestView mergeRequest(@PathVariable String mrId) {
        MergeRequest mr = mr(mrId);
        Path repo = git.repo(mr.projectId()).orElseThrow();
        String head = "MERGED".equals(mr.status()) && mr.mergedCommit() != null ? mr.mergedCommit() : mr.sourceBranch();
        List<GitService.FileChange> files = "MERGED".equals(mr.status()) && mr.mergedCommit() != null
                ? git.changes(repo, mr.mergedCommit() + "^1", mr.mergedCommit())
                : git.hasBranch(repo, mr.sourceBranch()) ? git.changes(repo, mr.targetBranch(), head) : List.of();
        List<TestRun> runsOfTask = tests.list(mr.projectId(), mr.taskId(), 20);
        return new MergeRequestView(mr, mrs.comments(mrId), files, runsOfTask);
    }

    @GetMapping("/api/v1/merge-requests/{mrId}/diff")
    Map<String, String> diff(@PathVariable String mrId, @RequestParam(required = false) String path) {
        MergeRequest mr = mr(mrId);
        Path repo = git.repo(mr.projectId()).orElseThrow();
        String diff = "MERGED".equals(mr.status()) && mr.mergedCommit() != null
                ? git.diff(repo, mr.mergedCommit() + "^1", mr.mergedCommit(), path)
                : git.hasBranch(repo, mr.sourceBranch()) ? git.diff(repo, mr.targetBranch(), mr.sourceBranch(), path) : "";
        return Map.of("diff", diff.length() > 400_000 ? diff.substring(0, 400_000) : diff);
    }

    record Review(boolean approve, @Size(max = 10_000) String comment) {}

    /** The CTO reviews: approving also approves the merge; requesting changes sends the comment to the agent. */
    @PostMapping("/api/v1/merge-requests/{mrId}/review")
    MergeRequest review(@PathVariable String mrId, @Valid @RequestBody Review body) {
        MergeRequest mr = mr(mrId);
        String user = member(mr.projectId());
        if (!body.approve() && (body.comment() == null || body.comment().isBlank())) {
            throw ApiException.badRequest("comment_required", "Say what to change");
        }
        return execution.humanReview(mrId, user, body.approve(), body.comment());
    }

    // ---- repository

    @GetMapping("/api/v1/projects/{projectId}/repo/branches")
    List<GitService.Branch> branches(@PathVariable String projectId) {
        access.requireProject(projectId, WorkspaceRole.VIEWER);
        return git.repo(projectId).map(git::branches).orElse(List.of());
    }

    /** The CTO deletes a branch: the click is the explicit approval, audited (main and develop never). */
    @org.springframework.web.bind.annotation.DeleteMapping("/api/v1/projects/{projectId}/repo/branches")
    Map<String, Object> deleteBranch(@PathVariable String projectId, @RequestParam String name) {
        String user = member(projectId);
        return branchFlow.deleteBranch(access.workspaceOfProject(projectId), projectId, name, user);
    }

    @GetMapping("/api/v1/projects/{projectId}/repo/log")
    List<GitService.Commit> log(@PathVariable String projectId, @RequestParam(defaultValue = "main") String ref) {
        access.requireProject(projectId, WorkspaceRole.VIEWER);
        return git.repo(projectId).map(r -> git.log(r, safeRef(ref), 100)).orElse(List.of());
    }

    @GetMapping("/api/v1/projects/{projectId}/repo/tree")
    List<GitService.Entry> tree(@PathVariable String projectId, @RequestParam(defaultValue = "main") String ref) {
        access.requireProject(projectId, WorkspaceRole.VIEWER);
        return git.repo(projectId).map(r -> git.tree(r, safeRef(ref))).orElse(List.of());
    }

    @GetMapping("/api/v1/projects/{projectId}/repo/file")
    Map<String, String> file(@PathVariable String projectId, @RequestParam(defaultValue = "main") String ref,
                             @RequestParam String path) {
        access.requireProject(projectId, WorkspaceRole.VIEWER);
        Path repo = git.repo(projectId).orElseThrow(() -> ApiException.notFound("Repository"));
        String content = git.show(repo, safeRef(ref), path).orElseThrow(() -> ApiException.notFound("File"));
        return Map.of("path", path, "ref", ref, "content", content.length() > 1_000_000 ? content.substring(0, 1_000_000) : content);
    }

    /** Diff of a branch against the branch it merges into: develop for task branches, main for develop. */
    @GetMapping("/api/v1/projects/{projectId}/repo/compare")
    Map<String, Object> compare(@PathVariable String projectId, @RequestParam String ref, @RequestParam(required = false) String path) {
        access.requireProject(projectId, WorkspaceRole.VIEWER);
        Path repo = git.repo(projectId).orElseThrow(() -> ApiException.notFound("Repository"));
        String r = safeRef(ref);
        String base = r.equals(GitService.DEVELOP) || r.equals(GitService.MAIN) ? GitService.MAIN : git.integrationBranch(repo);
        return Map.of("files", git.changes(repo, base, r), "diff", path == null ? "" : git.diff(repo, base, r, path), "base", base);
    }

    // ---- tests

    @GetMapping("/api/v1/projects/{projectId}/test-runs")
    List<TestRun> testRuns(@PathVariable String projectId, @RequestParam(required = false) String taskId) {
        access.requireProject(projectId, WorkspaceRole.VIEWER);
        return tests.list(projectId, taskId, 200);
    }

    @GetMapping("/api/v1/test-runs/{id}/output")
    Map<String, String> testOutput(@PathVariable String id) {
        TestRun tr = tests.find(id).orElseThrow(() -> ApiException.notFound("Test run"));
        access.requireProject(tr.projectId(), WorkspaceRole.VIEWER);
        return Map.of("output", tests.output(id).orElse(""));
    }

    // ---- helpers

    private Task task(String taskId, WorkspaceRole role) {
        Task t = tasks.find(taskId).orElseThrow(() -> ApiException.notFound("Task"));
        access.requireProject(t.projectId(), role);
        return t;
    }

    private MergeRequest mr(String id) {
        MergeRequest mr = mrs.find(id).orElseThrow(() -> ApiException.notFound("Merge request"));
        access.requireProject(mr.projectId(), WorkspaceRole.VIEWER);
        return mr;
    }

    private String member(String projectId) {
        return access.require(access.workspaceOfProject(projectId), WorkspaceRole.MEMBER);
    }

    /** Branch names and commit ids only: nothing that git could read as an option or a path. */
    private static String safeRef(String ref) {
        if (ref == null || !ref.matches("[A-Za-z0-9._/-]{1,200}") || ref.startsWith("-") || ref.contains("..")) {
            throw ApiException.badRequest("invalid_ref", "Invalid branch or commit");
        }
        return ref;
    }

}
