package dev.aiw.orchestration;

import dev.aiw.agentruntime.AgentJob;
import dev.aiw.agentruntime.AgentJobs;
import dev.aiw.agentruntime.AgentRunner;
import dev.aiw.agentruntime.JobHandler;
import dev.aiw.collaboration.CollaborationService;
import dev.aiw.collaboration.MeetingService;
import dev.aiw.events.EventPublisher;
import dev.aiw.events.Subject;
import dev.aiw.execution.SandboxService;
import dev.aiw.governance.ApprovalDecided;
import dev.aiw.governance.ApprovalService;
import dev.aiw.governance.AuditLog;
import dev.aiw.memory.MemoryService;
import dev.aiw.modelgateway.BudgetService;
import dev.aiw.organization.Agent;
import dev.aiw.organization.AgentService;
import dev.aiw.projects.Project;
import dev.aiw.projects.ProjectService;
import dev.aiw.quality.SecretScan;
import dev.aiw.quality.TestRun;
import dev.aiw.quality.TestRunService;
import dev.aiw.scm.GitService;
import dev.aiw.scm.MergeRequest;
import dev.aiw.scm.MergeRequestService;
import dev.aiw.scm.ReviewComment;
import dev.aiw.shared.Actor;
import dev.aiw.shared.ApiException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Increment 4: agents execute their tasks (docs/10). A run is a chain of persisted steps, one agent job each:
 * the model decides a few tool calls, the platform executes them on the task's branch and in its sandbox.
 * On submission the platform checks conflicts and secrets and runs the tests itself; a teammate reviews; the
 * validation engine checks every gate; the CTO approves the merge into main. Only then is the task DONE.
 */
@Service
public class TaskExecution {
    private static final Logger log = LoggerFactory.getLogger(TaskExecution.class);
    static final String STEP = "TASK_STEP", REVIEW = "TASK_REVIEW", MERGE = "MR_MERGE";
    static final String MERGE_ACTION = "merge_request.merge";
    private static final int MAX_REVIEW_ROUNDS = 3;
    private static final int FULL_OUTPUTS = 8;

    /**
     * How much of the run a step prompt carries. NORMAL keeps everything; the lean levels are used when the provider
     * allows few tokens per minute (Groq free tier: 8 000), so that a long task still fits in one request.
     */
    record Lean(int historyEntries, int fullOutputs, int outputChars, int newsChars, int specChars, int memoryChars) {}
    static final Lean NORMAL = new Lean(Integer.MAX_VALUE, FULL_OUTPUTS, Integer.MAX_VALUE, 4_000, 4_000, 3_000);
    static final List<Lean> LEAN = List.of(new Lean(20, 3, 2_500, 2_000, 1_500, 1_000),
            new Lean(10, 2, 1_200, 1_200, 600, 400), new Lean(6, 1, 600, 800, 0, 0));
    /** Conservative characters per token when sizing a prompt against a token limit. */
    private static final int CHARS_PER_TOKEN = 3;

    private final TaskService tasks;
    private final TaskRuns runs;
    private final AgentTools tools;
    private final AgentRunner runner;
    private final AgentJobs jobs;
    private final AgentService agents;
    private final CollaborationService collaboration;
    private final MemoryService memory;
    private final GitService git;
    private final SandboxService sandboxes;
    private final TestRunService tests;
    private final MergeRequestService mrs;
    private final ApprovalService approvals;
    private final ProjectService projects;
    private final EventPublisher events;
    private final AuditLog audit;
    private final TransactionTemplate tx;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final dev.aiw.modelgateway.ModelGateway gateway;
    private final boolean autoStart;
    private final int maxSegmentSteps;
    private final long tokenBudget;
    private final BudgetService budgets;
    private final MeetingService meetings;

    TaskExecution(TaskService tasks, TaskRuns runs, AgentTools tools, AgentRunner runner, AgentJobs jobs, AgentService agents,
                  CollaborationService collaboration, MemoryService memory, GitService git, SandboxService sandboxes,
                  TestRunService tests, MergeRequestService mrs, ApprovalService approvals, ProjectService projects,
                  EventPublisher events, AuditLog audit, TransactionTemplate tx, JdbcClient jdbc, JsonMapper json,
                  BudgetService budgets, MeetingService meetings, dev.aiw.modelgateway.ModelGateway gateway,
                  @Value("${aiw.execution.auto-start:true}") boolean autoStart,
                  @Value("${aiw.execution.max-steps-per-segment:40}") int maxSegmentSteps,
                  @Value("${aiw.execution.token-budget:200000}") long tokenBudget) {
        this.tasks = tasks;
        this.runs = runs;
        this.tools = tools;
        this.runner = runner;
        this.jobs = jobs;
        this.agents = agents;
        this.collaboration = collaboration;
        this.memory = memory;
        this.git = git;
        this.sandboxes = sandboxes;
        this.tests = tests;
        this.mrs = mrs;
        this.approvals = approvals;
        this.projects = projects;
        this.events = events;
        this.audit = audit;
        this.tx = tx;
        this.jdbc = jdbc;
        this.json = json;
        this.gateway = gateway;
        this.autoStart = autoStart;
        this.maxSegmentSteps = maxSegmentSteps;
        this.tokenBudget = tokenBudget;
        this.budgets = budgets;
        this.meetings = meetings;
    }

    /** Tokens given to each new run of the project: the project's setting, else the platform default. */
    public long taskTokenBudget(String projectId) {
        Long own = budgets.get(projectId).taskTokenBudget();
        return own == null ? tokenBudget : own;
    }

    // ================================================================== start

    /** Starts work on assigned tasks whose agent is free (one task at a time per agent). */
    @Scheduled(fixedDelayString = "${aiw.execution.sweep-ms:10000}", initialDelayString = "${aiw.execution.sweep-delay-ms:5000}")
    void sweep() {
        if (!autoStart || !sandboxes.enabled()) return;
        List<String> candidates = jdbc.sql("""
                SELECT DISTINCT ON (t.assignee_id) t.id FROM tasks t JOIN agents a ON a.id = t.assignee_id
                WHERE t.status = 'ASSIGNED' AND a.status = 'ACTIVE' AND a.provider_id IS NOT NULL AND a.model IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM task_runs r WHERE r.task_id = t.id AND r.status IN ('RUNNING', 'PAUSED', 'SUBMITTED', 'BLOCKED'))
                  AND NOT EXISTS (SELECT 1 FROM task_runs r WHERE r.agent_id = a.id AND r.status IN ('RUNNING', 'PAUSED'))
                  AND NOT EXISTS (SELECT 1 FROM meeting_participants p JOIN meetings m ON m.id = p.meeting_id
                                  WHERE p.agent_id = a.id AND m.status IN ('IN_PROGRESS', 'SYNTHESIZING'))
                ORDER BY t.assignee_id, CASE t.priority WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END, t.number
                """).query(String.class).list();
        for (String taskId : candidates) {
            try {
                start(taskId, Actor.system());
            } catch (RuntimeException e) {
                log.warn("Could not start task {}", taskId, e);
            }
        }
    }

    /** Opens a run: branch from main, worktree, first step queued. The sandbox starts with the first step. */
    public TaskRun start(String taskId, Actor actor) {
        Task task = tasks.find(taskId).orElseThrow(() -> ApiException.notFound("Task"));
        if (!"ASSIGNED".equals(task.status())) {
            throw ApiException.conflict("not_assigned", "Only an assigned task can start (it is " + task.status().toLowerCase() + ")");
        }
        Agent agent = agents.find(task.assigneeId()).orElseThrow();
        if (runs.agentBusy(agent.id())) throw ApiException.conflict("agent_busy", agent.name() + " is already working on a task");
        Optional<TaskRun> open = runs.openForTask(taskId);
        if (open.isPresent()) return open.get();
        Project project = projects.find(task.projectId()).orElseThrow();
        String ws = tasks.workspaceOf(task);
        Path repo = git.ensureRepo(ws, project.id(), project.name(), project.description());
        // A task split from a bigger one may continue the work already committed on the original branch.
        String base = jdbc.sql("SELECT base_branch FROM tasks WHERE id = ?").param(taskId).query(String.class).optional().orElse(null);
        String branch = base != null && git.hasBranch(repo, base) ? base
                : "agent/" + slug(agent.name(), 30) + "/t-" + task.number() + "-" + slug(task.title(), 32);
        long budget = taskTokenBudget(project.id());
        Path worktree = git.worktree(repo, project.id(), branch);
        String image = SandboxService.imageFor(worktree);
        return tx.execute(s -> {
            TaskRun run = runs.create(ws, project.id(), taskId, agent.id(), branch, worktree.toString(), image, budget);
            tasks.advance(taskId, "IN_PROGRESS", actor, Map.of("runId", run.id(), "branch", branch));
            agents.setResting(agent.id(), "WORKING", null);
            runs.step(run, "PLATFORM", null, (branch.equals(base) ? "Continues branch " + branch : "Branch " + branch + " created from main")
                            + " · sandbox " + image + " · budget " + budget + " tokens",
                    Map.of("branch", branch, "image", image), "OK", 0, 0, 0);
            events.publish(ws, project.id(), "task_run.started", actor, new Subject("task_run", run.id()),
                    Map.of("taskId", taskId, "agentId", agent.id(), "branch", branch, "number", task.number()));
            jobs.enqueue(ws, project.id(), agent.id(), STEP, Map.of("runId", run.id()), run.id());
            return run;
        });
    }

    // ================================================================== step

    void step(AgentJob job) {
        TaskRun run = runs.find(job.string("runId")).orElseThrow();
        if (!"RUNNING".equals(run.status())) return;
        Task task = tasks.find(run.taskId()).orElseThrow();
        Agent agent = runner.agent(job);
        if (run.pauseRequested()) {
            tx.executeWithoutResult(s -> {
                String meeting = pausedForMeeting(run.id());
                runs.status(run, "PAUSED", null);
                runs.step(run, "PLATFORM", null, meeting != null ? "Paused for a realignment meeting" : "Paused by the CTO",
                        Map.of(), "OK", 0, 0, 0);
                agents.setResting(agent.id(), meetings.inOpenMeeting(agent.id()) ? "IN_MEETING" : "AVAILABLE", null);
            });
            return;
        }
        if (run.tokens() >= run.tokenBudget()) {
            block(run, task, agent, "Token budget reached (" + run.tokens() + " of " + run.tokenBudget() + ")");
            return;
        }
        if (run.segmentSteps() >= maxSegmentSteps) {
            block(run, task, agent, "Step limit reached (" + maxSegmentSteps + " steps without submitting)");
            return;
        }
        String sandbox = ensureSandbox(run);
        AgentTools.Workspace ws = new AgentTools.Workspace(Path.of(run.worktree()), sandbox, author(agent));

        String query = task.title() + " " + task.description();
        long perMinute = gateway.tokensPerMinute(agent.providerId(), agent.model()).orElse(0L);
        long maxOut = perMinute > 0 ? Math.min(8_000, Math.max(800, perMinute / 5)) : 8_000;
        AgentRunner.Context ctx = null;
        String text = null;
        if (perMinute <= 0) {
            ctx = runner.context(agent, query, NORMAL.specChars(), NORMAL.memoryChars()).withStable(AgentTools.MANUAL);
            text = prompt(run, task, agent, NORMAL);
        } else {
            // The whole request must fit in the provider's per-minute allowance: take the first level that fits.
            long budget = (perMinute - maxOut - 400) * CHARS_PER_TOKEN;
            for (Lean lean : LEAN) {
                ctx = runner.context(agent, query, lean.specChars(), lean.memoryChars()).withStable(AgentTools.MANUAL);
                text = prompt(run, task, agent, lean);
                long size = ctx.stable().length() + (ctx.dynamic() == null ? 0 : ctx.dynamic().length()) + text.length();
                if (size <= budget) break;
            }
        }
        AgentRunner.Context context = ctx;
        String prompt = text;
        long outTokens = maxOut;
        AgentRunner.Usage usage = new AgentRunner.Usage();
        long t0 = System.nanoTime();
        JsonNode decision = runner.measure(usage, () -> runner.completeJson(job, agent, context, prompt, outTokens, "medium",
                "T-" + task.number() + " · step " + (run.steps() + 1), this::validateDecision));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        tx.executeWithoutResult(s -> {
            runs.usage(run.id(), usage.input, usage.output, usage.cacheRead, usage.cost);
            runs.step(run, "MODEL", null, decision.path("thought").asString("(no thought)"),
                    Map.of("actions", decision.path("actions").size(), "cacheRead", usage.cacheRead), "OK",
                    usage.input, usage.output, ms);
        });

        AgentTools.End end = AgentTools.End.NONE;
        String endText = null;
        for (JsonNode action : decision.path("actions")) {
            String tool = action.path("tool").asString();
            JsonNode args = action.path("args").isObject() ? action.path("args") : json.createObjectNode();
            long a0 = System.nanoTime();
            AgentTools.Result r = switch (tool) {
                case "task.submit" -> new AgentTools.Result("OK", "task.submit", "Submitted.", Map.of(), AgentTools.End.SUBMIT);
                case "task.block" -> new AgentTools.Result("OK", "task.block · " + args.path("reason").asString(""), "Blocked.", Map.of(), AgentTools.End.BLOCK);
                case "message.send" -> sendMessage(run, task, agent, args);
                case "memory.record" -> record(run, agent, args);
                default -> tools.run(ws, tool, args);
            };
            long ams = (System.nanoTime() - a0) / 1_000_000;
            Map<String, Object> detail = new HashMap<>(r.detail());
            detail.put("args", argsSummary(args));
            detail.put("out", AgentTools.clip(r.output()));
            detail.put("sig", tool + ":" + args);
            AgentTools.Result result = r;
            tx.executeWithoutResult(s -> runs.step(run, "TOOL", tool, result.summary(), detail, result.outcome(), 0, 0, ams));
            if (r.end() != AgentTools.End.NONE) {
                end = r.end();
                endText = tool.equals("task.submit") ? args.path("summary").asString("") : args.path("reason").asString("");
                break;
            }
            if (!"OK".equals(r.outcome())) break;
        }

        if (end == AgentTools.End.BLOCK) {
            block(run, task, agent, endText == null || endText.isBlank() ? "Blocked without a reason" : endText);
            return;
        }
        if (end == AgentTools.End.SUBMIT) {
            submit(run, task, agent, endText);
            return;
        }
        String loop = repeatedFailure(run.id());
        if (loop != null) {
            block(run, task, agent, "Stopped by the watchdog: the same call failed 3 times (" + loop + ")");
            return;
        }
        next(run);
    }

    private void validateDecision(JsonNode node) {
        // Models trained on native tool calling (Groq, some OpenRouter models) may answer with one call
        // {"name": "repo.tree", "arguments": {...}} instead of our format: it is read as that single action.
        if (node instanceof ObjectNode o && !o.has("actions") && o.path("name").isString()) {
            ObjectNode action = json.createObjectNode().put("tool", o.path("name").asString());
            JsonNode args = o.path("arguments");
            if (args.isString()) {
                try {
                    args = json.readTree(args.asString());
                } catch (RuntimeException e) {
                    args = json.createObjectNode();
                }
            }
            action.set("args", args.isObject() ? args : json.createObjectNode());
            o.putArray("actions").add(action);
            if (!o.has("thought")) o.put("thought", "Calls " + o.path("name").asString() + ".");
        }
        JsonNode actions = node.path("actions");
        if (!actions.isArray() || actions.isEmpty()) throw new AgentRunner.InvalidOutput("\"actions\" must list 1 to 5 actions.");
        if (actions.size() > 5) throw new AgentRunner.InvalidOutput("At most 5 actions per reply.");
        for (JsonNode a : actions) {
            String tool = a.path("tool").asString("");
            if (!AgentTools.TOOLS.contains(tool)) {
                throw new AgentRunner.InvalidOutput("Unknown tool \"" + tool + "\". Tools: " + String.join(", ", AgentTools.TOOLS.stream().sorted().toList()) + ".");
            }
        }
    }

    private void next(TaskRun run) {
        tx.executeWithoutResult(s -> jobs.enqueue(runs.workspaceOf(run), run.projectId(), run.agentId(), STEP,
                Map.of("runId", run.id()), run.id()));
    }

    private String ensureSandbox(TaskRun stale) {
        TaskRun run = runs.find(stale.id()).orElseThrow(); // the sandbox may have been (re)started earlier in this job
        String image = run.image() == null ? SandboxService.imageFor(Path.of(run.worktree())) : run.image();
        String id = sandboxes.ensure(run.id(), run.sandboxId(), Path.of(run.worktree()), image);
        if (!id.equals(run.sandboxId())) tx.executeWithoutResult(s -> runs.sandbox(run.id(), id, image));
        return id;
    }

    /** The prompt of a step: the task, the branch, the compacted history and what is new since the last decision. */
    private String prompt(TaskRun run, Task task, Agent agent, Lean lean) {
        StringBuilder sb = new StringBuilder();
        sb.append("<task number=\"T-").append(task.number()).append("\">\n").append(task.title()).append("\n");
        if (task.description() != null && !task.description().isBlank()) sb.append(task.description().strip()).append("\n");
        sb.append("Acceptance criteria (each must be proven by an automated test):\n");
        for (int i = 0; i < task.acceptanceCriteria().size(); i++) {
            sb.append(i + 1).append(". ").append(task.acceptanceCriteria().get(i).text()).append("\n");
        }
        sb.append("</task>\n");
        brief(task, agent).ifPresent(b -> sb.append("<brief from=\"Lead\">\n").append(b).append("\n</brief>\n"));
        Path wt = Path.of(run.worktree());
        sb.append("<repository branch=\"").append(run.branch()).append("\">\n");
        List<GitService.FileChange> changes = git.changes(wt, GitService.MAIN, "HEAD");
        sb.append(changes.isEmpty() ? "No committed change on your branch yet.\n"
                : "Committed on your branch: " + changes.stream().map(c -> c.status() + " " + c.path()).reduce((a, b) -> a + ", " + b).orElse("") + "\n");
        if (git.dirty(wt)) sb.append("You have uncommitted changes.\n");
        var plan = dev.aiw.quality.TestOutput.plan(wt);
        sb.append("Test command: ").append(plan == null ? "none yet (add one, see rules)" : plan.command()).append("\n</repository>\n");

        List<RunStep> steps = runs.steps(run.id());
        int lastModel = -1;
        for (int i = 0; i < steps.size(); i++) if ("MODEL".equals(steps.get(i).kind())) lastModel = i;
        List<RunStep> history = steps.subList(0, lastModel + 1 > 0 ? lastModel + 1 : 0);
        List<RunStep> fresh = steps.subList(Math.max(lastModel + 1, 0), steps.size());
        // Tool results of the latest decision are history too: they answer it.
        List<RunStep> answered = new ArrayList<>(history);
        List<RunStep> news = new ArrayList<>();
        for (RunStep st : fresh) (st.kind().equals("TOOL") ? answered : news).add(st);

        if (!answered.isEmpty()) {
            sb.append("<history>\n");
            if (answered.size() > lean.historyEntries()) {
                int dropped = answered.size() - lean.historyEntries();
                answered = new ArrayList<>(answered.subList(dropped, answered.size()));
                sb.append("(").append(dropped).append(" earlier entries left out; the repository state above sums them up)\n");
            }
            long toolCount = answered.stream().filter(st -> st.kind().equals("TOOL")).count();
            long seen = 0;
            for (RunStep st : answered) {
                switch (st.kind()) {
                    case "MODEL" -> sb.append("#").append(st.seq()).append(" you: ").append(st.summary()).append("\n");
                    case "TOOL" -> {
                        seen++;
                        sb.append("#").append(st.seq()).append(" ").append(st.summary()).append(" [").append(st.outcome()).append("]\n");
                        if (seen > toolCount - lean.fullOutputs()) {
                            Object out = st.detail().get("out");
                            if (out != null && !out.toString().isBlank()) {
                                String o = out.toString();
                                sb.append("<output>\n").append(o.length() > lean.outputChars()
                                        ? o.substring(0, lean.outputChars()) + "\n(output cut; read a smaller range if you need more)" : o)
                                        .append("\n</output>\n");
                            }
                        }
                    }
                    default -> sb.append("#").append(st.seq()).append(" ").append(st.kind().toLowerCase()).append(": ")
                            .append(st.summary()).append("\n");
                }
            }
            sb.append("</history>\n");
        }
        if (!news.isEmpty()) {
            sb.append("<new>\n");
            for (RunStep st : news) {
                sb.append("- ").append(st.summary()).append("\n");
                Object text = st.detail().get("text");
                if (text != null) sb.append(AgentTools.clip(text.toString(), lean.newsChars())).append("\n");
            }
            sb.append("</new>\n");
        }
        sb.append("Step ").append(run.steps() + 1).append(" · tokens used ").append(run.tokens()).append(" of ")
                .append(run.tokenBudget()).append(".");
        if (run.tokens() * 10 >= run.tokenBudget() * 8) {
            sb.append(" Your budget is nearly spent: commit what works and task.submit if the criteria are proven; otherwise "
                    + "task.block and say precisely what remains, so the Lead can split it into smaller tasks.");
        }
        gateway.outputCap(agent.providerId(), agent.model()).filter(cap -> cap < 4_000).ifPresent(cap -> sb.append(
                " Your model can write about ").append(cap).append(" tokens per reply: keep each reply small, create files in "
                + "parts (file.write a skeleton, then file.edit) and never write more than about ").append(cap * 3)
                .append(" characters of content in one reply."));
        sb.append(" Decide your next actions.");
        return sb.toString();
    }

    private Optional<String> brief(Task task, Agent agent) {
        return collaboration.threadOf("TASK", task.id()).flatMap(t -> collaboration.messages(t).stream()
                .filter(m -> "ASSIGNMENT".equals(m.type()) && agent.id().equals(m.toAgentId()))
                .reduce((a, b) -> b).map(m -> m.content()));
    }

    /** The same failing call three times in a row: the agent is looping. */
    private String repeatedFailure(String runId) {
        List<RunStep> toolSteps = runs.steps(runId).stream().filter(s -> s.kind().equals("TOOL")).toList();
        if (toolSteps.size() < 3) return null;
        List<RunStep> last = toolSteps.subList(toolSteps.size() - 3, toolSteps.size());
        boolean allFailed = last.stream().noneMatch(s -> s.outcome().equals("OK"));
        Object sig = last.getFirst().detail().get("sig");
        boolean same = sig != null && last.stream().allMatch(s -> sig.equals(s.detail().get("sig")));
        return allFailed && same ? last.getLast().summary() : null;
    }

    private AgentTools.Result sendMessage(TaskRun run, Task task, Agent agent, JsonNode args) {
        String to = args.path("to").asString("Lead"), content = args.path("content").asString("");
        if (content.isBlank()) return AgentTools.Result.refused("message.send", "Give the content.");
        Optional<Agent> target = to.equalsIgnoreCase("lead") ? agents.lead(task.projectId())
                : agents.list(task.projectId()).stream().filter(a -> a.name().equalsIgnoreCase(to)).findFirst();
        if (target.isEmpty()) return AgentTools.Result.error("message.send to " + to, "No teammate named " + to + ".");
        tx.executeWithoutResult(s -> tasks.message(task, Actor.agent(agent.id()), target.get().id(), "QUESTION", content));
        return AgentTools.Result.ok("message.send to " + target.get().name(), "Sent. Answers arrive as new messages.");
    }

    private AgentTools.Result record(TaskRun run, Agent agent, JsonNode args) {
        String title = args.path("title").asString(""), content = args.path("content").asString("");
        if (title.isBlank() || content.isBlank()) return AgentTools.Result.refused("memory.record", "Give a title and content.");
        tx.executeWithoutResult(s -> memory.record(runs.workspaceOf(run), run.projectId(), null, null, "DECISION", title, content,
                "TASK", run.taskId(), Actor.agent(agent.id())));
        return AgentTools.Result.ok("memory.record \"" + title + "\"", "Recorded in shared memory.");
    }

    private static Map<String, Object> argsSummary(JsonNode args) {
        Map<String, Object> out = new LinkedHashMap<>();
        args.properties().forEach(e -> {
            String v = e.getValue().isString() ? e.getValue().asString() : e.getValue().toString();
            out.put(e.getKey(), v.length() > 160 ? v.substring(0, 160) + "…" : v);
        });
        return out;
    }

    // ================================================================== block, pause, resume

    private void block(TaskRun run, Task task, Agent agent, String reason) {
        tx.executeWithoutResult(s -> {
            runs.status(run, "BLOCKED", reason);
            runs.step(run, "PLATFORM", null, "Blocked: " + reason, Map.of(), "OK", 0, 0, 0);
            tasks.block(task.id(), reason, Actor.agent(agent.id()));
            agents.setResting(agent.id(), "BLOCKED", reason);
            agents.lead(task.projectId()).filter(l -> !l.id().equals(agent.id())).ifPresent(lead ->
                    tasks.message(task, Actor.agent(agent.id()), lead.id(), "PROBLEM", "T-" + task.number() + " is blocked: " + reason));
        });
    }

    public TaskRun pause(String taskId, String userId) {
        TaskRun run = runs.openForTask(taskId).filter(r -> r.status().equals("RUNNING"))
                .orElseThrow(() -> ApiException.conflict("not_running", "No work is running on this task"));
        tx.executeWithoutResult(s -> {
            runs.requestPause(run.id(), true);
            audit.record(runs.workspaceOf(run), Actor.human(userId), "task_run.pause", "task_run", run.id(), AuditLog.Decision.RECORDED, Map.of());
        });
        return runs.find(run.id()).orElseThrow();
    }

    /** Resumes a paused or blocked run (after the CTO unblocked the task or answered). */
    public TaskRun resume(String taskId, Actor actor) {
        TaskRun run = runs.openForTask(taskId).filter(r -> Set.of("PAUSED", "BLOCKED").contains(r.status()))
                .orElseThrow(() -> ApiException.conflict("not_paused", "No paused or blocked work on this task"));
        Task task = tasks.find(taskId).orElseThrow();
        if (runs.agentBusyElsewhere(run.agentId(), run.id())) throw ApiException.conflict("agent_busy", "The agent is working on another task");
        if (budgetExhausted(run)) {
            throw ApiException.conflict("budget_exhausted", "The token budget of this task is spent (" + run.tokens() + " of "
                    + run.tokenBudget() + "): raise it or ask the Lead to split the task");
        }
        tx.executeWithoutResult(s -> {
            if ("BLOCKED".equals(task.status())) tasks.unblock(taskId, actor);
            tasks.advance(taskId, "IN_PROGRESS", actor, Map.of("runId", run.id()));
            runs.status(run, "RUNNING", null);
            runs.step(run, "PLATFORM", null, "Resumed", Map.of(), "OK", 0, 0, 0);
            agents.setResting(run.agentId(), "WORKING", null);
            jobs.enqueue(runs.workspaceOf(run), run.projectId(), run.agentId(), STEP, Map.of("runId", run.id()), run.id());
        });
        return runs.find(run.id()).orElseThrow();
    }

    static boolean budgetExhausted(TaskRun run) {
        return run.tokens() >= run.tokenBudget();
    }

    /**
     * The CTO changes the token budget of the task's open run. A run stopped because its budget was spent resumes
     * at once when the new budget leaves room.
     */
    public TaskRun setBudget(String taskId, String userId, long budget) {
        TaskRun run = runs.openForTask(taskId).orElseThrow(() -> ApiException.conflict("no_run", "No open work on this task"));
        if (budget <= run.tokens()) {
            throw ApiException.badRequest("budget_too_low", "This task has already used " + run.tokens() + " tokens: give more than that");
        }
        boolean stoppedByBudget = "BLOCKED".equals(run.status()) && budgetExhausted(run);
        Actor actor = Actor.human(userId);
        tx.executeWithoutResult(s -> {
            runs.budget(run, budget, actor);
            runs.step(run, "PLATFORM", null, "Token budget changed by the CTO: " + run.tokenBudget() + " → " + budget,
                    Map.of(), "OK", 0, 0, 0);
            audit.record(runs.workspaceOf(run), actor, "task_run.budget", "task_run", run.id(), AuditLog.Decision.RECORDED,
                    Map.of("from", String.valueOf(run.tokenBudget()), "to", String.valueOf(budget)));
        });
        if (stoppedByBudget && !runs.agentBusyElsewhere(run.agentId(), run.id())) return resume(taskId, actor);
        return runs.find(run.id()).orElseThrow();
    }

    // ================================================================== realignment meetings

    private String pausedForMeeting(String runId) {
        return jdbc.sql("SELECT paused_for_meeting FROM task_runs WHERE id = ?").param(runId).query(String.class).optional().orElse(null);
    }

    /** Pauses the agent's running work for the meeting (at its next step); work already paused stays as it is. */
    void pauseForMeeting(String agentId, String meetingId) {
        runs.openForAgent(agentId).stream().filter(r -> "RUNNING".equals(r.status())).forEach(r -> {
            runs.requestPause(r.id(), true);
            runs.pausedForMeeting(r.id(), meetingId);
        });
    }

    /**
     * When the meeting is over, the work it paused resumes, with what the meeting decided as a new item. Runs that
     * had not reached their pause point yet simply go on. Never throws: it runs in the meeting's transaction.
     */
    int resumeAfterMeeting(String meetingId, String note) {
        int resumed = 0;
        for (TaskRun run : runs.pausedFor(meetingId)) {
            runs.pausedForMeeting(run.id(), null);
            if (note != null && !note.isBlank()) {
                runs.step(run, "MESSAGE", null, "Outcome of the realignment meeting", Map.of("text", note), "OK", 0, 0, 0);
            }
            if ("RUNNING".equals(run.status())) {
                runs.requestPause(run.id(), false);
            } else if ("PAUSED".equals(run.status()) && !runs.agentBusyElsewhere(run.agentId(), run.id())) {
                Task task = tasks.find(run.taskId()).orElseThrow();
                if (TaskService.CLOSED.contains(task.status())) continue;
                tasks.advance(task.id(), "IN_PROGRESS", Actor.system(), Map.of("runId", run.id()));
                runs.status(run, "RUNNING", null);
                runs.step(run, "PLATFORM", null, "Resumed after the meeting", Map.of(), "OK", 0, 0, 0);
                agents.setResting(run.agentId(), "WORKING", null);
                jobs.enqueue(runs.workspaceOf(run), run.projectId(), run.agentId(), STEP, Map.of("runId", run.id()), run.id());
                resumed++;
            }
        }
        return resumed;
    }

    /**
     * A solution agreed in a meeting reaches the task's open run as a message; a run the agent had blocked resumes with it
     * (not one stopped by its budget: that needs the CTO). Returns null (no open run), "posted" or "resumed". Never throws.
     */
    String deliverSolution(String taskId, String text) {
        Optional<TaskRun> open = runs.openForTask(taskId);
        if (open.isEmpty()) return null;
        TaskRun run = open.get();
        runs.step(run, "MESSAGE", null, "Solution agreed in the realignment meeting", Map.of("text", text), "OK", 0, 0, 0);
        if (!"BLOCKED".equals(run.status()) || budgetExhausted(run) || runs.agentBusyElsewhere(run.agentId(), run.id())) {
            return "posted";
        }
        Task task = tasks.find(taskId).orElseThrow();
        if ("BLOCKED".equals(task.status())) tasks.unblock(taskId, Actor.system());
        tasks.advance(taskId, "IN_PROGRESS", Actor.system(), Map.of("runId", run.id()));
        runs.status(run, "RUNNING", null);
        runs.step(run, "PLATFORM", null, "Resumed with the solution from the meeting", Map.of(), "OK", 0, 0, 0);
        agents.setResting(run.agentId(), "WORKING", null);
        jobs.enqueue(runs.workspaceOf(run), run.projectId(), run.agentId(), STEP, Map.of("runId", run.id()), run.id());
        return "resumed";
    }

    /** Agents whose work stays blocked after a meeting show as blocked again (the meeting showed them in it). */
    void restoreBlocked(List<String> agentIds) {
        for (String agentId : agentIds) {
            runs.openForAgent(agentId).stream().filter(r -> "BLOCKED".equals(r.status())).findFirst()
                    .ifPresent(r -> agents.setResting(agentId, "BLOCKED", r.blockedReason()));
        }
    }

    /** What the agent is working on, for a realignment meeting turn (a few lines, built by code). */
    String workStatus(String agentId) {
        List<TaskRun> open = runs.openForAgent(agentId);
        StringBuilder sb = new StringBuilder();
        for (TaskRun run : open) {
            Task task = tasks.find(run.taskId()).orElse(null);
            if (task == null) continue;
            sb.append("- T-").append(task.number()).append(" \"").append(task.title()).append("\": ").append(run.status().toLowerCase());
            if (run.blockedReason() != null) sb.append(" — ").append(AgentTools.clip(run.blockedReason(), 400));
            sb.append(" · ").append(run.steps()).append(" steps, ").append(run.tokens()).append(" of ").append(run.tokenBudget()).append(" tokens");
            runs.steps(run.id()).stream().filter(st -> "MODEL".equals(st.kind())).reduce((a, b) -> b)
                    .ifPresent(st -> sb.append("\n  last thought: ").append(AgentTools.clip(st.summary(), 300)));
            sb.append("\n");
        }
        return sb.toString();
    }

    public boolean hasBlockedRun(String taskId) {
        return runs.openForTask(taskId).map(r -> "BLOCKED".equals(r.status())).orElse(false);
    }

    /** A message from the CTO, read by the agent at its next step. */
    public RunStep message(String taskId, String userId, String content) {
        TaskRun run = runs.openForTask(taskId).orElseThrow(() -> ApiException.conflict("no_run", "No work on this task yet"));
        Task task = tasks.find(taskId).orElseThrow();
        return tx.execute(s -> {
            tasks.message(task, Actor.human(userId), run.agentId(), "RECOMMENDATION", content);
            return runs.step(run, "MESSAGE", null, "Message from the CTO", Map.of("text", content), "OK", 0, 0, 0);
        });
    }

    /** Called when the task is cancelled: the run stops and its sandbox is removed; the branch stays. */
    public void onCancelled(String taskId) {
        runs.openForTask(taskId).ifPresent(run -> {
            sandboxes.stop(run.sandboxId());
            tx.executeWithoutResult(s -> {
                runs.status(run, "CANCELLED", null);
                if (!runs.agentBusy(run.agentId())) agents.setResting(run.agentId(), "AVAILABLE", null);
            });
        });
    }

    /** Ends a run inside the caller's transaction (its task was split); the sandbox is stopped by {@link #stopSandbox}. */
    void cancelRun(TaskRun run) {
        runs.status(run, "CANCELLED", null);
        if (!runs.agentBusy(run.agentId())) agents.setResting(run.agentId(), "AVAILABLE", null);
    }

    void stopSandbox(TaskRun run) {
        sandboxes.stop(run.sandboxId());
    }

    void failed(AgentJob job, String error) {
        Optional.ofNullable(job.string("runId")).flatMap(runs::find).ifPresent(run -> tx.executeWithoutResult(s -> {
            runs.status(run, "BLOCKED", error);
            runs.step(run, "PLATFORM", null, "Step failed: " + error, Map.of(), "ERROR", 0, 0, 0);
            Task task = tasks.find(run.taskId()).orElseThrow();
            if (!TaskService.CLOSED.contains(task.status()) && !"BLOCKED".equals(task.status())) {
                tasks.block(task.id(), error, Actor.system());
            }
        }));
    }

    void retried(AgentJob job) {
        Optional.ofNullable(job.string("runId")).flatMap(runs::find).ifPresent(run -> tx.executeWithoutResult(s -> {
            Task task = tasks.find(run.taskId()).orElseThrow();
            if ("BLOCKED".equals(task.status())) tasks.unblock(task.id(), Actor.system());
            if (STEP.equals(job.type())) {
                tasks.advance(task.id(), "IN_PROGRESS", Actor.system(), Map.of());
                runs.status(run, "RUNNING", null);
                agents.setResting(run.agentId(), "WORKING", null);
            }
        }));
    }

    // ================================================================== submission

    /**
     * The platform's checks on a submission, in order: commit leftovers, bring main in (conflicts), secrets, tests.
     * Any problem goes back to the agent as a new item; otherwise a merge request opens and a teammate reviews.
     */
    private void submit(TaskRun run, Task task, Agent agent, String summary) {
        Path wt = Path.of(run.worktree());
        GitService.Author author = author(agent);
        git.commitAll(wt, "T-" + task.number() + ": " + firstLine(summary, task.title()), author);
        String ws = runs.workspaceOf(run);

        GitService.MergeOutcome merged = git.mergeMainInto(wt, author);
        if (!merged.clean()) {
            back(run, "Conflicts with main in " + String.join(", ", merged.conflicts()),
                    "main changed since your branch started and conflicts with your work in: " + String.join(", ", merged.conflicts())
                            + ".\nThe files contain conflict markers (<<<<<<< ======= >>>>>>>). Keep both intents, remove the markers, "
                            + "run the tests, git.commit, then task.submit again.");
            return;
        }
        if (git.changes(wt, GitService.MAIN, "HEAD").isEmpty()) {
            back(run, "Nothing to submit", "Your branch has no change compared to main.");
            return;
        }
        List<SecretScan.Finding> secrets = SecretScan.scan(git.diff(wt, GitService.MAIN, "HEAD", null));
        if (!secrets.isEmpty()) {
            back(run, "Secret found in the diff", "Remove these credentials from the code (use configuration instead): "
                    + secrets.stream().map(f -> f.kind() + " in " + f.file()).reduce((a, b) -> a + "; " + b).orElse(""));
            return;
        }
        tx.executeWithoutResult(s -> tasks.advance(task.id(), "IN_TEST", Actor.system(), Map.of("runId", run.id())));
        String head = git.head(wt);
        Optional<TestRun> testRun = tests.run(new TestRunService.Context(ws, run.projectId(), task.id(), run.id(), null,
                run.branch(), head, "submission"), ensureSandbox(run), wt);
        if (testRun.isEmpty()) {
            tx.executeWithoutResult(s -> tasks.advance(task.id(), "IN_PROGRESS", Actor.system(), Map.of()));
            back(run, "No test command", "The platform found no test command. Add tests that prove each acceptance criterion "
                    + "and a test script (see rules), commit, then submit again.");
            return;
        }
        TestRun tr = testRun.get();
        if (!tr.succeeded()) {
            tx.executeWithoutResult(s -> tasks.advance(task.id(), "IN_PROGRESS", Actor.system(), Map.of()));
            back(run, "Tests " + tr.status().toLowerCase() + " on submission (tr-" + tr.number() + ")",
                    "The platform ran `" + tr.command() + "` on your branch merged with main:\n" + tr.failingExcerpt());
            return;
        }
        Optional<Agent> reviewer = reviewerFor(task, agent);
        tx.executeWithoutResult(s -> {
            MergeRequest mr = mrs.openForTask(task.id()).map(existing -> {
                mrs.update(existing.id(), "IN_REVIEW", head, null);
                mrs.nextRound(existing.id(), reviewer.map(Agent::id).orElse(null));
                return mrs.find(existing.id()).orElseThrow();
            }).orElseGet(() -> {
                MergeRequest opened = mrs.open(ws, run.projectId(), task.id(), run.id(), run.branch(), agent.id(),
                        reviewer.map(Agent::id).orElse(null), "T-" + task.number() + " " + task.title(), summary == null ? "" : summary, head);
                mrs.nextRound(opened.id(), reviewer.map(Agent::id).orElse(null));
                return mrs.find(opened.id()).orElseThrow();
            });
            jdbc.sql("UPDATE test_runs SET mr_id = ? WHERE id = ?").params(mr.id(), tr.id()).update();
            runs.status(run, "SUBMITTED", null);
            runs.step(run, "PLATFORM", null, "Submitted: tests passed (tr-" + tr.number() + "), merge request !" + mr.number()
                    + (reviewer.isPresent() ? " · review by " + reviewer.get().name() : " · review by the CTO"),
                    Map.of("mrId", mr.id(), "testRun", tr.number()), "OK", 0, 0, 0);
            tasks.advance(task.id(), "IN_REVIEW", Actor.agent(agent.id()), Map.of("mrId", mr.id()));
            agents.setResting(agent.id(), "AVAILABLE", null);
            reviewer.ifPresent(r -> jobs.enqueue(ws, run.projectId(), r.id(), REVIEW, Map.of("mrId", mr.id()), mr.id()));
        });
    }

    /** A problem found by the platform goes back to the agent, who keeps working. */
    private void back(TaskRun run, String summary, String text) {
        tx.executeWithoutResult(s -> {
            runs.step(run, "PLATFORM", null, summary, Map.of("text", text), "ERROR", 0, 0, 0);
            jobs.enqueue(runs.workspaceOf(run), run.projectId(), run.agentId(), STEP, Map.of("runId", run.id()), run.id());
        });
    }

    /** Another active agent: QA first, then the author's department, then anyone but the Lead, then the Lead. */
    private Optional<Agent> reviewerFor(Task task, Agent author) {
        List<Agent> candidates = agents.list(task.projectId()).stream()
                .filter(a -> !a.id().equals(author.id()) && "ACTIVE".equals(a.status()) && a.providerId() != null && a.model() != null)
                .toList();
        return candidates.stream().min(Comparator.comparingInt(a -> a.department().name().equals("QA") ? 0
                : a.department() == author.department() ? 1 : a.lead() ? 3 : 2));
    }

    // ================================================================== review

    void review(AgentJob job) {
        MergeRequest mr = mrs.find(job.string("mrId")).orElseThrow();
        if (!"IN_REVIEW".equals(mr.status())) return;
        Agent reviewer = runner.agent(job);
        Task task = tasks.find(mr.taskId()).orElseThrow();
        TaskRun run = runs.find(mr.runId()).orElseThrow();
        Path repo = git.repo(mr.projectId()).orElseThrow();
        String diff = git.diff(repo, GitService.MAIN, mr.sourceBranch(), null);
        TestRun tr = tests.list(mr.projectId(), task.id(), 1).stream().findFirst().orElse(null);
        String testOutput = tr == null ? "" : tests.output(tr.id()).map(o -> lastLines(o, 40)).orElse("");
        StringBuilder sb = new StringBuilder();
        sb.append("You review merge request !").append(mr.number()).append(" for task T-").append(task.number())
                .append(" \"").append(task.title()).append("\", written by ").append(agents.find(mr.authorAgentId()).map(Agent::name).orElse("a teammate"))
                .append(".\n<summary_from_author>\n").append(mr.summary()).append("\n</summary_from_author>\n");
        sb.append("<acceptance_criteria>\n");
        for (int i = 0; i < task.acceptanceCriteria().size(); i++) sb.append(i + 1).append(". ").append(task.acceptanceCriteria().get(i).text()).append("\n");
        sb.append("</acceptance_criteria>\n");
        if (tr != null) {
            sb.append("<tests run=\"tr-").append(tr.number()).append("\" status=\"").append(tr.status()).append("\" command=\"")
                    .append(tr.command()).append("\">\n").append(testOutput).append("\n</tests>\n");
        }
        List<ReviewComment> earlier = mrs.comments(mr.id());
        if (!earlier.isEmpty()) {
            sb.append("<earlier_comments>\n");
            earlier.forEach(c -> sb.append("round ").append(c.round()).append(c.file() == null ? "" : " " + c.file() + (c.line() == null ? "" : ":" + c.line()))
                    .append(": ").append(c.body()).append("\n"));
            sb.append("</earlier_comments>\n");
        }
        sb.append("<diff>\n").append(AgentTools.clip(diff, 24_000)).append("\n</diff>\n");
        sb.append("""
                Check correctness, the acceptance criteria and the tests that prove them. A criterion is met only if a \
                test in the diff proves it; cite that test as evidence. Request changes for real defects only, with \
                precise comments.
                JSON: {"decision": "APPROVE" or "REQUEST_CHANGES", "summary": "two sentences",
                 "comments": [{"file": "path", "line": 12, "body": "..."}],
                 "criteria": [{"index": 1, "met": true, "evidence": "test name"}]}""");
        AgentRunner.Context context = runner.context(reviewer, task.title(), 3_000, 2_000).oneShot();
        JsonNode out = runner.completeJson(job, reviewer, context, sb.toString(), 6_000, "medium",
                "Review !" + mr.number(), node -> {
                    String d = node.path("decision").asString("");
                    if (!Set.of("APPROVE", "REQUEST_CHANGES").contains(d)) throw new AgentRunner.InvalidOutput("decision must be APPROVE or REQUEST_CHANGES.");
                    if (node.path("criteria").size() != task.acceptanceCriteria().size()) {
                        throw new AgentRunner.InvalidOutput("criteria must have one entry per acceptance criterion (" + task.acceptanceCriteria().size() + ").");
                    }
                });
        List<Task.Criterion> criteria = new ArrayList<>();
        for (int i = 0; i < task.acceptanceCriteria().size(); i++) {
            JsonNode c = out.path("criteria").get(i);
            boolean met = c.path("met").asBoolean(false);
            String evidence = c.path("evidence").asString("");
            criteria.add(new Task.Criterion(task.acceptanceCriteria().get(i).text(), met && !evidence.isBlank(),
                    evidence.isBlank() ? null : evidence + (tr == null ? "" : " · tr-" + tr.number())));
        }
        boolean approve = "APPROVE".equals(out.path("decision").asString()) && criteria.stream().allMatch(Task.Criterion::met);
        List<String[]> comments = new ArrayList<>();
        for (JsonNode c : out.path("comments")) {
            comments.add(new String[] {c.path("file").asString(null), c.path("line").isInt() ? String.valueOf(c.path("line").asInt()) : null, c.path("body").asString("")});
        }
        concludeReview(mr, task, run, Actor.agent(reviewer.id()), reviewer.name(), approve, out.path("summary").asString(""), comments, criteria);
    }

    /** The CTO reviews (no teammate available, or the CTO steps in). Approving accepts the criteria as reviewed. */
    public MergeRequest humanReview(String mrId, String userId, boolean approve, String comment) {
        MergeRequest mr = mrs.find(mrId).orElseThrow(() -> ApiException.notFound("Merge request"));
        if (!Set.of("IN_REVIEW", "AWAITING_APPROVAL").contains(mr.status())) {
            throw ApiException.conflict("not_in_review", "This merge request is not waiting for a review");
        }
        Task task = tasks.find(mr.taskId()).orElseThrow();
        TaskRun run = runs.find(mr.runId()).orElseThrow();
        List<Task.Criterion> criteria = task.acceptanceCriteria().stream()
                .map(c -> approve ? new Task.Criterion(c.text(), true, c.met() && c.evidence() != null ? c.evidence() : "accepted by the CTO in review") : c)
                .toList();
        List<String[]> comments = comment == null || comment.isBlank() ? List.of() : List.<String[]>of(new String[] {null, null, comment});
        concludeReview(mr, task, run, Actor.human(userId), "the CTO", approve, comment == null ? "" : comment, comments, criteria);
        return mrs.find(mrId).orElseThrow();
    }

    private void concludeReview(MergeRequest mr, Task task, TaskRun run, Actor reviewer, String reviewerName, boolean approve,
                                String summary, List<String[]> comments, List<Task.Criterion> criteria) {
        tx.executeWithoutResult(s -> {
            int round = Math.max(mr.reviewRounds(), 1);
            for (String[] c : comments) {
                if (c[2] == null || c[2].isBlank()) continue;
                mrs.comment(mr.id(), round, reviewer, c[0], c[1] == null ? null : Integer.valueOf(c[1]), c[2]);
            }
            tasks.setCriteria(task.id(), criteria);
            String ws = runs.workspaceOf(run);
            Map<String, Object> payload = new HashMap<>();
            payload.put("number", mr.number());
            payload.put("decision", approve ? "APPROVE" : "REQUEST_CHANGES");
            payload.put("taskId", task.id());
            events.publish(ws, mr.projectId(), "merge_request.reviewed", reviewer, new Subject("merge_request", mr.id()), payload);
            if (!approve) {
                if (round >= MAX_REVIEW_ROUNDS) {
                    mrs.update(mr.id(), "CHANGES_REQUESTED", null, null);
                    Agent author = agents.find(run.agentId()).orElseThrow();
                    runs.status(run, "RUNNING", null);
                    blockInTx(run, task, author, "Still not approved after " + MAX_REVIEW_ROUNDS + " review rounds");
                    return;
                }
                mrs.update(mr.id(), "CHANGES_REQUESTED", null, null);
                tasks.advance(task.id(), "IN_PROGRESS", reviewer, Map.of("mrId", mr.id()));
                runs.status(run, "RUNNING", null);
                StringBuilder text = new StringBuilder(summary == null ? "" : summary.strip()).append("\n");
                comments.forEach(c -> text.append("- ").append(c[0] == null ? "" : c[0] + (c[1] == null ? "" : ":" + c[1]) + " ").append(c[2]).append("\n"));
                criteria.stream().filter(c -> !c.met()).forEach(c -> text.append("- Not proven yet: ").append(c.text()).append("\n"));
                runs.step(run, "PLATFORM", null, "Changes requested by " + reviewerName + " on !" + mr.number(),
                        Map.of("text", text.toString()), "ERROR", 0, 0, 0);
                agents.setResting(run.agentId(), "WORKING", null);
                jobs.enqueue(ws, mr.projectId(), run.agentId(), STEP, Map.of("runId", run.id()), run.id());
                return;
            }
            Map<String, Object> checks = gates(mr, task, criteria);
            boolean green = checks.values().stream().allMatch(v -> "ok".equals(((Map<?, ?>) v).get("status")));
            mrs.update(mr.id(), green ? "AWAITING_APPROVAL" : "IN_REVIEW", null, checks);
            runs.step(run, "PLATFORM", null, "Approved by " + reviewerName + (green ? " · waiting for the CTO to merge" : " · a gate is not green"),
                    Map.of("checks", checks), "OK", 0, 0, 0);
            if (!green) return;
            tasks.advance(task.id(), "VALIDATION", reviewer, Map.of("mrId", mr.id()));
            if (reviewer.kind() == Actor.Kind.HUMAN) {
                // The CTO reviewed and approved: that is the merge approval too.
                audit.record(ws, reviewer, MERGE_ACTION, "merge_request", mr.id(), AuditLog.Decision.APPROVED, Map.of("number", String.valueOf(mr.number())));
                jobs.enqueue(ws, mr.projectId(), run.agentId(), MERGE, Map.of("mrId", mr.id(), "userId", reviewer.id()), mr.id());
            } else {
                String approvalId = approvals.request(ws, mr.projectId(), reviewer, MERGE_ACTION, "merge_request", mr.id(),
                        "Merge !" + mr.number() + " " + mr.title() + " into main",
                        Map.of("number", mr.number(), "branch", mr.sourceBranch(), "taskId", task.id()));
                mrs.setApproval(mr.id(), approvalId);
            }
        });
    }

    private void blockInTx(TaskRun run, Task task, Agent agent, String reason) {
        runs.status(run, "BLOCKED", reason);
        runs.step(run, "PLATFORM", null, "Blocked: " + reason, Map.of(), "OK", 0, 0, 0);
        if (!"BLOCKED".equals(tasks.find(task.id()).orElseThrow().status())) tasks.block(task.id(), reason, Actor.system());
        agents.setResting(agent.id(), "BLOCKED", reason);
    }

    /** The validation engine's five gates, each {status: ok|pending|failed, detail}. */
    private Map<String, Object> gates(MergeRequest mr, Task task, List<Task.Criterion> criteria) {
        Map<String, Object> g = new LinkedHashMap<>();
        TestRun tr = tests.list(mr.projectId(), task.id(), 1).stream().findFirst().orElse(null);
        g.put("tests", gate(tr != null && tr.succeeded(), tr == null ? "not run" : "tr-" + tr.number() + " · " + tr.status().toLowerCase()
                + (tr.passed() == null ? "" : " · " + tr.passed() + " passed")));
        Path repo = git.repo(mr.projectId()).orElseThrow();
        List<SecretScan.Finding> secrets = SecretScan.scan(git.diff(repo, GitService.MAIN, mr.sourceBranch(), null));
        g.put("secrets", gate(secrets.isEmpty(), secrets.isEmpty() ? "clean" : secrets.size() + " finding(s)"));
        List<String> conflicts = git.conflictsWithMain(repo, mr.sourceBranch());
        g.put("conflicts", gate(conflicts.isEmpty(), conflicts.isEmpty() ? "none" : String.join(", ", conflicts)));
        g.put("review", gate(true, "approved"));
        long met = criteria.stream().filter(Task.Criterion::met).count();
        g.put("criteria", gate(met == criteria.size(), met + " / " + criteria.size()));
        return g;
    }

    private static Map<String, Object> gate(boolean ok, String detail) {
        return Map.of("status", ok ? "ok" : "failed", "detail", detail);
    }

    // ================================================================== merge

    @EventListener
    void onApproval(ApprovalDecided e) {
        if (!MERGE_ACTION.equals(e.action())) return;
        MergeRequest mr = mrs.find(e.targetId()).orElse(null);
        if (mr == null || !"AWAITING_APPROVAL".equals(mr.status())) return;
        TaskRun run = runs.find(mr.runId()).orElseThrow();
        if (e.approved()) {
            jobs.enqueue(e.workspaceId(), mr.projectId(), run.agentId(), MERGE, Map.of("mrId", mr.id(), "userId", e.userId()), mr.id());
            return;
        }
        Task task = tasks.find(mr.taskId()).orElseThrow();
        mrs.update(mr.id(), "CHANGES_REQUESTED", null, null);
        tasks.advance(task.id(), "IN_PROGRESS", Actor.human(e.userId()), Map.of("mrId", mr.id()));
        runs.status(run, "RUNNING", null);
        runs.step(run, "PLATFORM", null, "The CTO declined the merge of !" + mr.number(),
                Map.of("text", e.reason() == null ? "No reason given." : e.reason()), "ERROR", 0, 0, 0);
        agents.setResting(run.agentId(), "WORKING", null);
        jobs.enqueue(e.workspaceId(), mr.projectId(), run.agentId(), STEP, Map.of("runId", run.id()), run.id());
    }

    /** Re-checks against the current main (tests again if main moved), merges, closes the task. */
    void merge(AgentJob job) {
        MergeRequest mr = mrs.find(job.string("mrId")).orElseThrow();
        if (!"AWAITING_APPROVAL".equals(mr.status())) return;
        String userId = job.string("userId");
        Task task = tasks.find(mr.taskId()).orElseThrow();
        TaskRun run = runs.find(mr.runId()).orElseThrow();
        Agent author = agents.find(run.agentId()).orElseThrow();
        Path repo = git.repo(mr.projectId()).orElseThrow();
        Path wt = Path.of(run.worktree());
        String ws = runs.workspaceOf(run);
        if (!git.revParse(repo, GitService.MAIN).equals(git.revParse(wt, "HEAD")) && !mainIncluded(wt)) {
            GitService.MergeOutcome m = git.mergeMainInto(wt, author(author));
            if (!m.clean()) {
                reopen(mr, task, run, "main moved before the merge and now conflicts in " + String.join(", ", m.conflicts())
                        + ". Resolve the markers, test, commit and submit again.");
                return;
            }
            Optional<TestRun> again = tests.run(new TestRunService.Context(ws, mr.projectId(), task.id(), run.id(), mr.id(),
                    run.branch(), git.head(wt), "pre-merge"), ensureSandbox(run), wt);
            if (again.isPresent() && !again.get().succeeded()) {
                reopen(mr, task, run, "After bringing in the latest main, the tests fail (tr-" + again.get().number() + "):\n" + again.get().failingExcerpt());
                return;
            }
        }
        String commit = git.mergeIntoMain(repo, run.branch(), "Merge !" + mr.number() + ": " + mr.title(), GitService.Author.PLATFORM);
        sandboxes.stop(run.sandboxId());
        tx.executeWithoutResult(s -> {
            mrs.markMerged(mr.id(), commit, userId);
            events.publish(ws, mr.projectId(), "merge_request.merged", userId == null ? Actor.system() : Actor.human(userId),
                    new Subject("merge_request", mr.id()), Map.of("number", mr.number(), "commit", commit, "taskId", task.id()));
            runs.step(run, "PLATFORM", null, "Merged into main as " + commit.substring(0, 7) + " · task done", Map.of("commit", commit), "OK", 0, 0, 0);
            runs.status(run, "DONE", null);
            tasks.complete(task.id(), Actor.system(), Map.of("mrId", mr.id(), "commit", commit));
            if (!runs.agentBusy(author.id())) agents.setResting(author.id(), "AVAILABLE", null);
        });
        git.removeWorktree(repo, wt);
    }

    private boolean mainIncluded(Path wt) {
        try {
            return git.changes(wt, "HEAD", GitService.MAIN).isEmpty();
        } catch (GitService.GitException e) {
            return false;
        }
    }

    private void reopen(MergeRequest mr, Task task, TaskRun run, String text) {
        tx.executeWithoutResult(s -> {
            mrs.update(mr.id(), "CHANGES_REQUESTED", null, null);
            tasks.advance(task.id(), "IN_PROGRESS", Actor.system(), Map.of("mrId", mr.id()));
            runs.status(run, "RUNNING", null);
            runs.step(run, "PLATFORM", null, "Merge stopped: main moved", Map.of("text", text), "ERROR", 0, 0, 0);
            agents.setResting(run.agentId(), "WORKING", null);
            jobs.enqueue(runs.workspaceOf(run), mr.projectId(), run.agentId(), STEP, Map.of("runId", run.id()), run.id());
        });
    }

    /** Removes sandboxes whose run is over (or unknown), e.g. after a restart. Branches and worktrees stay. */
    @Scheduled(fixedDelayString = "${aiw.execution.cleanup-ms:300000}", initialDelayString = "${aiw.execution.cleanup-delay-ms:60000}")
    void cleanup() {
        if (!sandboxes.enabled()) return;
        sandboxes.running().forEach((runId, containerId) -> {
            boolean keep = runs.find(runId).map(r -> Set.of("RUNNING", "PAUSED", "SUBMITTED", "BLOCKED").contains(r.status())
                    && containerId.startsWith(r.sandboxId() == null ? "-" : r.sandboxId().substring(0, Math.min(12, r.sandboxId().length())))).orElse(false);
            if (!keep) sandboxes.stop(containerId);
        });
    }

    // ================================================================== helpers

    private static GitService.Author author(Agent agent) {
        return new GitService.Author(agent.name(), "agent-" + agent.id().toLowerCase() + "@aiw.local");
    }

    static String slug(String s, int max) {
        String out = java.text.Normalizer.normalize(s.toLowerCase(), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (out.length() > max) out = out.substring(0, max).replaceAll("-$", "");
        return out.isEmpty() ? "task" : out;
    }

    private static String firstLine(String s, String fallback) {
        if (s == null || s.isBlank()) return fallback;
        String l = s.strip().lines().findFirst().orElse(fallback);
        return l.length() > 72 ? l.substring(0, 72) : l;
    }

    private static String lastLines(String s, int n) {
        List<String> lines = s.lines().toList();
        return String.join("\n", lines.subList(Math.max(0, lines.size() - n), lines.size()));
    }

    // ================================================================== job handlers

    @Component
    static class StepHandler implements JobHandler {
        private final TaskExecution exec;

        StepHandler(TaskExecution exec) {
            this.exec = exec;
        }

        @Override public String type() { return STEP; }
        @Override public void handle(AgentJob job) { exec.step(job); }
        @Override public void onFailed(AgentJob job, String error) { exec.failed(job, error); }
        @Override public void onRetry(AgentJob job) { exec.retried(job); }
    }

    @Component
    static class ReviewHandler implements JobHandler {
        private final TaskExecution exec;

        ReviewHandler(TaskExecution exec) {
            this.exec = exec;
        }

        @Override public String type() { return REVIEW; }
        @Override public void handle(AgentJob job) { exec.review(job); }
    }

    @Component
    static class MergeHandler implements JobHandler {
        private final TaskExecution exec;

        MergeHandler(TaskExecution exec) {
            this.exec = exec;
        }

        @Override public String type() { return MERGE; }
        @Override public void handle(AgentJob job) { exec.merge(job); }
    }
}
