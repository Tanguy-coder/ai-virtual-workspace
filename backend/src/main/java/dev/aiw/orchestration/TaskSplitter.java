package dev.aiw.orchestration;

import dev.aiw.agentruntime.AgentJob;
import dev.aiw.agentruntime.AgentJobs;
import dev.aiw.agentruntime.AgentRunner;
import dev.aiw.agentruntime.JobHandler;
import dev.aiw.events.EventPublisher;
import dev.aiw.events.Subject;
import dev.aiw.governance.AuditLog;
import dev.aiw.organization.Agent;
import dev.aiw.organization.AgentService;
import dev.aiw.scm.GitService;
import dev.aiw.shared.Actor;
import dev.aiw.shared.ApiException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * A task too big for its budget is split by the Lead into smaller tasks (CTO requirement, 2026-11-10). The new tasks
 * cover the original's acceptance criteria, inherit its dependencies and its dependents; the first one continues the
 * work already committed on the original branch. The original is cancelled, its branch kept.
 */
@Service
public class TaskSplitter {
    static final String SPLIT = "LEAD_SPLIT";
    private static final Set<String> UNDER_REVIEW = Set.of("IN_REVIEW", "IN_TEST", "VALIDATION");

    private final TaskService tasks;
    private final TaskRuns runs;
    private final TaskExecution execution;
    private final AgentService agents;
    private final AgentJobs jobs;
    private final AgentRunner runner;
    private final GitService git;
    private final EventPublisher events;
    private final AuditLog audit;
    private final TransactionTemplate tx;

    TaskSplitter(TaskService tasks, TaskRuns runs, TaskExecution execution, AgentService agents, AgentJobs jobs,
                 AgentRunner runner, GitService git, EventPublisher events, AuditLog audit, TransactionTemplate tx) {
        this.tasks = tasks;
        this.runs = runs;
        this.execution = execution;
        this.agents = agents;
        this.jobs = jobs;
        this.runner = runner;
        this.git = git;
        this.events = events;
        this.audit = audit;
        this.tx = tx;
    }

    /** The CTO asks the Lead to split a task. Work on it must be paused or blocked first. */
    public void request(String taskId, String userId, String guidance) {
        Task task = tasks.find(taskId).orElseThrow(() -> ApiException.notFound("Task"));
        if (TaskService.CLOSED.contains(task.status())) throw ApiException.conflict("task_closed", "The task is " + task.status().toLowerCase());
        if (UNDER_REVIEW.contains(task.status())) {
            throw ApiException.conflict("task_in_review", "The task is being reviewed; split it only if the review fails");
        }
        Optional<TaskRun> run = runs.openForTask(taskId);
        if (run.isPresent() && !Set.of("PAUSED", "BLOCKED").contains(run.get().status())) {
            throw ApiException.conflict("run_active", "Pause the work on this task before splitting it");
        }
        Agent lead = agents.lead(task.projectId()).filter(l -> "ACTIVE".equals(l.status()) && l.model() != null)
                .orElseThrow(() -> ApiException.conflict("no_lead", "The project has no active Lead with a model"));
        String ws = tasks.workspaceOf(task);
        Actor actor = Actor.human(userId);
        tx.executeWithoutResult(s -> {
            jobs.enqueue(ws, task.projectId(), lead.id(), SPLIT,
                    Map.of("taskId", taskId, "guidance", guidance == null ? "" : guidance.strip()), taskId);
            events.publish(ws, task.projectId(), "task.split_requested", actor, new Subject("task", taskId),
                    Map.of("number", task.number(), "leadId", lead.id()));
            audit.record(ws, actor, "task.split", "task", taskId, AuditLog.Decision.RECORDED, Map.of("number", String.valueOf(task.number())));
        });
    }

    void split(AgentJob job) {
        Task task = tasks.find(job.string("taskId")).orElseThrow();
        if (TaskService.CLOSED.contains(task.status())) return; // already split (or cancelled meanwhile)
        Agent lead = runner.agent(job);
        List<Agent> team = agents.list(task.projectId()).stream()
                .filter(a -> "ACTIVE".equals(a.status()) && a.model() != null && a.providerId() != null).toList();
        Map<String, Agent> byName = team.stream().collect(Collectors.toMap(a -> a.name().toLowerCase(), Function.identity(), (a, b) -> a));
        TaskRun run = runs.openForTask(task.id()).orElse(null);
        boolean committed = run != null && !git.changes(Path.of(run.worktree()), GitService.MAIN, "HEAD").isEmpty();
        long budget = execution.taskTokenBudget(task.projectId());

        StringBuilder sb = new StringBuilder();
        sb.append("The task below is too big for one run. Split it into 2 to 8 smaller tasks that together deliver it, and assign each.\n\n");
        sb.append("<task number=\"T-").append(task.number()).append("\">\n").append(task.title()).append("\n");
        if (!task.description().isBlank()) sb.append(task.description().strip()).append("\n");
        sb.append("Acceptance criteria:\n");
        for (int i = 0; i < task.acceptanceCriteria().size(); i++) {
            sb.append(i + 1).append(". ").append(task.acceptanceCriteria().get(i).text()).append("\n");
        }
        sb.append("</task>\n");
        if (run != null) {
            sb.append("<work_so_far recorded_by=\"platform\">\n").append(execution.workStatus(run.agentId()));
            if (committed) {
                sb.append("Committed on ").append(run.branch()).append(": ")
                        .append(git.changes(Path.of(run.worktree()), GitService.MAIN, "HEAD").stream()
                                .map(c -> c.status() + " " + c.path()).limit(40).collect(Collectors.joining(", "))).append("\n");
            }
            sb.append("</work_so_far>\n");
        }
        String guidance = job.string("guidance");
        if (guidance != null && !guidance.isBlank()) sb.append("<cto_guidance>\n").append(guidance).append("\n</cto_guidance>\n");
        sb.append("<team>\n");
        team.forEach(a -> sb.append("- ").append(a.name()).append(": ").append(a.title()).append(" [").append(a.department()).append("]\n"));
        sb.append("</team>\n\nRules:\n");
        sb.append("- Together the new tasks cover every acceptance criterion of the original; each criterion goes to the task that proves it.\n");
        sb.append(TaskSizing.rules(budget)).append("\n");
        if (committed) sb.append("- Work is already committed: list first the task that finishes it; it continues on the same branch.\n");
        sb.append("""
                - dependsOn lists refs of the new tasks only. No cycles.
                - assignee is the exact name of a team member; priority is LOW, MEDIUM, HIGH or CRITICAL.

                JSON shape:
                {"tasks": [{"ref": "s1", "title": "...", "description": "...", "acceptanceCriteria": ["..."],
                            "dependsOn": [], "assignee": "Name", "priority": "HIGH", "brief": "..."}]}""");

        JsonNode out = runner.completeJson(job, lead, runner.context(lead, task.title() + " " + task.description(), 3_000, 2_000).oneShot(),
                sb.toString(), 8_000, "medium", "Split T-" + task.number(), node -> LeadService.validateTasks(node, byName, 2, 8));

        List<Task> created = new ArrayList<>();
        tx.executeWithoutResult(s -> {
            Task current = tasks.find(task.id()).orElseThrow();
            if (TaskService.CLOSED.contains(current.status())) return;
            Actor actor = Actor.agent(lead.id());
            String ws = tasks.workspaceOf(task);
            Map<String, Task> byRef = new LinkedHashMap<>();
            Map<String, String> briefs = new HashMap<>();
            for (JsonNode t : out.path("tasks")) {
                String priority = LeadService.text(t, "priority");
                Task sub = tasks.create(ws, task.projectId(), new TaskService.Draft(LeadService.text(t, "title"),
                        LeadService.text(t, "description"), LeadService.strings(t.path("acceptanceCriteria")),
                        Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(priority) ? priority : task.priority(), null,
                        byName.get(LeadService.text(t, "assignee").toLowerCase()).id()), "SPLIT", task.id(), actor);
                byRef.put(LeadService.text(t, "ref"), sub);
                briefs.put(sub.id(), LeadService.text(t, "brief"));
            }
            boolean first = true;
            for (JsonNode t : out.path("tasks")) {
                Task sub = byRef.get(LeadService.text(t, "ref"));
                List<String> deps = new ArrayList<>(LeadService.strings(t.path("dependsOn")).stream().map(r -> byRef.get(r).id()).toList());
                if (deps.isEmpty()) {
                    if (first && committed) tasks.baseBranch(sub.id(), run.branch());
                    first = false;
                    deps.addAll(current.dependsOn());
                }
                if (!deps.isEmpty()) tasks.addDependencies(sub.id(), deps, actor);
                else tasks.settle(sub.id(), actor);
            }
            List<String> ids = byRef.values().stream().map(Task::id).toList();
            tasks.transferDependents(task.id(), ids, actor);
            tasks.cancel(task.id(), actor);
            if (run != null) execution.cancelRun(run);
            for (Task sub : byRef.values()) {
                String brief = briefs.get(sub.id());
                if (!brief.isBlank() && !sub.assigneeId().equals(lead.id())) {
                    tasks.message(tasks.find(sub.id()).orElseThrow(), actor, sub.assigneeId(), "ASSIGNMENT", brief);
                }
            }
            events.publish(ws, task.projectId(), "task.split", actor, new Subject("task", task.id()), Map.of("number", task.number(),
                    "into", byRef.values().stream().map(Task::number).toList()));
            created.addAll(byRef.values());
        });
        if (!created.isEmpty() && run != null) execution.stopSandbox(run);
    }

    void failed(AgentJob job, String error) {
        tasks.find(job.string("taskId")).ifPresent(task -> events.publish(job.workspaceId(), task.projectId(), "task.split_failed",
                Actor.agent(job.agentId()), new Subject("task", task.id()), Map.of("number", task.number(), "error", error)));
    }

    @Component
    static class SplitHandler implements JobHandler {
        private final TaskSplitter splitter;

        SplitHandler(TaskSplitter splitter) {
            this.splitter = splitter;
        }

        @Override public String type() { return SPLIT; }
        @Override public void handle(AgentJob job) { splitter.split(job); }
        @Override public void onFailed(AgentJob job, String error) { splitter.failed(job, error); }
    }
}
