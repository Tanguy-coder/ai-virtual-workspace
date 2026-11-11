package dev.aiw.orchestration;

import dev.aiw.access.WorkspaceAccess;
import dev.aiw.access.WorkspaceRole;
import dev.aiw.governance.AuditLog;
import dev.aiw.organization.Agent;
import dev.aiw.organization.AgentService;
import dev.aiw.organization.LeadCommands;
import dev.aiw.scm.GitService;
import dev.aiw.shared.Actor;
import dev.aiw.shared.ApiException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The orders the Lead carries out from its chat with the CTO (CTO requirement, 2026-11-11). Each order goes through
 * the same services as the CTO's buttons, with the CTO's rights, and is audited. The model only chooses the order;
 * the platform runs it and reports the real outcome.
 */
@Component
class LeadChatCommands implements LeadCommands {
    private static final Logger log = LoggerFactory.getLogger(LeadChatCommands.class);
    private static final int STATE_LINES = 40;
    private static final Map<String, Integer> ORDER = Map.of("BLOCKED", 0, "IN_PROGRESS", 1, "IN_TEST", 2, "IN_REVIEW", 3,
            "VALIDATION", 4, "ASSIGNED", 5, "READY", 6, "CREATED", 7);

    private final TaskService tasks;
    private final TaskRuns runs;
    private final TaskExecution execution;
    private final TaskSplitter splitter;
    private final RealignmentService realignment;
    private final AgentService agents;
    private final WorkspaceAccess access;
    private final AuditLog audit;
    private final TransactionTemplate tx;
    private final BranchFlow branches;
    private final GitService git;
    private final JdbcClient jdbc;

    LeadChatCommands(TaskService tasks, TaskRuns runs, TaskExecution execution, TaskSplitter splitter,
                     RealignmentService realignment, AgentService agents, WorkspaceAccess access, AuditLog audit,
                     TransactionTemplate tx, BranchFlow branches, GitService git, JdbcClient jdbc) {
        this.branches = branches;
        this.git = git;
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.runs = runs;
        this.execution = execution;
        this.splitter = splitter;
        this.realignment = realignment;
        this.agents = agents;
        this.access = access;
        this.audit = audit;
        this.tx = tx;
    }

    @Override
    public String manual() {
        return """
                <orders_manual>
                In this conversation you are not limited to talking: you carry out the CTO's orders on the platform \
                (this replaces any earlier statement that you can only converse). To act, end your reply with one block:
                <orders>[{"do": "...", ...}]</orders>
                Orders (task = its number, 3 for T-3; assignee = exact name of a team member):
                - {"do":"create_task","title":"...","description":"...","criteria":["..."],"assignee":"Name","priority":"MEDIUM","dependsOn":[2]}
                - {"do":"update_task","task":3,"title":"...","description":"...","criteria":["..."]}  rewrites an open task \
                (give only the fields that change); the agent works from the task as written, so change scope here, not by message
                - {"do":"assign","task":3,"assignee":"Name"}  an assigned task starts by itself when its agent is free
                - {"do":"start","task":3}
                - {"do":"pause","task":3}
                - {"do":"resume","task":3}  also unblocks a blocked task
                - {"do":"message","task":3,"text":"instruction read by the agent at its next step"}
                - {"do":"budget","task":3,"tokens":400000}  token budget of the task's current work
                - {"do":"split","task":3,"guidance":"..."}  you split the task into smaller ones (its work must be paused or blocked)
                - {"do":"cancel","task":3}
                - {"do":"realign","concern":"..."}  you organize a realignment meeting with the people concerned
                - {"do":"merge_main","note":"what develop brings"}  you ask the CTO to approve merging develop into main
                Branches: agents branch from develop; you review their merge requests and your approval merges them into \
                develop. Only the CTO approves a merge into main, through merge_main (also requested by the platform \
                when every task is merged).
                Rules:
                - Give an order only when the CTO's latest message asks for it or approves what you proposed. Otherwise propose.
                - The platform runs the orders after your reply and shows the CTO the real outcome: never write that it is done.
                - Rely only on <project_state>: nothing else needs opening or approving. Never invent a step, an approval or a blocker.
                - At most 10 orders per reply.
                </orders_manual>""";
    }

    @Override
    public String state(String projectId) {
        List<Agent> team = agents.list(projectId).stream().filter(a -> !"DISMISSED".equals(a.status())).toList();
        Map<String, Agent> byId = team.stream().collect(Collectors.toMap(Agent::id, a -> a));
        List<Task> all = tasks.list(projectId);
        StringBuilder sb = new StringBuilder();
        if (all.isEmpty()) {
            sb.append("No task yet: the plan is written after the kickoff meeting, or create tasks with create_task.\n");
        } else {
            sb.append("Tasks by status: ").append(tasks.countByStatus(projectId).entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .map(e -> e.getKey().toLowerCase() + " " + e.getValue()).collect(Collectors.joining(", "))).append("\n");
        }
        List<Task> open = all.stream().filter(t -> !TaskService.CLOSED.contains(t.status()))
                .sorted(Comparator.comparingInt((Task t) -> ORDER.getOrDefault(t.status(), 9)).thenComparingInt(Task::number)).toList();
        Map<String, Integer> numbers = all.stream().collect(Collectors.toMap(Task::id, Task::number));
        for (Task t : open.subList(0, Math.min(open.size(), STATE_LINES))) {
            Agent a = t.assigneeId() == null ? null : byId.get(t.assigneeId());
            sb.append("- T-").append(t.number()).append(" ").append(t.title()).append(" · ").append(t.status().toLowerCase())
                    .append(" · ").append(a == null ? "unassigned" : a.name());
            if (!t.dependsOn().isEmpty()) {
                sb.append(" · after ").append(t.dependsOn().stream().map(id -> "T-" + numbers.getOrDefault(id, 0)).collect(Collectors.joining(",")));
            }
            runs.openForTask(t.id()).ifPresent(r -> sb.append(" · work ").append(r.status().toLowerCase()).append(" ")
                    .append(r.tokens() / 1000).append("k/").append(r.tokenBudget() / 1000).append("k tokens"));
            if (t.blockedReason() != null) sb.append(" · blocked: ").append(AgentTools.clip(t.blockedReason(), 200));
            sb.append("\n");
        }
        if (open.size() > STATE_LINES) sb.append("(").append(open.size() - STATE_LINES).append(" more open tasks)\n");
        // Closed tasks too, so the Lead never takes a cancelled or done task for one still standing.
        List<Task> closed = all.stream().filter(t -> TaskService.CLOSED.contains(t.status())).sorted(Comparator.comparingInt(Task::number)).toList();
        if (!closed.isEmpty()) {
            sb.append("Closed (no longer to do): ").append(closed.stream().map(t -> "T-" + t.number() + " " + t.status().toLowerCase())
                    .collect(Collectors.joining(", "))).append("\n");
        }
        git.repo(projectId).filter(r -> git.hasBranch(r, GitService.DEVELOP)).ifPresent(r -> {
            sb.append("Branches: develop is ").append(git.ahead(r, GitService.MAIN, GitService.DEVELOP)).append(" commit(s) ahead of main");
            boolean pending = jdbc.sql("SELECT count(*) FROM approvals WHERE project_id = ? AND action = ? AND status = 'PENDING'")
                    .params(projectId, BranchFlow.MERGE_MAIN).query(Long.class).single() > 0;
            sb.append(pending ? " · merge into main waiting for the CTO\n" : "\n");
        });
        sb.append("Team:\n");
        team.forEach(a -> sb.append("- ").append(a.name()).append(": ").append(a.title()).append(" · ")
                .append(a.status().equals("ACTIVE") ? a.activity().toLowerCase() : a.status().toLowerCase())
                .append(a.model() == null ? " · no model" : "").append("\n"));
        return sb.toString();
    }

    @Override
    public Outcome execute(String projectId, String userId, String leadId, JsonNode order) {
        String action = LeadService.text(order, "do");
        Outcome outcome;
        try {
            String ws = access.workspaceOfProject(projectId);
            if (access.roleOf(ws, userId).filter(r -> r.atLeast(WorkspaceRole.MEMBER)).isEmpty()) {
                return new Outcome(action, false, "You no longer have the rights to give this order");
            }
            outcome = new Outcome(action, true, run(ws, projectId, userId, action, order));
        } catch (ApiException e) {
            outcome = new Outcome(action, false, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Lead order {} failed in project {}", action, projectId, e);
            outcome = new Outcome(action, false, "The platform could not carry it out");
        }
        Outcome done = outcome;
        try {
            tx.executeWithoutResult(s -> audit.record(access.workspaceOfProject(projectId), Actor.human(userId), "lead.order",
                    "project", projectId, done.ok() ? AuditLog.Decision.ALLOWED : AuditLog.Decision.DENIED,
                    Map.of("do", action, "via", leadId, "detail", AgentTools.clip(done.detail(), 300))));
        } catch (RuntimeException e) {
            log.warn("Could not audit Lead order {}", action, e);
        }
        return done;
    }

    private String run(String ws, String projectId, String userId, String action, JsonNode o) {
        Actor cto = Actor.human(userId);
        return switch (action) {
            case "create_task" -> {
                String title = LeadService.text(o, "title");
                if (title.isBlank()) throw ApiException.badRequest("title_required", "A task needs a title");
                String priority = LeadService.text(o, "priority");
                Agent assignee = LeadService.text(o, "assignee").isBlank() ? null : member(projectId, LeadService.text(o, "assignee"));
                List<String> deps = new ArrayList<>();
                for (JsonNode n : o.path("dependsOn")) deps.add(task(projectId, n).id());
                Task created = tx.execute(s -> {
                    Task t = tasks.create(ws, projectId, new TaskService.Draft(clip(title, 200), LeadService.text(o, "description"),
                            LeadService.strings(o.path("criteria")).stream().limit(12).toList(),
                            Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(priority) ? priority : "MEDIUM", null,
                            assignee == null ? null : assignee.id()), "MANUAL", null, cto);
                    if (!deps.isEmpty()) tasks.addDependencies(t.id(), deps, cto);
                    else tasks.settle(t.id(), cto);
                    return t;
                });
                yield "T-" + created.number() + " created" + (assignee == null ? "" : ", assigned to " + assignee.name());
            }
            case "update_task" -> {
                Task t = task(projectId, o.path("task"));
                String title = LeadService.text(o, "title");
                String description = LeadService.text(o, "description");
                List<String> criteria = o.has("criteria") ? LeadService.strings(o.path("criteria")).stream().limit(12).toList() : null;
                if (title.isBlank() && description.isBlank() && criteria == null) {
                    throw ApiException.badRequest("nothing_to_update", "Give the title, description or criteria to change");
                }
                tasks.update(t.id(), new TaskService.Draft(title.isBlank() ? null : clip(title, 200), description.isBlank() ? null : description,
                        criteria, null, null, null), cto);
                runs.openForTask(t.id()).ifPresent(r -> execution.message(t.id(), userId,
                        "The task was rewritten by the Lead on the CTO's order. Work from the new version:\n" + t.number() + ". "
                                + (title.isBlank() ? t.title() : title) + (description.isBlank() ? "" : "\n" + clip(description, 4_000))
                                + (criteria == null ? "" : "\nAcceptance criteria:\n- " + String.join("\n- ", criteria))));
                yield "T-" + t.number() + " rewritten" + (runs.openForTask(t.id()).isPresent() ? "; its agent is told" : "");
            }
            case "assign" -> {
                Task t = task(projectId, o.path("task"));
                Agent a = member(projectId, LeadService.text(o, "assignee"));
                tasks.assign(t.id(), a.id(), cto);
                yield "T-" + t.number() + " assigned to " + a.name();
            }
            case "start" -> {
                Task t = task(projectId, o.path("task"));
                execution.start(t.id(), cto);
                yield "Work on T-" + t.number() + " started";
            }
            case "pause" -> {
                Task t = task(projectId, o.path("task"));
                execution.pause(t.id(), userId);
                yield "T-" + t.number() + " pauses after its current step";
            }
            case "resume" -> {
                Task t = task(projectId, o.path("task"));
                if (runs.openForTask(t.id()).isPresent()) {
                    execution.resume(t.id(), cto);
                    yield "Work on T-" + t.number() + " resumed";
                }
                if (!"BLOCKED".equals(t.status())) throw ApiException.conflict("not_paused", "T-" + t.number() + " is neither paused nor blocked");
                tasks.unblock(t.id(), cto);
                yield "T-" + t.number() + " unblocked";
            }
            case "message" -> {
                Task t = task(projectId, o.path("task"));
                String text = LeadService.text(o, "text");
                if (text.isBlank()) throw ApiException.badRequest("text_required", "The message is empty");
                execution.message(t.id(), userId, clip(text, 10_000));
                yield "Message delivered to the work on T-" + t.number();
            }
            case "budget" -> {
                Task t = task(projectId, o.path("task"));
                long tokens = o.path("tokens").asLong(0);
                if (tokens < 10_000 || tokens > 50_000_000) throw ApiException.badRequest("budget_invalid", "A budget is between 10,000 and 50,000,000 tokens");
                TaskRun r = execution.setBudget(t.id(), userId, tokens);
                yield "Budget of T-" + t.number() + " set to " + tokens + " tokens (work " + r.status().toLowerCase() + ")";
            }
            case "split" -> {
                Task t = task(projectId, o.path("task"));
                splitter.request(t.id(), userId, clip(LeadService.text(o, "guidance"), 5_000));
                yield "Split of T-" + t.number() + " queued";
            }
            case "cancel" -> {
                Task t = task(projectId, o.path("task"));
                tasks.cancel(t.id(), cto);
                execution.onCancelled(t.id());
                yield "T-" + t.number() + " cancelled";
            }
            case "realign" -> {
                String concern = LeadService.text(o, "concern");
                if (concern.isBlank()) throw ApiException.badRequest("concern_required", "Say what the meeting must realign");
                realignment.delegate(ws, projectId, userId, clip(concern, 5_000));
                yield "Realignment meeting being prepared";
            }
            case "merge_main" -> {
                BranchFlow.MainMerge m = branches.requestMainMerge(ws, projectId, LeadService.text(o, "note"));
                if (!m.requested()) throw ApiException.conflict("merge_main_refused", m.detail());
                yield m.detail();
            }
            default -> throw ApiException.badRequest("unknown_order", "Unknown order \"" + action + "\"");
        };
    }

    private Task task(String projectId, JsonNode ref) {
        String given = ref.isNumber() ? String.valueOf(ref.asInt()) : ref.isString() ? ref.asString().strip() : "";
        String raw = given.toUpperCase().replaceFirst("^T-?", "");
        int number;
        try {
            number = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("task_unknown", "No task \"" + given + "\"");
        }
        return tasks.list(projectId).stream().filter(t -> t.number() == number).findFirst()
                .orElseThrow(() -> ApiException.badRequest("task_unknown", "No task T-" + number + " in this project"));
    }

    private Agent member(String projectId, String name) {
        Optional<Agent> found = agents.list(projectId).stream()
                .filter(a -> "ACTIVE".equals(a.status()) && a.name().equalsIgnoreCase(name.strip())).findFirst();
        return found.orElseThrow(() -> ApiException.badRequest("agent_unknown", "No active team member named \"" + name + "\""));
    }

    private static String clip(String s, int max) {
        return s == null ? "" : s.length() > max ? s.substring(0, max) : s;
    }
}
