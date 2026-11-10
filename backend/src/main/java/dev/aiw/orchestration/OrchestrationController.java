package dev.aiw.orchestration;

import dev.aiw.access.WorkspaceAccess;
import dev.aiw.access.WorkspaceRole;
import dev.aiw.collaboration.CollaborationService;
import dev.aiw.collaboration.Meeting;
import dev.aiw.collaboration.MeetingService;
import dev.aiw.organization.Agent;
import dev.aiw.organization.Department;
import dev.aiw.shared.Actor;
import dev.aiw.shared.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OrchestrationController {
    private final WorkspaceAccess access;
    private final LeadService lead;
    private final TaskService tasks;
    private final MeetingService meetings;
    private final CollaborationService collaboration;

    private TaskExecution execution;

    @org.springframework.beans.factory.annotation.Autowired
    void setExecution(TaskExecution execution) {
        this.execution = execution;
    }

    OrchestrationController(WorkspaceAccess access, LeadService lead, TaskService tasks, MeetingService meetings,
                            CollaborationService collaboration) {
        this.access = access;
        this.lead = lead;
        this.tasks = tasks;
        this.meetings = meetings;
        this.collaboration = collaboration;
    }

    // ---- Lead briefing ----

    @GetMapping("/api/v1/projects/{projectId}/lead/briefings")
    List<Briefing> briefings(@PathVariable String projectId) {
        access.requireProject(projectId, WorkspaceRole.VIEWER);
        return lead.briefings(projectId);
    }

    @PostMapping("/api/v1/projects/{projectId}/lead/briefings")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Briefing brief(@PathVariable String projectId) {
        String ws = access.requireProject(projectId, WorkspaceRole.MEMBER);
        return lead.start(ws, projectId, access.require(ws, WorkspaceRole.MEMBER));
    }

    record BriefingDetail(Briefing briefing, List<HiringProposal> proposals, String threadId) {}

    @GetMapping("/api/v1/briefings/{briefingId}")
    BriefingDetail briefing(@PathVariable String briefingId) {
        Briefing b = loadBriefing(briefingId, WorkspaceRole.VIEWER);
        return new BriefingDetail(b, lead.proposals(briefingId), collaboration.threadOf("BRIEFING", briefingId).orElse(null));
    }

    record Answers(@NotNull Map<String, @Size(max = 5_000) String> answers) {}

    @PutMapping("/api/v1/briefings/{briefingId}/answers")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Briefing answer(@PathVariable String briefingId, @Valid @RequestBody Answers body) {
        Briefing b = loadBriefing(briefingId, WorkspaceRole.MEMBER);
        return lead.answer(briefingId, user(b.projectId()), body.answers());
    }

    record ProposalLine(@NotBlank String templateId, @Min(0) @Max(4) int count, Department department,
                        @Pattern(regexp = "JUNIOR|MID|SENIOR|PRINCIPAL") String seniority,
                        @Size(max = 30) List<@Size(max = 80) String> skills,
                        @Size(max = 30) List<@Size(max = 300) String> responsibilities) {}

    record Accept(@NotNull @Size(min = 1, max = 30) List<@Valid ProposalLine> lines, Boolean kickoff) {}

    @PostMapping("/api/v1/hiring-proposals/{proposalId}/accept")
    List<Agent> accept(@PathVariable String proposalId, @Valid @RequestBody Accept body) {
        HiringProposal p = lead.proposal(proposalId).orElseThrow(() -> ApiException.notFound("Proposal"));
        access.requireProject(p.projectId(), WorkspaceRole.MEMBER);
        return lead.accept(proposalId, user(p.projectId()), body.lines().stream()
                .map(l -> new LeadService.Line(l.templateId(), l.count(), l.department(), l.seniority(), l.skills(),
                        l.responsibilities())).toList(), body.kickoff() == null || body.kickoff());
    }

    record Comment(@NotBlank @Size(max = 5_000) String comment) {}

    @PostMapping("/api/v1/hiring-proposals/{proposalId}/revise")
    @ResponseStatus(HttpStatus.ACCEPTED)
    HiringProposal revise(@PathVariable String proposalId, @Valid @RequestBody Comment body) {
        HiringProposal p = lead.proposal(proposalId).orElseThrow(() -> ApiException.notFound("Proposal"));
        access.requireProject(p.projectId(), WorkspaceRole.MEMBER);
        return lead.requestRevision(proposalId, user(p.projectId()), body.comment());
    }

    record Reason(@Size(max = 2_000) String reason) {}

    @PostMapping("/api/v1/hiring-proposals/{proposalId}/reject")
    HiringProposal reject(@PathVariable String proposalId, @RequestBody(required = false) Reason body) {
        HiringProposal p = lead.proposal(proposalId).orElseThrow(() -> ApiException.notFound("Proposal"));
        access.requireProject(p.projectId(), WorkspaceRole.MEMBER);
        return lead.reject(proposalId, user(p.projectId()), body == null ? null : body.reason());
    }

    /** Pending decisions for the Inbox: Lead questions to answer and team proposals to review. */
    @GetMapping("/api/v1/workspaces/{workspaceId}/briefings/waiting")
    List<Briefing> waiting(@PathVariable String workspaceId) {
        access.require(workspaceId, WorkspaceRole.VIEWER);
        return lead.waiting(workspaceId);
    }

    // ---- tasks ----

    @GetMapping("/api/v1/projects/{projectId}/tasks")
    List<Task> tasks(@PathVariable String projectId) {
        access.requireProject(projectId, WorkspaceRole.VIEWER);
        return tasks.list(projectId);
    }

    record TaskBody(@Size(max = 200) String title, @Size(max = 20_000) String description,
                    @Size(max = 12) List<@Size(max = 500) String> acceptanceCriteria,
                    @Pattern(regexp = "LOW|MEDIUM|HIGH|CRITICAL") String priority,
                    @DecimalMin("0") @DecimalMax("100") BigDecimal estimateDays, String assigneeId,
                    @Size(max = 50) List<String> dependsOn) {}

    @PostMapping("/api/v1/projects/{projectId}/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Task create(@PathVariable String projectId, @Valid @RequestBody TaskBody body) {
        String ws = access.requireProject(projectId, WorkspaceRole.MEMBER);
        if (body.title() == null || body.title().isBlank()) throw ApiException.badRequest("title_required", "A title is required");
        Actor actor = Actor.human(access.require(ws, WorkspaceRole.MEMBER));
        Task task = tasks.create(ws, projectId, draft(body), "MANUAL", null, actor);
        if (body.dependsOn() != null && !body.dependsOn().isEmpty()) tasks.addDependencies(task.id(), body.dependsOn(), actor);
        else tasks.settle(task.id(), actor);
        return tasks.find(task.id()).orElseThrow();
    }

    @GetMapping("/api/v1/tasks/{taskId}")
    Map<String, Object> task(@PathVariable String taskId) {
        Task t = loadTask(taskId, WorkspaceRole.VIEWER);
        return Map.of("task", t, "threadId", collaboration.threadOf("TASK", taskId).orElse(""));
    }

    @PutMapping("/api/v1/tasks/{taskId}")
    Task update(@PathVariable String taskId, @Valid @RequestBody TaskBody body) {
        Task t = loadTask(taskId, WorkspaceRole.MEMBER);
        return tasks.update(taskId, draft(body), Actor.human(user(t.projectId())));
    }

    record Assign(String agentId) {}

    @PutMapping("/api/v1/tasks/{taskId}/assignee")
    Task assign(@PathVariable String taskId, @RequestBody Assign body) {
        Task t = loadTask(taskId, WorkspaceRole.MEMBER);
        return tasks.assign(taskId, body.agentId() == null || body.agentId().isBlank() ? null : body.agentId(),
                Actor.human(user(t.projectId())));
    }

    record Dependencies(@NotNull @Size(min = 1, max = 50) List<String> dependsOn) {}

    @PostMapping("/api/v1/tasks/{taskId}/dependencies")
    Task addDependencies(@PathVariable String taskId, @Valid @RequestBody Dependencies body) {
        Task t = loadTask(taskId, WorkspaceRole.MEMBER);
        tasks.addDependencies(taskId, body.dependsOn(), Actor.human(user(t.projectId())));
        return tasks.find(taskId).orElseThrow();
    }

    @DeleteMapping("/api/v1/tasks/{taskId}/dependencies/{dependsOn}")
    Task removeDependency(@PathVariable String taskId, @PathVariable String dependsOn) {
        Task t = loadTask(taskId, WorkspaceRole.MEMBER);
        return tasks.removeDependency(taskId, dependsOn, Actor.human(user(t.projectId())));
    }

    record Block(@NotBlank @Size(max = 2_000) String reason) {}

    @PostMapping("/api/v1/tasks/{taskId}/block")
    Task block(@PathVariable String taskId, @Valid @RequestBody Block body) {
        Task t = loadTask(taskId, WorkspaceRole.MEMBER);
        return tasks.block(taskId, body.reason().trim(), Actor.human(user(t.projectId())));
    }

    @PostMapping("/api/v1/tasks/{taskId}/unblock")
    Task unblock(@PathVariable String taskId) {
        Task t = loadTask(taskId, WorkspaceRole.MEMBER);
        Actor actor = Actor.human(user(t.projectId()));
        // A task blocked during its run resumes the run: the agent continues where it stopped.
        if (execution.hasBlockedRun(taskId)) {
            execution.resume(taskId, actor);
            return tasks.find(taskId).orElseThrow();
        }
        return tasks.unblock(taskId, actor);
    }

    @PostMapping("/api/v1/tasks/{taskId}/cancel")
    Task cancel(@PathVariable String taskId) {
        Task t = loadTask(taskId, WorkspaceRole.MEMBER);
        Task cancelled = tasks.cancel(taskId, Actor.human(user(t.projectId())));
        execution.onCancelled(taskId);
        return cancelled;
    }

    /** Turns a meeting action item into a task, assigned to its owner. */
    @PostMapping("/api/v1/meetings/{meetingId}/action-items/{index}/task")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Task actionItemToTask(@PathVariable String meetingId, @PathVariable int index) {
        Meeting m = meetings.find(meetingId).orElseThrow(() -> ApiException.notFound("Meeting"));
        String ws = access.requireProject(m.projectId(), WorkspaceRole.MEMBER);
        Actor actor = Actor.human(access.require(ws, WorkspaceRole.MEMBER));
        if (m.minutes() == null || !(m.minutes().get("actionItems") instanceof List<?> items) || index < 0 || index >= items.size()) {
            throw ApiException.notFound("Action item");
        }
        Map<?, ?> item = (Map<?, ?>) items.get(index);
        if (item.get("taskId") != null) throw ApiException.conflict("already_converted", "This action item is already a task");
        String owner = (String) item.get("ownerAgentId");
        String due = (String) item.get("due");
        Task task = tasks.create(ws, m.projectId(), new TaskService.Draft((String) item.get("title"),
                "From the meeting \"" + m.title() + "\"" + (due == null ? "." : ". Due: " + due + "."), List.of(),
                "MEDIUM", null, owner), "MEETING", meetingId, actor);
        tasks.settle(task.id(), actor);
        meetings.linkActionItem(meetingId, index, task.id());
        return tasks.find(task.id()).orElseThrow();
    }

    private static TaskService.Draft draft(TaskBody b) {
        return new TaskService.Draft(b.title(), b.description(), b.acceptanceCriteria(), b.priority(), b.estimateDays(),
                b.assigneeId() == null || b.assigneeId().isBlank() ? null : b.assigneeId());
    }

    private Briefing loadBriefing(String id, WorkspaceRole role) {
        Briefing b = lead.briefing(id).orElseThrow(() -> ApiException.notFound("Briefing"));
        access.requireProject(b.projectId(), role);
        return b;
    }

    private Task loadTask(String id, WorkspaceRole role) {
        Task t = tasks.find(id).orElseThrow(() -> ApiException.notFound("Task"));
        access.requireProject(t.projectId(), role);
        return t;
    }

    private String user(String projectId) {
        return access.require(access.workspaceOfProject(projectId), WorkspaceRole.MEMBER);
    }
}
