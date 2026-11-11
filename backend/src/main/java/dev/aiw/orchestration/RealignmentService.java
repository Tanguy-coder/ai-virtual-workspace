package dev.aiw.orchestration;

import dev.aiw.agentruntime.AgentJob;
import dev.aiw.agentruntime.AgentJobs;
import dev.aiw.agentruntime.AgentRunner;
import dev.aiw.agentruntime.JobHandler;
import dev.aiw.collaboration.Meeting;
import dev.aiw.collaboration.MeetingEnded;
import dev.aiw.collaboration.MeetingService;
import dev.aiw.collaboration.ParticipantStatus;
import dev.aiw.events.EventPublisher;
import dev.aiw.events.Subject;
import dev.aiw.governance.AuditLog;
import dev.aiw.organization.Agent;
import dev.aiw.organization.AgentService;
import dev.aiw.shared.Actor;
import dev.aiw.shared.ApiException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Realignment meetings mid-project (CTO requirement, 2026-11-10). The CTO convenes one, or orders the Lead to organize
 * it: the Lead reads the project state recorded by the platform and writes the agenda and the list of participants.
 * During the meeting the participants' running work is paused; each one is given their own recorded work status so
 * they expose real problems, and the others propose solutions. When it ends, agreed solutions reach the tasks they
 * concern (a run the agent had blocked resumes with it), the decisions reach the paused runs, and the work resumes.
 */
@Service
public class RealignmentService implements ParticipantStatus {
    static final String PREPARE = "LEAD_REALIGN";
    private static final int MAX_PARTICIPANTS = 12;
    private static final int STATE_LINES = 40;
    private static final Map<String, Integer> ORDER = Map.of("BLOCKED", 0, "IN_PROGRESS", 1, "IN_TEST", 2, "IN_REVIEW", 3,
            "VALIDATION", 4, "ASSIGNED", 5, "READY", 6, "CREATED", 7);

    private final AgentService agents;
    private final AgentJobs jobs;
    private final AgentRunner runner;
    private final MeetingService meetings;
    private final TaskService tasks;
    private final TaskExecution execution;
    private final EventPublisher events;
    private final AuditLog audit;
    private final TransactionTemplate tx;

    RealignmentService(AgentService agents, AgentJobs jobs, AgentRunner runner, MeetingService meetings, TaskService tasks,
                       TaskExecution execution, EventPublisher events, AuditLog audit, TransactionTemplate tx) {
        this.agents = agents;
        this.jobs = jobs;
        this.runner = runner;
        this.meetings = meetings;
        this.tasks = tasks;
        this.execution = execution;
        this.events = events;
        this.audit = audit;
        this.tx = tx;
    }

    @Override
    public String of(String agentId) {
        return execution.workStatus(agentId);
    }

    /** The CTO orders the Lead to organize the meeting. */
    @Transactional
    public void delegate(String workspaceId, String projectId, String userId, String concern) {
        requireNoneOpen(projectId);
        Agent lead = agents.lead(projectId).filter(l -> "ACTIVE".equals(l.status()) && l.model() != null)
                .orElseThrow(() -> ApiException.conflict("no_lead", "The project has no active Lead with a model"));
        Actor actor = Actor.human(userId);
        jobs.enqueue(workspaceId, projectId, lead.id(), PREPARE, Map.of("concern", concern), projectId);
        events.publish(workspaceId, projectId, "realignment.requested", actor, new Subject("agent", lead.id()),
                Map.of("leadId", lead.id(), "concern", concern.length() > 300 ? concern.substring(0, 300) + "…" : concern));
        audit.record(workspaceId, actor, "realignment.delegate", "project", projectId, AuditLog.Decision.RECORDED, Map.of());
    }

    /** Opens a realignment meeting and pauses the participants' running work until it ends. */
    @Transactional
    public Meeting convene(String workspaceId, String projectId, String title, String agenda, String organizerId,
                           List<String> participantIds, int rounds, Actor convener) {
        requireNoneOpen(projectId);
        Meeting m = meetings.convene(workspaceId, projectId, MeetingService.REALIGNMENT, title, agenda, organizerId,
                participantIds, rounds, convener);
        for (String id : m.participantIds()) execution.pauseForMeeting(id, m.id());
        return m;
    }

    private void requireNoneOpen(String projectId) {
        meetings.openRealignment(projectId).ifPresent(m -> {
            throw ApiException.conflict("realignment_open", "A realignment meeting is already in progress: " + m.title());
        });
    }

    // ---- the Lead prepares the meeting ----

    void prepare(AgentJob job) {
        if (meetings.openRealignment(job.projectId()).isPresent()) return;
        Agent lead = runner.agent(job);
        String concern = job.string("concern");
        List<Agent> team = agents.list(job.projectId()).stream()
                .filter(a -> "ACTIVE".equals(a.status()) && !a.lead() && a.model() != null).toList();
        Map<String, Agent> byName = team.stream().collect(Collectors.toMap(a -> a.name().toLowerCase(), Function.identity(), (a, b) -> a));
        Map<String, Agent> byId = team.stream().collect(Collectors.toMap(Agent::id, Function.identity()));

        List<Task> open = tasks.list(job.projectId()).stream().filter(t -> !TaskService.CLOSED.contains(t.status()))
                .sorted(Comparator.comparingInt((Task t) -> ORDER.getOrDefault(t.status(), 9)).thenComparingInt(Task::number)).toList();
        Set<String> blockedAssignees = new LinkedHashSet<>();
        StringBuilder state = new StringBuilder();
        Map<String, Long> counts = tasks.countByStatus(job.projectId());
        state.append("Tasks by status: ").append(counts.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey().toLowerCase() + " " + e.getValue()).collect(Collectors.joining(", "))).append("\n");
        for (Task t : open.subList(0, Math.min(open.size(), STATE_LINES))) {
            Agent a = t.assigneeId() == null ? null : byId.get(t.assigneeId());
            state.append("- T-").append(t.number()).append(" ").append(t.title()).append(" · ").append(t.status().toLowerCase())
                    .append(" · ").append(a == null ? "unassigned" : a.name());
            if (t.blockedReason() != null) state.append(" · blocked: ").append(AgentTools.clip(t.blockedReason(), 200));
            state.append("\n");
            if ("BLOCKED".equals(t.status()) && a != null) blockedAssignees.add(a.id());
        }
        if (open.size() > STATE_LINES) state.append("(").append(open.size() - STATE_LINES).append(" more open tasks)\n");
        StringBuilder people = new StringBuilder();
        team.forEach(a -> people.append("- ").append(a.name()).append(": ").append(a.title()).append(" · ")
                .append(a.activity().toLowerCase()).append("\n"));

        String prompt = """
                The CTO orders you to organize a realignment meeting now, in the middle of the project.

                <cto_concern>
                %s
                </cto_concern>

                <project_state recorded_by="platform">
                %s</project_state>

                <team>
                %s</team>

                Choose the participants concerned by the concern and the state above (anyone with a blocked task attends \
                anyway) and write a specific agenda: 1) the CTO's concern, 2) each participant's status and problems, \
                3) solutions, 4) decisions to realign the work. rounds: 1 if the question is simple, 2 when problems need \
                answers from others.

                JSON shape:
                {"title": "short", "agenda": "numbered points, one per line", "participants": ["exact name"], "rounds": 2}"""
                .formatted(concern, state, people);
        JsonNode out = runner.completeJson(job, lead, runner.context(lead, concern, 2_000, 2_000).oneShot(), prompt, 2_000,
                "medium", "Realignment meeting agenda", node -> {
                    if (LeadService.text(node, "title").isBlank() || LeadService.text(node, "agenda").isBlank()) {
                        throw new AgentRunner.InvalidOutput("Give a title and an agenda.");
                    }
                    for (String name : LeadService.strings(node.path("participants"))) {
                        if (!byName.containsKey(name.toLowerCase())) {
                            throw new AgentRunner.InvalidOutput("Unknown participant \"" + name + "\". Team: "
                                    + team.stream().map(Agent::name).collect(Collectors.joining(", ")) + ".");
                        }
                    }
                });
        Set<String> chosen = new LinkedHashSet<>(blockedAssignees);
        LeadService.strings(out.path("participants")).forEach(n -> chosen.add(byName.get(n.toLowerCase()).id()));
        if (chosen.isEmpty()) team.forEach(a -> chosen.add(a.id()));
        List<String> ids = new ArrayList<>(chosen).subList(0, Math.min(chosen.size(), MAX_PARTICIPANTS - 1));
        int rounds = out.path("rounds").isInt() ? Math.min(Math.max(out.path("rounds").asInt(), 1), 3) : 2;
        String title = LeadService.text(out, "title");
        String agenda = "CTO's concern: " + concern.strip() + "\n\n" + LeadService.text(out, "agenda");
        tx.executeWithoutResult(s -> {
            if (meetings.openRealignment(job.projectId()).isPresent()) return;
            convene(job.workspaceId(), job.projectId(), title.length() > 200 ? title.substring(0, 200) : title, agenda,
                    lead.id(), ids, rounds, Actor.agent(lead.id()));
        });
    }

    void failed(AgentJob job, String error) {
        events.publish(job.workspaceId(), job.projectId(), "realignment.failed", Actor.agent(job.agentId()),
                new Subject("agent", job.agentId()), Map.of("error", error));
    }

    // ---- after the meeting ----

    /** Runs in the transaction that closes the meeting; nothing here throws on an ordinary state. */
    @EventListener
    void onMeetingEnded(MeetingEnded e) {
        if (!MeetingService.REALIGNMENT.equals(e.purpose())) return;
        Meeting m = meetings.find(e.meetingId()).orElseThrow();
        String note = null;
        int delivered = 0, resumedBySolution = 0;
        if (e.ended() && m.minutes() != null) {
            note = outcome(m.minutes());
            Map<Integer, Task> byNumber = tasks.list(m.projectId()).stream()
                    .collect(Collectors.toMap(Task::number, Function.identity()));
            if (m.minutes().get("problems") instanceof List<?> problems) {
                Set<String> seen = new HashSet<>();
                for (int i = 0; i < problems.size(); i++) {
                    if (!(problems.get(i) instanceof Map<?, ?> p)) continue;
                    Object solution = p.get("solution");
                    Task task = p.get("taskNumber") instanceof Number n ? byNumber.get(n.intValue()) : null;
                    if (solution == null || task == null || TaskService.CLOSED.contains(task.status())) continue;
                    String text = "Problem: " + p.get("problem") + "\nAgreed solution: " + solution;
                    String result = execution.deliverSolution(task.id(), text);
                    if (result == null) continue;
                    meetings.markProblemDelivered(m.id(), i);
                    delivered++;
                    if ("resumed".equals(result) && seen.add(task.id())) resumedBySolution++;
                }
            }
        }
        int resumed = execution.resumeAfterMeeting(m.id(), note);
        execution.restoreBlocked(m.participantIds());
        events.publish(e.workspaceId(), e.projectId(), "realignment.applied", Actor.system(), new Subject("meeting", m.id()),
                Map.of("title", m.title(), "outcome", e.outcome(), "solutionsDelivered", delivered,
                        "resumed", resumed + resumedBySolution));
    }

    /** What the paused runs are told when they resume: the summary and the decisions, kept short. */
    private static String outcome(Map<String, Object> minutes) {
        StringBuilder sb = new StringBuilder();
        if (minutes.get("summary") != null) sb.append(minutes.get("summary")).append("\n");
        if (minutes.get("decisions") instanceof List<?> decisions && !decisions.isEmpty()) {
            sb.append("Decisions:\n");
            for (Object d : decisions) {
                if (d instanceof Map<?, ?> map) sb.append("- ").append(map.get("title")).append(": ").append(map.get("detail")).append("\n");
            }
        }
        sb.append("Apply these decisions to your task where they concern it.");
        return AgentTools.clip(sb.toString(), 2_500);
    }

    @Component
    static class PrepareHandler implements JobHandler {
        private final RealignmentService realignment;

        PrepareHandler(RealignmentService realignment) {
            this.realignment = realignment;
        }

        @Override public String type() { return PREPARE; }
        @Override public void handle(AgentJob job) { realignment.prepare(job); }
        @Override public void onFailed(AgentJob job, String error) { realignment.failed(job, error); }
    }
}
