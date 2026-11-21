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
import dev.aiw.modelgateway.ModelCallException;
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
import java.util.regex.Pattern;
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
 * validation engine checks every gate; the Lead approves the merge into develop (the CTO when the Lead wrote the
 * work). Only then is the task DONE. develop reaches main through {@link BranchFlow}, with the CTO's approval.
 */
@Service
public class TaskExecution {
    private static final Logger log = LoggerFactory.getLogger(TaskExecution.class);
    static final String STEP = "TASK_STEP", REVIEW = "TASK_REVIEW", MERGE = "MR_MERGE";
    static final String MERGE_ACTION = "merge_request.merge";
    /** The reviewer agent and the author still disagree after {@link #MAX_REVIEW_ROUNDS} rounds: the CTO decides. */
    static final String ARBITRATE_ACTION = "merge_request.arbitrate";
    private static final int MAX_REVIEW_ROUNDS = 3;
    private static final int FULL_OUTPUTS = 8;

    /**
     * How much of the run a step prompt carries. NORMAL keeps everything; the lean levels are used when the provider
     * allows few tokens per minute (Groq free tier: 8 000), so that a long task still fits in one request.
     */
    record Lean(int historyEntries, int fullOutputs, int outputChars, int newsChars, int specChars, int memoryChars) {}
    /**
     * Default for every provider (CTO requirement, 2026-11-18: very small contexts): the last 12 entries, the last 2
     * outputs in full. Older work is summarized by the state of the repository, which the agent reads when it needs it.
     */
    static final Lean NORMAL = new Lean(12, 2, 2_000, 1_500, 1_200, 800);
    static final Lean TAKEOVER = new Lean(24, 4, 3_000, 2_000, 1_200, 800);
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
    private final long takeoverTokens;
    private final long maxTaskTokens;
    private final int takeoverSteps;
    private final BudgetService budgets;
    private final MeetingService meetings;
    private final BranchFlow branches;

    TaskExecution(TaskService tasks, TaskRuns runs, AgentTools tools, AgentRunner runner, AgentJobs jobs, AgentService agents,
                  CollaborationService collaboration, MemoryService memory, GitService git, SandboxService sandboxes,
                  TestRunService tests, MergeRequestService mrs, ApprovalService approvals, ProjectService projects,
                  EventPublisher events, AuditLog audit, TransactionTemplate tx, JdbcClient jdbc, JsonMapper json,
                  BudgetService budgets, MeetingService meetings, dev.aiw.modelgateway.ModelGateway gateway, BranchFlow branches,
                  @Value("${aiw.execution.auto-start:true}") boolean autoStart,
                  @Value("${aiw.execution.max-steps-per-segment:40}") int maxSegmentSteps,
                  @Value("${aiw.execution.token-budget:200000}") long tokenBudget,
                  @Value("${aiw.execution.takeover-token-budget:150000}") long takeoverTokens,
                  @Value("${aiw.execution.takeover-steps:30}") int takeoverSteps,
                  @Value("${aiw.execution.max-task-tokens:200000}") long maxTaskTokens) {
        this.maxTaskTokens = maxTaskTokens;
        this.takeoverTokens = takeoverTokens;
        this.takeoverSteps = takeoverSteps;
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
        this.branches = branches;
        this.autoStart = autoStart;
        this.maxSegmentSteps = maxSegmentSteps;
        this.tokenBudget = tokenBudget;
        this.budgets = budgets;
        this.meetings = meetings;
    }

    /** Tokens given to each new run of the project: the project's setting, else the platform default. */
    public long taskTokenBudget(String projectId) {
        Long own = budgets.get(projectId).taskTokenBudget();
        return Math.min(own == null ? tokenBudget : own, maxTaskTokens);
    }

    /**
     * No task ever uses more tokens than this (CTO requirement, 2026-11-20): budgets are never raised past it; a task
     * that runs out is split by the Lead into smaller ones that go on from its branch.
     */
    public long maxTaskTokens() {
        return maxTaskTokens;
    }

    /** Runs opened before the ceiling, or raised past it, are brought back under it. */
    @EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    void capOpenBudgets() {
        int capped = jdbc.sql("""
                UPDATE task_runs SET token_budget = ? WHERE token_budget > ?
                  AND status IN ('RUNNING', 'PAUSED', 'SUBMITTED', 'BLOCKED')
                """).params(maxTaskTokens, maxTaskTokens).update();
        if (capped > 0) log.info("Capped {} open run budget(s) to {} tokens", capped, maxTaskTokens);
    }

    // ================================================================== start

    /** Starts work on assigned tasks whose agent is free (one task at a time per agent). */
    @Scheduled(fixedDelayString = "${aiw.execution.sweep-ms:10000}", initialDelayString = "${aiw.execution.sweep-delay-ms:5000}")
    void sweep() {
        if (!autoStart || !sandboxes.enabled()) return;
        closeFinishedRuns();
        // Tasks still waiting although none of their dependencies is pending any more (all done or cancelled).
        jdbc.sql("""
                SELECT t.id FROM tasks t WHERE t.status = 'CREATED'
                  AND EXISTS (SELECT 1 FROM task_dependencies d WHERE d.task_id = t.id)
                  AND NOT EXISTS (SELECT 1 FROM task_dependencies d JOIN tasks x ON x.id = d.depends_on_id
                                  WHERE d.task_id = t.id AND x.status NOT IN ('DONE', 'CANCELLED'))
                """).query(String.class).list()
                .forEach(id -> tx.executeWithoutResult(s -> tasks.settle(id, Actor.system())));
        // Blocks the Lead settled while their agent was busy: they resume first, as soon as the agent is free.
        List<String> waiting = jdbc.sql("""
                SELECT DISTINCT ON (r.agent_id) r.task_id FROM task_runs r JOIN agents a ON a.id = r.agent_id
                WHERE r.resume_when_free AND r.status = 'BLOCKED' AND a.status = 'ACTIVE'
                  AND NOT EXISTS (SELECT 1 FROM task_runs o WHERE o.agent_id = r.agent_id AND o.status IN ('RUNNING', 'PAUSED'))
                ORDER BY r.agent_id, r.updated_at
                """).query(String.class).list();
        for (String taskId : waiting) {
            try {
                jdbc.sql("UPDATE task_runs SET resume_when_free = false WHERE task_id = ? AND resume_when_free").param(taskId).update();
                resume(taskId, Actor.system());
            } catch (RuntimeException e) {
                log.warn("Could not resume task {}", taskId, e);
            }
        }
        List<String> candidates = jdbc.sql("""
                SELECT DISTINCT ON (t.assignee_id) t.id FROM tasks t JOIN agents a ON a.id = t.assignee_id
                WHERE t.status = 'ASSIGNED' AND a.status = 'ACTIVE' AND a.provider_id IS NOT NULL AND a.model IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM task_runs r WHERE r.task_id = t.id AND r.status IN ('RUNNING', 'PAUSED', 'SUBMITTED', 'BLOCKED'))
                  AND NOT EXISTS (SELECT 1 FROM task_runs r WHERE r.agent_id = a.id AND r.status IN ('RUNNING', 'PAUSED'))
                  AND NOT EXISTS (SELECT 1 FROM task_runs r WHERE r.agent_id = a.id AND r.resume_when_free)
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

    /** Opens a run: branch from develop (the Lead creates it if needed), worktree, first step queued. The sandbox starts with the first step. */
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
        String from = branches.ensureDevelop(ws, project.id(), repo);
        // A task split from a bigger one may continue the work already committed on the original branch.
        String base = jdbc.sql("SELECT base_branch FROM tasks WHERE id = ?").param(taskId).query(String.class).optional().orElse(null);
        String branch = base != null && git.hasBranch(repo, base) ? base
                : "agent/" + slug(agent.name(), 30) + "/t-" + task.number() + "-" + slug(task.title(), 32);
        long budget = taskTokenBudget(project.id());
        Path worktree = git.worktree(repo, project.id(), branch, from);
        String image = SandboxService.imageFor(worktree);
        return tx.execute(s -> {
            TaskRun run = runs.create(ws, project.id(), taskId, agent.id(), branch, worktree.toString(), image, budget);
            tasks.advance(taskId, "IN_PROGRESS", actor, Map.of("runId", run.id(), "branch", branch));
            agents.setResting(agent.id(), "WORKING", null);
            runs.step(run, "PLATFORM", null, (branch.equals(base) ? "Continues branch " + branch : "Branch " + branch + " created from " + from)
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
        Optional<TaskRuns.Takeover> takeover = runs.takeover(run.id());
        // A step queued for whoever no longer works the run (the Lead took over, or handed back) is dropped.
        if (!agent.id().equals(takeover.map(TaskRuns.Takeover::leadId).orElse(run.agentId()))) return;
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
        if (takeover.isPresent()) {
            String spent = takeoverSpent(run, takeover.get());
            if (spent != null) {
                takeoverFailed(run, task, agent, spent);
                return;
            }
        } else if (run.tokens() >= run.tokenBudget()) {
            block(run, task, agent, "Token budget reached (" + run.tokens() + " of " + run.tokenBudget() + ")");
            return;
        } else if (run.segmentSteps() >= maxSegmentSteps) {
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
            // The Lead in a takeover keeps more of its own work in view: with 12 entries it read the same files again
            // and again (T-29: app.js read 10 times in 30 steps).
            Lean lean = takeover.isPresent() ? TAKEOVER : NORMAL;
            ctx = runner.context(agent, query, lean.specChars(), lean.memoryChars()).withStable(AgentTools.MANUAL);
            text = prompt(run, task, agent, lean, takeover);
        } else {
            // The whole request must fit in the provider's per-minute allowance: take the first level that fits.
            long budget = (perMinute - maxOut - 400) * CHARS_PER_TOKEN;
            for (Lean lean : LEAN) {
                ctx = runner.context(agent, query, lean.specChars(), lean.memoryChars()).withStable(AgentTools.MANUAL);
                text = prompt(run, task, agent, lean, takeover);
                long size = ctx.stable().length() + (ctx.dynamic() == null ? 0 : ctx.dynamic().length()) + text.length();
                if (size <= budget) break;
            }
        }
        AgentRunner.Context context = ctx;
        String prompt = text;
        long outTokens = maxOut;
        AgentRunner.Usage usage = new AgentRunner.Usage();
        long t0 = System.nanoTime();
        JsonNode decision;
        try {
            decision = runner.measure(usage, () -> runner.completeJson(job, agent, context, prompt, outTokens, "medium",
                    "T-" + task.number() + " · step " + (run.steps() + 1), this::validateDecision));
        } catch (AgentRunner.UnusableAnswer e) {
            // A malformed reply is the model's slip, not a blocker: it sees why at its next step. Blocked after 3 in a row.
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (unusableInARow(run) >= UNUSABLE_LIMIT - 1) {
                tx.executeWithoutResult(s -> runs.usage(run.id(), usage.input, usage.output, usage.cacheRead, usage.cost));
                throw e;
            }
            tx.executeWithoutResult(s -> {
                runs.usage(run.id(), usage.input, usage.output, usage.cacheRead, usage.cost);
                runs.step(run, "PLATFORM", null, UNUSABLE + e.getMessage(), Map.of("text", "Your reply could not be used: "
                        + e.getMessage() + (e.excerpt().isBlank() ? "\nYour reply was empty." : "\nIt began with:\n" + e.excerpt())
                        + "\nReply with one valid JSON object only, in the message text (not a native tool call); "
                        + "keep file contents short (several file.edit calls rather than one large file.write)."),
                        "ERROR", usage.input, usage.output, ms);
            });
            next(run);
            return;
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        tx.executeWithoutResult(s -> {
            runs.usage(run.id(), usage.input, usage.output, usage.cacheRead, usage.cost);
            runs.step(run, "MODEL", null, (takeover.isPresent() ? "Lead: " : "") + decision.path("thought").asString("(no thought)"),
                    Map.of("actions", decision.path("actions").size(), "cacheRead", usage.cacheRead), "OK",
                    usage.input, usage.output, ms);
        });
        if (decision.path(DROPPED).asInt(0) > 0) {
            int dropped = decision.path(DROPPED).asInt();
            tx.executeWithoutResult(s -> runs.step(run, "PLATFORM", null, "Only the first " + MAX_ACTIONS + " actions run",
                    Map.of("text", "Your reply listed " + (MAX_ACTIONS + dropped) + " actions; the last " + dropped
                            + " were not run. At most " + MAX_ACTIONS + " actions per reply."), "ERROR", 0, 0, 0));
        }

        AgentTools.End end = AgentTools.End.NONE;
        String endText = null;
        for (JsonNode action : decision.path("actions")) {
            String tool = action.path("tool").asString();
            JsonNode args = action.path("args").isObject() ? action.path("args") : json.createObjectNode();
            long a0 = System.nanoTime();
            AgentTools.Result r = switch (tool) {
                case "task.submit" -> new AgentTools.Result("OK", "task.submit", "Submitted.", Map.of(), AgentTools.End.SUBMIT);
                case "task.block" -> new AgentTools.Result("OK", "task.block · " + args.path("reason").asString(""), "Blocked.", Map.of(), AgentTools.End.BLOCK);
                case "task.handback" -> handbackCheck(run, takeover);
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
                endText = switch (tool) {
                    case "task.submit" -> args.path("summary").asString("");
                    case "task.handback" -> args.path("note").asString("");
                    default -> args.path("reason").asString("");
                };
                break;
            }
            if (!"OK".equals(r.outcome())) break;
        }

        if (takeover.isPresent()) {
            // The Lead works the run (docs/17): no ladder, no stop after two failing test runs; its own limits only.
            Agent worker = agents.find(run.agentId()).orElseThrow();
            switch (end) {
                case BLOCK -> takeoverFailed(run, task, agent, "the Lead stopped: " + (endText == null || endText.isBlank() ? "no reason given" : endText));
                case SUBMIT -> submit(run, task, worker, endText);
                case HANDBACK -> handBack(run, task, agent, endText);
                default -> {
                    String loop = repeatedFailure(run.id());
                    if (loop != null) takeoverFailed(run, task, agent, "the same call failed 3 times (" + loop + ")");
                    else next(run);
                }
            }
            return;
        }
        if (end == AgentTools.End.BLOCK) {
            String reason = endText == null || endText.isBlank() ? "Blocked without a reason" : endText;
            if (waitsForNothing(reason) && hasWork(run)) {
                // Seen on T-21: "Proceed to the next feature or wait for instructions" with the work committed; the Lead
                // spent two decisions saying "go on". The submission decides objectively: tests, then review.
                tx.executeWithoutResult(s -> runs.step(run, "PLATFORM", null, "Submitted for the agent: it waited with its work done",
                        Map.of("text", "You stopped with \"" + AgentTools.clip(reason) + "\" while your branch has work: the "
                                + "platform submitted it. Never wait for instructions: when your work is done, task.submit."),
                        "OK", 0, 0, 0));
                submit(run, task, agent, task.title());
                return;
            }
            block(run, task, agent, reason);
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
        String tests = testsFailedTwice(run.id());
        if (tests != null) {
            block(run, task, agent, TESTS_FAILED_TWICE + syntaxScan(run) + tests);
            return;
        }
        String edits = editsKeepFailing(runs.steps(run.id()));
        if (edits != null) {
            block(run, task, agent, edits);
            return;
        }
        next(run);
    }

    /** A block that asks nothing: the agent waits for a next step or says it is done (no question, no missing access). */
    private static final Pattern WAITS = Pattern.compile("(?i)\\b(wait(ing)?|await(ing)?|proceed|next (step|feature|task)|further instructions|"
            + "(is|are|has been|have been|been) (done|complete|completed|implemented|finished)|tests? pass(es|ed)?|ready for review|nothing (left|more) to do)\\b");
    private static final Pattern NEEDS = Pattern.compile("(?i)\\?|\\b(credential|token|password|api key|access|permission|approval|decide|decision|which|unclear|ambiguous|conflict|missing|cannot|can't|unable|error|fail)");

    static boolean waitsForNothing(String reason) {
        return reason != null && WAITS.matcher(reason).find() && !NEEDS.matcher(reason).find();
    }

    /** The branch has changes compared to the branch it goes into (committed or not). */
    private boolean hasWork(TaskRun run) {
        try {
            Path wt = Path.of(run.worktree());
            return git.dirty(wt) || !git.changes(wt, git.integrationBranch(wt), "HEAD").isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static final String UNUSABLE = "Reply not usable: ";
    private static final int UNUSABLE_LIMIT = 3;

    /** Unusable replies since the agent's last usable decision. */
    static final int MAX_ACTIONS = 5;
    static final String DROPPED = "_droppedActions";

    /** Native tool names cannot contain dots: "file_write" is our "file.write". */
    private static String toolName(String name) {
        String known = AgentTools.resolve(name);
        return known == null ? name : known;
    }

    private int unusableInARow(TaskRun run) {
        int count = 0;
        List<RunStep> steps = runs.steps(run.id());
        for (int i = steps.size() - 1; i >= 0; i--) {
            RunStep st = steps.get(i);
            // A model step, a resume or a block (then a retry) starts a new count.
            if ("MODEL".equals(st.kind()) || "Resumed".equals(st.summary()) || st.summary().startsWith("Step failed")
                    || st.summary().startsWith("Blocked")) break;
            if ("PLATFORM".equals(st.kind()) && st.summary().startsWith(UNUSABLE)) count++;
        }
        return count;
    }

    private void validateDecision(JsonNode node) {
        // Models trained on native tool calling (Groq, gpt-oss behind OpenRouter) may answer with native calls,
        // {"name": "repo.tree", "arguments": {...}} or {"tool_calls": [...]}, instead of our format: read as the actions.
        if (node instanceof ObjectNode o && !o.has("actions") && (o.path("name").isString() || o.path("tool_calls").isArray())) {
            List<JsonNode> calls = new ArrayList<>();
            if (o.path("name").isString()) calls.add(o);
            else o.path("tool_calls").forEach(calls::add);
            var list = o.putArray("actions");
            for (JsonNode c : calls) {
                ObjectNode action = json.createObjectNode().put("tool", toolName(c.path("name").asString("")));
                JsonNode args = c.path("arguments");
                if (args.isString()) {
                    try {
                        args = json.readTree(args.asString());
                    } catch (RuntimeException e) {
                        args = json.createObjectNode();
                    }
                }
                action.set("args", args.isObject() ? args : json.createObjectNode());
                list.add(action);
            }
            if (!o.has("thought")) o.put("thought", "Calls " + calls.stream().map(c -> toolName(c.path("name").asString(""))).collect(java.util.stream.Collectors.joining(", ")) + ".");
        }
        if (node instanceof ObjectNode o) blockWhenIdle(o);
        JsonNode actions = node.path("actions");
        if (!actions.isArray() || actions.isEmpty()) throw new AgentRunner.InvalidOutput("\"actions\" must list 1 to 5 actions.");
        if (actions.size() > MAX_ACTIONS && node instanceof ObjectNode o && actions instanceof tools.jackson.databind.node.ArrayNode arr) {
            // Too many actions: the first ones run, the agent is told the rest did not (rather than losing the reply).
            int dropped = arr.size() - MAX_ACTIONS;
            while (arr.size() > MAX_ACTIONS) arr.remove(arr.size() - 1);
            o.put(DROPPED, dropped);
        }
        if (actions instanceof tools.jackson.databind.node.ArrayNode arr) {
            for (int i = 0; i < arr.size(); i++) arr.set(i, normalizeAction(arr.get(i)));
        }
        for (JsonNode a : actions) {
            String tool = a.path("tool").asString("");
            if (!AgentTools.TOOLS.contains(tool)) {
                String tools = String.join(", ", AgentTools.TOOLS.stream().sorted().toList());
                throw new AgentRunner.InvalidOutput(tool.isBlank()
                        ? "Each action must be {\"tool\": \"<name>\", \"args\": {...}}, e.g. {\"tool\": \"task.block\", \"args\": {\"reason\": \"...\"}}. Tools: " + tools + "."
                        : "Unknown tool \"" + tool + "\". Tools: " + tools + ".");
            }
        }
    }

    /**
     * No action but a reason ("Waiting for the Lead's clarification"): the agent cannot go on, which is a block request.
     * Read as one, it reaches the Lead (docs/16) instead of failing as an unusable reply.
     */
    static void blockWhenIdle(ObjectNode o) {
        String thought = o.path("thought").asString("").strip();
        if (!o.path("actions").isArray() || !o.path("actions").isEmpty() || thought.isBlank()) return;
        ObjectNode block = o.objectNode().put("tool", "task.block");
        block.putObject("args").put("reason", thought.length() > 2_000 ? thought.substring(0, 2_000) : thought);
        o.putArray("actions").add(block);
    }

    /**
     * Reads the action shapes small models write instead of {"tool": t, "args": {...}} when the intent is unambiguous:
     * the tool as the key ({"task.block": {...}}), or "name"/"action"/"tool_name" with "arguments"/"parameters"/"input".
     * A retry costs a whole correction round trip; this costs nothing.
     */
    static JsonNode normalizeAction(JsonNode a) {
        if (!(a instanceof ObjectNode o) || AgentTools.TOOLS.contains(o.path("tool").asString(""))) return a;
        ObjectNode out = o.objectNode();
        String tool = null;
        for (String key : List.of("tool", "name", "action", "tool_name", "function")) {
            String v = AgentTools.resolve(o.path(key).asString(""));
            if (v != null) {
                tool = v;
                break;
            }
        }
        JsonNode args = null;
        if (tool != null) {
            for (String key : List.of("args", "arguments", "parameters", "params", "input")) {
                if (o.path(key).isObject()) {
                    args = o.path(key);
                    break;
                }
            }
        } else {
            // {"task.block": {"reason": "..."}}: one known tool as the only key (besides an empty "tool").
            List<String> keys = o.propertyNames().stream().filter(k -> !(k.equals("tool") && o.path(k).asString("").isBlank())).toList();
            if (keys.size() == 1 && AgentTools.resolve(keys.getFirst()) != null) {
                tool = AgentTools.resolve(keys.getFirst());
                args = o.path(keys.getFirst()).isObject() ? o.path(keys.getFirst()) : o.objectNode();
            }
        }
        if (tool == null) return a;
        out.put("tool", tool);
        out.set("args", args == null ? o.objectNode() : args);
        return out;
    }

    private void next(TaskRun run) {
        tx.executeWithoutResult(s -> jobs.enqueue(runs.workspaceOf(run), run.projectId(), driver(run), STEP,
                Map.of("runId", run.id()), run.id()));
    }

    /** Who works the run now: the Lead during a takeover (docs/17), else the run's agent. */
    String driver(TaskRun run) {
        return runs.takeover(run.id()).map(TaskRuns.Takeover::leadId).orElse(run.agentId());
    }

    /** JavaScript files of the branch that do not parse ({@link AgentTools#syntaxScan}), or "". */
    String syntaxScan(TaskRun run) {
        try {
            return AgentTools.syntaxScan(sandboxes, ensureSandbox(run), Path.of(run.worktree()));
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** node --check of one file of the run's worktree: {line, message}, or null when it parses or cannot be checked. */
    Object[] nodeCheck(TaskRun run, String path) {
        try {
            SandboxService.Command c = sandboxes.exec(ensureSandbox(run), "node --check '" + path.replace("'", "") + "'",
                    java.time.Duration.ofSeconds(20));
            return c.ok() ? null : LineEdits.nodeSyntaxError(c.output());
        } catch (RuntimeException e) {
            return null;
        }
    }

    String ensureSandbox(TaskRun stale) {
        TaskRun run = runs.find(stale.id()).orElseThrow(); // the sandbox may have been (re)started earlier in this job
        String image = run.image() == null ? SandboxService.imageFor(Path.of(run.worktree())) : run.image();
        String id = sandboxes.ensure(run.id(), run.sandboxId(), Path.of(run.worktree()), image);
        if (!id.equals(run.sandboxId())) tx.executeWithoutResult(s -> runs.sandbox(run.id(), id, image));
        return id;
    }

    /** The prompt of a step: the task, the branch, the compacted history and what is new since the last decision. */
    private String prompt(TaskRun run, Task task, Agent agent, Lean lean, Optional<TaskRuns.Takeover> takeover) {
        StringBuilder sb = new StringBuilder();
        List<RunStep> all = runs.steps(run.id());
        if (takeover.isPresent()) sb.append(takeoverBrief(run, task, all, takeover.get()));
        sb.append("<task number=\"T-").append(task.number()).append("\">\n").append(task.title()).append("\n");
        if (task.description() != null && !task.description().isBlank()) sb.append(task.description().strip()).append("\n");
        sb.append("Acceptance criteria (each must be proven by an automated test):\n");
        for (int i = 0; i < task.acceptanceCriteria().size(); i++) {
            sb.append(i + 1).append(". ").append(task.acceptanceCriteria().get(i).text()).append("\n");
        }
        sb.append("</task>\n");
        if (takeover.isEmpty()) brief(task, agent).ifPresent(b -> sb.append("<brief from=\"Lead\">\n").append(b).append("\n</brief>\n"));
        Path wt = Path.of(run.worktree());
        sb.append("<repository branch=\"").append(run.branch()).append("\">\n");
        List<GitService.FileChange> changes = git.changes(wt, git.integrationBranch(wt), "HEAD");
        sb.append(changes.isEmpty() ? "No committed change on your branch yet.\n"
                : "Committed on your branch: " + changes.stream().map(c -> c.status() + " " + c.path()).reduce((a, b) -> a + ", " + b).orElse("") + "\n");
        if (git.dirty(wt)) sb.append("You have uncommitted changes.\n");
        var plan = dev.aiw.quality.TestOutput.plan(wt);
        sb.append("Test command: ").append(plan == null ? "none yet (add one, see rules)" : plan.command()).append("\n</repository>\n");

        // The context starts at the latest intervention (CTO requirement, 2026-11-20: after the Lead's repair the agent
        // must not go back to the problem). Before, it carried twelve entries of its own failed attempts across the
        // Lead's message, whose text it lost after one step: on T-28 it redid the same broken edits after every answer.
        int reset = contextStart(all, takeover);
        List<RunStep> steps = all.subList(reset, all.size());
        RunStep instruction = reset > 0 && all.get(reset - 1).kind().equals("MESSAGE") ? all.get(reset - 1) : null;
        if (instruction != null) {
            Object text = instruction.detail().get("text");
            sb.append("<latest_instruction from=\"").append(instruction.summary().replace("Message from the ", ""))
                    .append("\" priority=\"follow it; earlier attempts are left out on purpose\">\n")
                    .append(AgentTools.clip(text == null ? instruction.summary() : text.toString(), 4_000)).append("\n</latest_instruction>\n");
        }
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
            if (reset > 0) sb.append("(work before the latest instruction is left out: the repository state above sums it up)\n");
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
        if (takeover.isEmpty()) sb.append("Step ").append(run.steps() + 1).append(" · tokens used ").append(run.tokens()).append(" of ")
                .append(run.tokenBudget()).append(".");
        if (takeover.isEmpty() && run.tokens() * 10 >= run.tokenBudget() * 8) {
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

    /**
     * Index of the first step the prompt shows: just after the latest message (Lead, CTO, meeting), or, during a
     * takeover, just after the takeover started (or a later message).
     */
    static int contextStart(List<RunStep> steps, Optional<TaskRuns.Takeover> takeover) {
        int start = 0;
        for (int i = 0; i < steps.size(); i++) {
            RunStep st = steps.get(i);
            if (st.kind().equals("MESSAGE") || (takeover.isPresent() && st.seq() == takeover.get().fromSeq())) start = i + 1;
        }
        if (takeover.isPresent()) {
            // A message from before the takeover is the agent's, not the Lead's.
            for (int i = 0; i < steps.size(); i++) if (steps.get(i).seq() == takeover.get().fromSeq()) start = Math.max(start, i + 1);
        }
        return start;
    }

    /** What the Lead needs when it works the task itself: why the agent stopped and how its takeover ends. */
    private String takeoverBrief(TaskRun run, Task task, List<RunStep> steps, TaskRuns.Takeover t) {
        String worker = agents.find(run.agentId()).map(Agent::name).orElse("The agent");
        RunStep marker = steps.stream().filter(st -> st.seq() == t.fromSeq()).findFirst().orElse(null);
        Object why = marker == null ? null : marker.detail().get("text");
        long stepsUsed = steps.stream().filter(st -> st.seq() > t.fromSeq() && "MODEL".equals(st.kind())).count();
        long tokensLeft = Math.max(0, Math.min(takeoverTokens - (run.tokens() - t.tokensAtStart()), run.tokenBudget() - run.tokens()));
        boolean canHandBack = (run.tokenBudget() - run.tokens()) * 5 >= run.tokenBudget();
        return "<takeover>\nYou are the Lead. " + worker + " could not finish T-" + task.number() + " after several attempts: "
                + "you take over and work on its branch yourself, with the tools below, until the tests pass.\n"
                + "Why it stopped:\n" + AgentTools.clip(why == null ? "(see the repository)" : why.toString(), 3_000) + "\n"
                + Regressions.explain(Regressions.find(git, Path.of(run.worktree()), why == null ? "" : why.toString()), true)
                + "- The repository is the truth: read the failing tests and the code they import before changing anything.\n"
                + "- A test written on this branch may be what is wrong (it tests outside the task, contradicts another test, "
                + "or cannot run under node --test): fix or rewrite it. A test that comes from develop is a requirement.\n"
                + "- A small file (under about 200 lines) whose structure is damaged: rewrite it whole with one file.write of "
                + "complete, valid content rather than line edits.\n"
                + "- Run the tests (exec) after each change; commit what works.\n"
                + "When the tests pass: task.submit {\"summary\"} if every criterion of the task is proven"
                + (canHandBack ? "; otherwise task.handback {\"note\": \"what you repaired and what remains for " + worker
                        + "\"}: " + worker + " goes on from your work with a fresh context" : " (" + worker
                        + "'s budget is spent: you finish the task)") + ".\n"
                + "If you cannot make progress: task.block {\"reason\"}; the CTO decides.\n"
                + "You have " + Math.max(0, takeoverSteps - stepsUsed) + " steps and " + tokensLeft + " tokens left.\n</takeover>\n";
    }

    private Optional<String> brief(Task task, Agent agent) {
        return collaboration.threadOf("TASK", task.id()).flatMap(t -> collaboration.messages(t).stream()
                .filter(m -> "ASSIGNMENT".equals(m.type()) && agent.id().equals(m.toAgentId()))
                .reduce((a, b) -> b).map(m -> m.content()));
    }

    /** The same failing call three times in a row: the agent is looping. */
    static final String TESTS_FAILED_TWICE = "Tests failed twice in a row; the Lead takes over. Latest failure:\n";
    private static final java.util.regex.Pattern TEST_COMMAND = java.util.regex.Pattern.compile(
            "^(npm (run )?test|npx (jest|vitest|mocha)|node --test|pytest|python3? -m (pytest|unittest)|mvn( -q)? test)\\b");

    /**
     * Tests may fail at most twice in a row (CTO requirement, 2026-11-18): an agent that does not fix them in two tries
     * on a small model rarely does in ten, it burns its steps and its budget (seen: 40 steps on one brace). Counts the
     * test runs since the last intervention (a message from the Lead or the CTO, a resume); returns the latest failing
     * excerpt when the last two failed, else null.
     */
    String testsFailedTwice(String runId) {
        List<RunStep> steps = runs.steps(runId);
        int failed = 0;
        String latest = null;
        for (int i = steps.size() - 1; i >= 0; i--) {
            RunStep st = steps.get(i);
            if (st.kind().equals("MESSAGE") || (st.kind().equals("PLATFORM") && st.summary().startsWith("Resumed"))) break;
            boolean submission = st.kind().equals("PLATFORM") && st.summary().startsWith("Tests ") && st.summary().contains("on submission");
            Object command = st.detail() == null ? null : st.detail().get("command");
            boolean own = st.kind().equals("TOOL") && "exec".equals(st.tool()) && command instanceof String c && TEST_COMMAND.matcher(c.strip()).find();
            if (!submission && !own) continue;
            if (st.outcome().equals("OK")) break;
            failed++;
            if (latest == null) {
                Object out = submission ? st.detail().get("text") : st.detail().get("output");
                latest = out instanceof String o ? dev.aiw.quality.TestOutput.failingExcerpt(o, 30) : st.summary();
            }
            if (failed >= 2) return latest.length() > 3_000 ? latest.substring(0, 3_000) + "…" : latest;
        }
        return null;
    }

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
            agents.lead(task.projectId()).filter(l -> !l.id().equals(agent.id())).ifPresent(lead -> {
                tasks.message(task, Actor.agent(agent.id()), lead.id(), "PROBLEM", "T-" + task.number() + " is blocked: " + reason);
                // Mode A: the Lead decides first; the CTO only gets what the Lead cannot settle (docs/16).
                if ("ACTIVE".equals(lead.status()) && lead.model() != null) {
                    jobs.enqueue(runs.workspaceOf(run), task.projectId(), lead.id(), LeadUnblocker.UNBLOCK,
                            Map.of("taskId", task.id(), "runId", run.id(), "reason", reason), task.id());
                }
            });
        });
    }

    // ================================================================== the Lead takes over (docs/17)

    /**
     * Changes refused or failed (file.edit, file.write) since the last intervention: past {@link #FAILED_EDITS}, the agent
     * is stopped and the Lead decides. Seen on T-28 (2026-11-19): more than a hundred refused edits of one 90-line file,
     * 40 steps at a time, each segment ending on the step limit.
     */
    static final int FAILED_EDITS = 6;

    static String editsKeepFailing(List<RunStep> steps) {
        int failed = 0;
        String last = null;
        for (int i = steps.size() - 1; i >= 0; i--) {
            RunStep st = steps.get(i);
            if (st.kind().equals("MESSAGE") || (st.kind().equals("PLATFORM") && st.summary().startsWith("Resumed"))) break;
            if (st.kind().equals("TOOL") && Set.of("file.edit", "file.write").contains(st.tool()) && !"OK".equals(st.outcome())) {
                failed++;
                if (last == null) last = st.summary();
            }
        }
        return failed >= FAILED_EDITS ? "Edits keep failing: " + failed + " changes refused since the last instruction (latest: "
                + last + "). The agent cannot make this change; the Lead takes over." : null;
    }

    /**
     * The Lead works the blocked run itself, on the agent's branch, with the same tools, until the tests pass (CTO
     * requirement, 2026-11-20: a task that failed four times is the Lead's to repair). Its steps go in the same run, its
     * commits carry its name. The agent is free meanwhile; it gets the work back with a fresh context.
     */
    void takeOver(Task task, TaskRun run, Agent lead, String why, String reason) {
        String ws = runs.workspaceOf(run);
        tx.executeWithoutResult(s -> {
            RunStep marker = runs.step(run, "PLATFORM", null, "The Lead takes over: " + why, Map.of("text", reason), "OK", 0, 0, 0);
            runs.startTakeover(run, lead.id(), marker.seq());
            if ("BLOCKED".equals(task.status())) tasks.unblock(task.id(), Actor.agent(lead.id()));
            tasks.advance(task.id(), "IN_PROGRESS", Actor.agent(lead.id()), Map.of("runId", run.id()));
            runs.status(run, "RUNNING", null);
            agents.setResting(run.agentId(), "AVAILABLE", null);
            agents.setResting(lead.id(), "WORKING", null);
            leadDecided(task, lead, "takeover", why);
            jobs.enqueue(ws, run.projectId(), lead.id(), STEP, Map.of("runId", run.id()), run.id());
        });
    }

    /** The takeover's own limits: steps and tokens since it started. Returns why it is over, or null. */
    private String takeoverSpent(TaskRun run, TaskRuns.Takeover t) {
        if (run.tokens() >= run.tokenBudget()) return "the task's budget of " + run.tokenBudget() + " tokens is spent";
        long tokens = run.tokens() - t.tokensAtStart();
        if (tokens >= takeoverTokens) return "its budget of " + takeoverTokens + " tokens is spent";
        long steps = runs.steps(run.id()).stream().filter(st -> st.seq() > t.fromSeq() && "MODEL".equals(st.kind())).count();
        return steps >= takeoverSteps ? "it used its " + takeoverSteps + " steps" : null;
    }

    /** task.handback: allowed during a takeover, when the agent still has a fifth of its budget to go on with. */
    private AgentTools.Result handbackCheck(TaskRun run, Optional<TaskRuns.Takeover> takeover) {
        if (takeover.isEmpty()) {
            return AgentTools.Result.refused("task.handback", "Only the Lead hands work back. Use task.submit or task.block.");
        }
        long left = run.tokenBudget() - run.tokens();
        if (left * 5 < run.tokenBudget()) {
            return AgentTools.Result.refused("task.handback", "The agent has spent its budget (" + Math.max(0, left)
                    + " tokens left): finish the task yourself and task.submit.");
        }
        return new AgentTools.Result("OK", "task.handback", "Handed back.", Map.of(), AgentTools.End.HANDBACK);
    }

    /**
     * The Lead gives the work back: its note becomes the agent's instruction and the start of its context (what came
     * before is left out of the agent's prompt, so it does not go back to the problem). The takeover's tokens count in
     * the task's budget: it is never raised.
     */
    private void handBack(TaskRun run, Task task, Agent lead, String note) {
        TaskRuns.Takeover t = runs.takeover(run.id()).orElseThrow();
        TaskRun now = runs.find(run.id()).orElseThrow();
        long used = now.tokens() - t.tokensAtStart();
        String text = "I (the Lead) took over T-" + task.number() + " and repaired it. " + (note == null || note.isBlank()
                ? "" : note.strip()) + "\nContinue from the repository as it is now: do not redo or undo my changes. Read a "
                + "file before editing it, run the tests, then task.submit when every criterion is proven.";
        tx.executeWithoutResult(s -> {
            runs.endTakeover(run.id());
            // The takeover counts in the task's budget, which is never raised (a task stays under the ceiling).
            runs.step(now, "PLATFORM", null, "The Lead's takeover used " + used + " tokens; " + Math.max(0, now.tokenBudget()
                    - now.tokens()) + " are left for the task", Map.of(), "OK", 0, 0, 0);
            leadMessage(task, now, lead, AgentTools.clip(text, 4_000));
            runs.step(now, "PLATFORM", null, "Resumed", Map.of(), "OK", 0, 0, 0);
            agents.setResting(lead.id(), "AVAILABLE", null);
            agents.setResting(run.agentId(), "WORKING", null);
            leadDecided(task, lead, "handback", AgentTools.clip(note == null ? "" : note, 500));
            jobs.enqueue(runs.workspaceOf(run), run.projectId(), run.agentId(), STEP, Map.of("runId", run.id()), run.id());
        });
    }

    /** The takeover could not finish: the run is blocked and the Lead tells the CTO (no new round of decisions). */
    private void takeoverFailed(TaskRun run, Task task, Agent lead, String why) {
        String reason = "The Lead took over and could not finish: " + why;
        tx.executeWithoutResult(s -> {
            runs.endTakeover(run.id());
            runs.status(run, "BLOCKED", reason);
            runs.step(run, "PLATFORM", null, "Blocked: " + reason, Map.of(), "OK", 0, 0, 0);
            if (!TaskService.CLOSED.contains(task.status()) && !"BLOCKED".equals(task.status())) {
                tasks.block(task.id(), reason, Actor.agent(lead.id()));
            }
            agents.setResting(lead.id(), "AVAILABLE", null);
            if (runs.openForAgent(run.agentId()).stream().noneMatch(r -> "RUNNING".equals(r.status()))) {
                agents.setResting(run.agentId(), "AVAILABLE", null);
            }
            leadDecided(task, lead, "takeover_failed", why);
            jobs.enqueue(runs.workspaceOf(run), task.projectId(), lead.id(), LeadUnblocker.UNBLOCK,
                    Map.of("taskId", task.id(), "runId", run.id(), "reason", reason, "afterTakeover", "true"), task.id());
        });
    }

    /** A decision of the Lead on the task, as LeadUnblocker records them (event + audit). */
    void leadDecided(Task task, Agent lead, String decision, String why) {
        String ws = tasks.workspaceOf(task);
        Actor actor = Actor.agent(lead.id());
        events.publish(ws, task.projectId(), "task.lead_decided", actor, new Subject("task", task.id()),
                Map.of("number", task.number(), "decision", decision, "why", why == null ? "" : why));
        audit.record(ws, actor, "task.lead_decided", "task", task.id(), AuditLog.Decision.RECORDED,
                Map.of("number", String.valueOf(task.number()), "decision", decision));
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
        if (Set.of("DONE", "CANCELLED").contains(task.status())) {
            closeFinishedRuns();
            throw ApiException.conflict("task_finished", "T-" + task.number() + " is " + task.status().toLowerCase()
                    + ": its old work was closed, there is nothing to resume");
        }
        if (runs.agentBusyElsewhere(run.agentId(), run.id())) throw ApiException.conflict("agent_busy", "The agent is working on another task");
        if (budgetExhausted(run) && runs.takeover(run.id()).isEmpty()) {
            throw ApiException.conflict("budget_exhausted", "The token budget of this task is spent (" + run.tokens() + " of "
                    + run.tokenBudget() + "): raise it or ask the Lead to split the task");
        }
        tx.executeWithoutResult(s -> {
            if ("BLOCKED".equals(task.status())) tasks.unblock(taskId, actor);
            tasks.advance(taskId, "IN_PROGRESS", actor, Map.of("runId", run.id()));
            runs.status(run, "RUNNING", null);
            jdbc.sql("UPDATE task_runs SET resume_when_free = false WHERE id = ?").param(run.id()).update();
            runs.step(run, "PLATFORM", null, "Resumed", Map.of(), "OK", 0, 0, 0);
            agents.setResting(driver(run), "WORKING", null);
            jobs.enqueue(runs.workspaceOf(run), run.projectId(), driver(run), STEP, Map.of("runId", run.id()), run.id());
        });
        return runs.find(run.id()).orElseThrow();
    }

    /**
     * Runs left open on a task that is done or cancelled (seen on T-8, 2026-11-19: done since the 16th, its run stayed
     * BLOCKED; every "Retry" on its agent tried to restart it in a worktree deleted with the merged branch). They are
     * closed, their sandbox stopped, and the agent is no longer shown blocked by them.
     */
    void closeFinishedRuns() {
        List<TaskRun> stale = jdbc.sql("""
                SELECT r.id FROM task_runs r JOIN tasks t ON t.id = r.task_id
                WHERE r.status IN ('RUNNING', 'PAUSED', 'SUBMITTED', 'BLOCKED') AND t.status IN ('DONE', 'CANCELLED')
                """).query(String.class).list().stream().map(id -> runs.find(id).orElseThrow()).toList();
        for (TaskRun run : stale) {
            String status = tasks.find(run.taskId()).map(Task::status).orElse("CANCELLED");
            tx.executeWithoutResult(s -> {
                runs.takeover(run.id()).ifPresent(t -> {
                    runs.endTakeover(run.id());
                    agents.setResting(t.leadId(), "AVAILABLE", null);
                });
                runs.status(run, status, null);
                jdbc.sql("UPDATE task_runs SET resume_when_free = false WHERE id = ?").param(run.id()).update();
                if (runs.openForAgent(run.agentId()).isEmpty()) agents.setResting(run.agentId(), "AVAILABLE", null);
            });
            if (run.sandboxId() != null) {
                try {
                    sandboxes.stop(run.sandboxId());
                } catch (RuntimeException e) {
                    // already gone
                }
            }
            log.info("Closed run {} of a {} task", run.id(), status.toLowerCase());
        }
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
        if (budget > maxTaskTokens) {
            throw ApiException.badRequest("over_task_ceiling", "A task never uses more than " + maxTaskTokens
                    + " tokens: ask the Lead to split it into smaller tasks instead");
        }
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
                agents.setResting(driver(run), "WORKING", null);
                jobs.enqueue(runs.workspaceOf(run), run.projectId(), driver(run), STEP, Map.of("runId", run.id()), run.id());
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
        agents.setResting(driver(run), "WORKING", null);
        jobs.enqueue(runs.workspaceOf(run), run.projectId(), driver(run), STEP, Map.of("runId", run.id()), run.id());
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

    /** The run resumes by itself once its agent is free (the Lead settled the block while the agent was busy). */
    void resumeWhenFree(TaskRun run) {
        jdbc.sql("UPDATE task_runs SET resume_when_free = true WHERE id = ?").param(run.id()).update();
        runs.step(run, "PLATFORM", null, "Resumes as soon as the agent finishes its current task", Map.of(), "OK", 0, 0, 0);
    }

    /** The Lead answers the agent of a blocked task (docs/16): read at the agent's next step, like the CTO's messages. */
    void leadMessage(Task task, TaskRun run, Agent lead, String content) {
        tasks.message(task, Actor.agent(lead.id()), run.agentId(), "RECOMMENDATION", content);
        runs.step(run, "MESSAGE", null, "Message from the Lead", Map.of("text", content), "OK", 0, 0, 0);
    }

    /** Called when the task is cancelled: the run stops and its sandbox is removed; the branch stays. */
    public void onCancelled(String taskId) {
        runs.openForTask(taskId).ifPresent(run -> {
            sandboxes.stop(run.sandboxId());
            tx.executeWithoutResult(s -> {
                runs.takeover(run.id()).ifPresent(t -> {
                    runs.endTakeover(run.id());
                    agents.setResting(t.leadId(), "AVAILABLE", null);
                });
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
        TaskRun failedRun = Optional.ofNullable(job.string("runId")).flatMap(runs::find).orElse(null);
        if (failedRun != null && runs.takeover(failedRun.id()).map(t -> t.leadId().equals(job.agentId())).orElse(false)) {
            // The Lead's own step failed during its takeover: the CTO decides, the Lead stays available.
            takeoverFailed(failedRun, tasks.find(failedRun.taskId()).orElseThrow(), agents.find(job.agentId()).orElseThrow(),
                    "a step failed: " + error);
            return;
        }
        Optional.ofNullable(failedRun).ifPresent(run -> tx.executeWithoutResult(s -> {
            runs.status(run, "BLOCKED", error);
            runs.step(run, "PLATFORM", null, "Step failed: " + error, Map.of(), "ERROR", 0, 0, 0);
            Task task = tasks.find(run.taskId()).orElseThrow();
            if (!TaskService.CLOSED.contains(task.status()) && !"BLOCKED".equals(task.status())) {
                tasks.block(task.id(), error, Actor.system());
            }
            // A failed step (watchdog, unusable replies) is a block like any other: the Lead decides first. Before,
            // it waited for the CTO (T-28, 2026-11-19: twice overnight).
            if (!TaskService.CLOSED.contains(task.status())) {
                agents.lead(task.projectId()).filter(l -> !l.id().equals(run.agentId()) && "ACTIVE".equals(l.status()) && l.model() != null)
                        .ifPresent(lead -> jobs.enqueue(runs.workspaceOf(run), task.projectId(), lead.id(), LeadUnblocker.UNBLOCK,
                                Map.of("taskId", task.id(), "runId", run.id(), "reason", "Step failed: " + error), task.id()));
            }
        }));
    }

    /** A provider limit: the step runs again by itself later; the run stays open and the task is not blocked. */
    void deferred(AgentJob job, String reason) {
        Optional.ofNullable(job.string("runId")).flatMap(runs::find)
                .ifPresent(run -> runs.step(run, "PLATFORM", null, "Waiting: " + reason, Map.of(), "OK", 0, 0, 0));
    }

    void retried(AgentJob job) {
        Optional.ofNullable(job.string("runId")).flatMap(runs::find).ifPresent(run -> tx.executeWithoutResult(s -> {
            Task task = tasks.find(run.taskId()).orElseThrow();
            if ("BLOCKED".equals(task.status())) tasks.unblock(task.id(), Actor.system());
            if (STEP.equals(job.type())) {
                tasks.advance(task.id(), "IN_PROGRESS", Actor.system(), Map.of());
                runs.status(run, "RUNNING", null);
                agents.setResting(driver(run), "WORKING", null);
            }
        }));
    }

    // ================================================================== submission

    /**
     * The platform's checks on a submission, in order: commit leftovers, bring develop in (conflicts), secrets, tests.
     * Any problem goes back to the agent as a new item; otherwise a merge request opens and a teammate reviews.
     */
    private void submit(TaskRun run, Task task, Agent agent, String summary) {
        Path wt = Path.of(run.worktree());
        // During a takeover the Lead commits (traceability); the merge request stays the agent's task.
        Optional<TaskRuns.Takeover> takeover = runs.takeover(run.id());
        Agent committer = takeover.flatMap(t -> agents.find(t.leadId())).orElse(agent);
        GitService.Author author = author(committer);
        git.commitAll(wt, "T-" + task.number() + ": " + firstLine(summary, task.title()), author);
        String ws = runs.workspaceOf(run);
        // Work started before the develop branch existed also goes through it.
        git.repo(run.projectId()).ifPresent(repo -> branches.ensureDevelop(ws, run.projectId(), repo));
        String target = git.integrationBranch(wt);

        GitService.MergeOutcome merged = git.mergeBaseInto(wt, target, author);
        if (!merged.clean()) {
            back(run, "Conflicts with " + target + " in " + String.join(", ", merged.conflicts()),
                    target + " changed since your branch started and conflicts with your work in: " + String.join(", ", merged.conflicts())
                            + ".\nThe files contain conflict markers (<<<<<<< ======= >>>>>>>). Keep both intents, remove the markers, "
                            + "run the tests, git.commit, then task.submit again.");
            return;
        }
        if (git.changes(wt, target, "HEAD").isEmpty()) {
            back(run, "Nothing to submit", "Your branch has no change compared to " + target + ".");
            return;
        }
        List<SecretScan.Finding> secrets = SecretScan.scan(git.diff(wt, target, "HEAD", null));
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
            String broken = Regressions.explain(Regressions.find(git, Path.of(run.worktree()), tr.failingExcerpt()), false)
                    + AgentTools.syntaxScan(sandboxes, ensureSandbox(run), Path.of(run.worktree()))
                    + AgentTools.testFileProblems(Path.of(run.worktree()));
            String text = "The platform ran `" + tr.command() + "` on your branch merged with " + target + ":\n"
                    + (broken.isEmpty() ? "" : broken + "\n") + tr.failingExcerpt();
            tx.executeWithoutResult(st -> runs.step(run, "PLATFORM", null, "Tests " + tr.status().toLowerCase() + " on submission (tr-"
                    + tr.number() + ")", Map.of("text", text), "ERROR", 0, 0, 0));
            String twice = takeover.isPresent() ? null : testsFailedTwice(run.id());
            if (twice != null) {
                block(run, task, agent, TESTS_FAILED_TWICE + syntaxScan(run) + twice);
                return;
            }
            next(run);
            return;
        }
        Optional<Agent> reviewer = reviewerFor(task, agent, target);
        tx.executeWithoutResult(s -> {
            MergeRequest mr = mrs.openForTask(task.id()).map(existing -> {
                mrs.update(existing.id(), "IN_REVIEW", head, null);
                mrs.retarget(existing.id(), target);
                mrs.nextRound(existing.id(), reviewer.map(Agent::id).orElse(null));
                return mrs.find(existing.id()).orElseThrow();
            }).orElseGet(() -> {
                MergeRequest opened = mrs.open(ws, run.projectId(), task.id(), run.id(), run.branch(), target, agent.id(),
                        reviewer.map(Agent::id).orElse(null), "T-" + task.number() + " " + task.title(), summary == null ? "" : summary, head);
                mrs.nextRound(opened.id(), reviewer.map(Agent::id).orElse(null));
                return mrs.find(opened.id()).orElseThrow();
            });
            jdbc.sql("UPDATE test_runs SET mr_id = ? WHERE id = ?").params(mr.id(), tr.id()).update();
            runs.status(run, "SUBMITTED", null);
            runs.step(run, "PLATFORM", null, "Submitted: tests passed (tr-" + tr.number() + "), merge request !" + mr.number()
                    + (reviewer.isPresent() ? " · review by " + reviewer.get().name() : " · review by the CTO"),
                    Map.of("mrId", mr.id(), "testRun", tr.number()), "OK", 0, 0, 0);
            tasks.advance(task.id(), "IN_REVIEW", Actor.agent(committer.id()), Map.of("mrId", mr.id()));
            agents.setResting(agent.id(), "AVAILABLE", null);
            if (takeover.isPresent()) {
                runs.endTakeover(run.id());
                agents.setResting(committer.id(), "AVAILABLE", null);
                leadDecided(task, committer, "takeover_submitted", "The Lead finished the task itself: tests pass (tr-" + tr.number() + ")");
            }
            reviewer.ifPresent(r -> jobs.enqueue(ws, run.projectId(), r.id(), REVIEW, Map.of("mrId", mr.id()), mr.id()));
        });
    }

    /** A problem found by the platform goes back to the agent, who keeps working. */
    private void back(TaskRun run, String summary, String text) {
        tx.executeWithoutResult(s -> {
            runs.step(run, "PLATFORM", null, summary, Map.of("text", text), "ERROR", 0, 0, 0);
            jobs.enqueue(runs.workspaceOf(run), run.projectId(), driver(run), STEP, Map.of("runId", run.id()), run.id());
        });
    }

    /**
     * Into develop, the Lead reviews and its approval is the merge decision (CTO requirement, 2026-11-11). When the Lead
     * wrote the work, or for main: QA first, then the author's department, then anyone but the Lead, then the Lead.
     */
    private Optional<Agent> reviewerFor(Task task, Agent author, String target) {
        List<Agent> candidates = agents.list(task.projectId()).stream()
                .filter(a -> !a.id().equals(author.id()) && "ACTIVE".equals(a.status()) && a.providerId() != null && a.model() != null)
                .toList();
        if (GitService.DEVELOP.equals(target)) {
            Optional<Agent> lead = candidates.stream().filter(Agent::lead).findFirst();
            if (lead.isPresent()) return lead;
        }
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
        List<GitService.FileChange> changed = git.changes(repo, mr.targetBranch(), mr.sourceBranch());
        TestRun tr = tests.list(mr.projectId(), task.id(), 1).stream().findFirst().orElse(null);
        String testOutput = tr == null ? "" : tests.output(tr.id()).map(o -> lastLines(o, 40)).orElse("");
        StringBuilder sb = new StringBuilder();
        sb.append("You review merge request !").append(mr.number()).append(" into ").append(mr.targetBranch())
                .append(" for task T-").append(task.number())
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
        String head = sb.toString();
        sb.setLength(0);
        sb.append("""
                Check correctness, the acceptance criteria and the tests that prove them. A criterion about behaviour is \
                met only if a test in the diff proves it: cite that test as evidence. A criterion about the code itself \
                (language, style, comments, structure, no build step) is met when the diff shows it: cite the file as \
                evidence. Do not ask for tools or test setups the task does not require. Do not repeat an earlier \
                comment the diff now addresses, and do not comment on a part of a file you were not shown. Request \
                changes for real defects only, with precise comments.
                JSON: {"decision": "APPROVE" or "REQUEST_CHANGES", "summary": "two sentences",
                 "comments": [{"file": "path", "line": 12, "body": "..."}],
                 "criteria": [{"index": 1, "met": true, "evidence": "test name"}]}""");
        String tail = sb.toString();
        JsonNode out = null;
        // A provider with a small per-minute allowance (Groq free tier: 8 000) learns its limit on the first refusal:
        // the review is then sized to fit it, the diff taking what is left.
        for (int attempt = 0; out == null; attempt++) {
            long perMinute = gateway.tokensPerMinute(reviewer.providerId(), reviewer.model()).orElse(0L);
            long maxOut = perMinute > 0 ? Math.min(6_000, Math.max(800, perMinute / 5)) : 6_000;
            AgentRunner.Context context = (perMinute > 0 ? runner.context(reviewer, task.title(), 1_000, 500)
                    : runner.context(reviewer, task.title(), 3_000, 2_000)).oneShot();
            int diffChars = 24_000;
            if (perMinute > 0) {
                long budget = (perMinute - maxOut - 1_200) * CHARS_PER_TOKEN - context.stable().length()
                        - (context.dynamic() == null ? 0 : context.dynamic().length()) - head.length() - tail.length() - 200;
                diffChars = (int) Math.max(1_500, Math.min(diffChars, budget));
            }
            String prompt = head + reviewDiff(repo, mr.targetBranch(), mr.sourceBranch(), changed, diffChars) + tail;
            try {
                out = runner.completeJson(job, reviewer, context, prompt, maxOut, "medium",
                        "Review !" + mr.number(), node -> {
                            String d = node.path("decision").asString("");
                            if (!Set.of("APPROVE", "REQUEST_CHANGES").contains(d)) throw new AgentRunner.InvalidOutput("decision must be APPROVE or REQUEST_CHANGES.");
                            if (node.path("criteria").size() != task.acceptanceCriteria().size()) {
                                throw new AgentRunner.InvalidOutput("criteria must have one entry per acceptance criterion (" + task.acceptanceCriteria().size() + ").");
                            }
                        });
            } catch (ModelCallException e) {
                boolean learned = perMinute == 0 && gateway.tokensPerMinute(reviewer.providerId(), reviewer.model()).isPresent();
                if (attempt > 0 || !learned) throw e;
            }
        }
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

    /** Lock files and build output: listed for the reviewer, never shown (they would fill the diff budget). */
    private static final java.util.regex.Pattern GENERATED = java.util.regex.Pattern.compile(
            "(^|/)(package-lock\\.json|npm-shrinkwrap\\.json|yarn\\.lock|pnpm-lock\\.yaml|bun\\.lockb|composer\\.lock|"
            + "Gemfile\\.lock|poetry\\.lock|Pipfile\\.lock|uv\\.lock|Cargo\\.lock|go\\.sum)$"
            + "|\\.min\\.(js|css)$|\\.map$|(^|/)(dist|build|node_modules|target|vendor)/");

    /**
     * The diff a reviewer reads: every changed file listed, generated files left out, and the room shared fairly
     * between the others so that one large file cannot hide the rest (a lock file once hid every source file).
     */
    String reviewDiff(Path repo, String base, String ref, List<GitService.FileChange> changed, int maxChars) {
        StringBuilder sb = new StringBuilder("<changed_files>\n");
        List<GitService.FileChange> shown = new ArrayList<>();
        for (GitService.FileChange c : changed) {
            boolean generated = GENERATED.matcher(c.path()).find();
            sb.append(c.status()).append(" ").append(c.path()).append(" +").append(c.added()).append(" -").append(c.removed())
                    .append(generated ? " (generated, not shown)" : "").append("\n");
            if (!generated) shown.add(c);
        }
        sb.append("</changed_files>\n");
        Map<String, String> diffs = new java.util.LinkedHashMap<>();
        for (GitService.FileChange c : shown) diffs.put(c.path(), git.diff(repo, base, ref, c.path()));
        // Smallest first: each file gets its size or an equal share of what is left, whichever is smaller.
        Map<String, Integer> room = new HashMap<>();
        int left = Math.max(0, maxChars - sb.length() - 100), files = diffs.size();
        for (Map.Entry<String, String> e : diffs.entrySet().stream().sorted(java.util.Comparator.comparingInt(x -> x.getValue().length())).toList()) {
            int share = Math.min(e.getValue().length(), left / Math.max(1, files--));
            room.put(e.getKey(), share);
            left -= share;
        }
        boolean cut = false;
        sb.append("<diff>\n");
        for (Map.Entry<String, String> e : diffs.entrySet()) {
            int r = room.get(e.getKey());
            cut |= r < e.getValue().length();
            sb.append(AgentTools.clip(e.getValue(), r)).append("\n");
        }
        sb.append("</diff>\n");
        if (cut) sb.append("Some files are cut to fit the model's limits: judge what you see and the tests.\n");
        return sb.toString();
    }

    /** The CTO reviews (no teammate available, or the CTO steps in). Approving accepts the criteria as reviewed. */
    public MergeRequest humanReview(String mrId, String userId, boolean approve, String comment) {
        MergeRequest submitted = mrs.find(mrId).orElseThrow(() -> ApiException.notFound("Merge request"));
        MergeRequest mr = submitted;
        Task task = tasks.find(submitted.taskId()).orElseThrow();
        TaskRun run = runs.find(submitted.runId()).orElseThrow();
        if ("CHANGES_REQUESTED".equals(submitted.status()) && Set.of("BLOCKED", "PAUSED").contains(run.status())) {
            // The work stopped on a refused review: the CTO can still judge the last submission, unchanged since.
            Path repo = git.repo(submitted.projectId()).orElseThrow();
            if (submitted.headCommit() != null && !submitted.headCommit().equals(git.revParse(repo, submitted.sourceBranch()))) {
                throw ApiException.conflict("changed_since", "The branch changed after its last submission: resume the task so the agent submits it again");
            }
            tx.executeWithoutResult(s -> {
                if ("BLOCKED".equals(task.status())) tasks.unblock(task.id(), Actor.human(userId));
                tasks.advance(task.id(), "IN_REVIEW", Actor.human(userId), Map.of("mrId", submitted.id()));
                runs.status(run, "SUBMITTED", null);
                runs.step(run, "PLATFORM", null, "The CTO reviews !" + submitted.number(), Map.of(), "OK", 0, 0, 0);
                agents.setResting(run.agentId(), "AVAILABLE", null);
                mrs.handToHuman(submitted.id());
            });
            mr = mrs.find(mrId).orElseThrow();
        } else if (!Set.of("IN_REVIEW", "AWAITING_APPROVAL").contains(mr.status())) {
            throw ApiException.conflict("not_in_review", "This merge request is not waiting for a review");
        }
        Task current = tasks.find(mr.taskId()).orElseThrow();
        List<Task.Criterion> criteria = current.acceptanceCriteria().stream()
                .map(c -> approve ? new Task.Criterion(c.text(), true, c.met() && c.evidence() != null ? c.evidence() : "accepted by the CTO in review") : c)
                .toList();
        List<String[]> comments = comment == null || comment.isBlank() ? List.of() : List.<String[]>of(new String[] {null, null, comment});
        concludeReview(mr, current, run, Actor.human(userId), "the CTO", approve, comment == null ? "" : comment, comments, criteria);
        // Reviewed from the merge request page: the arbitration card in the Inbox is settled with the same decision.
        jdbc.sql("SELECT id FROM approvals WHERE action = ? AND target_id = ? AND status = 'PENDING'").params(ARBITRATE_ACTION, mrId)
                .query(String.class).list()
                .forEach(id -> approvals.decide(runs.workspaceOf(run), id, userId, approve, comment));
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
                if (reviewer.kind() == Actor.Kind.AGENT && round >= MAX_REVIEW_ROUNDS) {
                    // No agreement between the reviewer and the author: the CTO arbitrates instead of the task blocking.
                    mrs.handToHuman(mr.id());
                    runs.status(run, "SUBMITTED", null);
                    StringBuilder why = new StringBuilder(summary == null ? "" : summary.strip());
                    comments.forEach(c -> why.append("\n- ").append(c[0] == null ? "" : c[0] + " ").append(c[2]));
                    criteria.stream().filter(c -> !c.met()).forEach(c -> why.append("\n- Not proven: ").append(c.text()));
                    runs.step(run, "PLATFORM", null, "No agreement with " + reviewerName + " after " + round
                            + " review rounds: the CTO decides on !" + mr.number(), Map.of("text", why.toString()), "OK", 0, 0, 0);
                    agents.setResting(run.agentId(), "AVAILABLE", null);
                    Map<String, Object> details = new HashMap<>();
                    details.put("number", mr.number());
                    details.put("taskId", task.id());
                    details.put("into", mr.targetBranch());
                    details.put("reviewer", reviewerName);
                    details.put("rounds", round);
                    details.put("remarks", AgentTools.clip(why.toString(), 3_000));
                    approvals.request(ws, mr.projectId(), reviewer, ARBITRATE_ACTION, "merge_request", mr.id(),
                            "Decide on !" + mr.number() + " " + mr.title() + ": " + reviewerName + " still requests changes after "
                                    + round + " rounds", details);
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
                jobs.enqueue(ws, mr.projectId(), driver(run), STEP, Map.of("runId", run.id()), run.id());
                return;
            }
            Map<String, Object> checks = gates(mr, task, criteria);
            boolean green = checks.values().stream().allMatch(v -> "ok".equals(((Map<?, ?>) v).get("status")));
            // Into develop the Lead's approval is the merge decision; the CTO decides for main and for the Lead's own work.
            boolean leadDecides = reviewer.kind() == Actor.Kind.AGENT && GitService.DEVELOP.equals(mr.targetBranch())
                    && agents.find(reviewer.id()).map(Agent::lead).orElse(false);
            boolean decided = reviewer.kind() == Actor.Kind.HUMAN || leadDecides;
            mrs.update(mr.id(), green ? "AWAITING_APPROVAL" : "IN_REVIEW", null, checks);
            runs.step(run, "PLATFORM", null, "Approved by " + reviewerName + (!green ? " · a gate is not green"
                            : decided ? " · merging into " + mr.targetBranch() : " · waiting for the CTO to merge into " + mr.targetBranch()),
                    Map.of("checks", checks), "OK", 0, 0, 0);
            if (!green) return;
            tasks.advance(task.id(), "VALIDATION", reviewer, Map.of("mrId", mr.id()));
            if (decided) {
                // The CTO, or the Lead into develop, reviewed and approved: that is the merge approval too.
                audit.record(ws, reviewer, MERGE_ACTION, "merge_request", mr.id(), AuditLog.Decision.APPROVED,
                        Map.of("number", String.valueOf(mr.number()), "into", mr.targetBranch()));
                Map<String, Object> job = new HashMap<>();
                job.put("mrId", mr.id());
                if (reviewer.kind() == Actor.Kind.HUMAN) job.put("userId", reviewer.id());
                jobs.enqueue(ws, mr.projectId(), run.agentId(), MERGE, job, mr.id());
            } else {
                String approvalId = approvals.request(ws, mr.projectId(), reviewer, MERGE_ACTION, "merge_request", mr.id(),
                        "Merge !" + mr.number() + " " + mr.title() + " into " + mr.targetBranch(),
                        Map.of("number", mr.number(), "branch", mr.sourceBranch(), "into", mr.targetBranch(), "taskId", task.id()));
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
        List<SecretScan.Finding> secrets = SecretScan.scan(git.diff(repo, mr.targetBranch(), mr.sourceBranch(), null));
        g.put("secrets", gate(secrets.isEmpty(), secrets.isEmpty() ? "clean" : secrets.size() + " finding(s)"));
        List<String> conflicts = git.conflicts(repo, mr.targetBranch(), mr.sourceBranch());
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
        if (ARBITRATE_ACTION.equals(e.action())) {
            // Approving accepts the work as it is (and merges it); declining sends the reason back to the author.
            MergeRequest mr = mrs.find(e.targetId()).orElse(null);
            if (mr == null || !"IN_REVIEW".equals(mr.status()) || mr.reviewerAgentId() != null) return; // decided elsewhere
            humanReview(mr.id(), e.userId(), e.approved(), e.approved() ? null
                    : e.reason() == null || e.reason().isBlank() ? "The CTO sides with the reviewer: address the remarks." : e.reason());
            return;
        }
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
        jobs.enqueue(e.workspaceId(), mr.projectId(), driver(run), STEP, Map.of("runId", run.id()), run.id());
    }

    /** Re-checks against the current target (tests again if it moved), merges, closes the task. */
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
        String target = mr.targetBranch();
        if (!git.revParse(repo, target).equals(git.revParse(wt, "HEAD")) && !baseIncluded(wt, target)) {
            GitService.MergeOutcome m = git.mergeBaseInto(wt, target, author(author));
            if (!m.clean()) {
                reopen(mr, task, run, target + " moved before the merge and now conflicts in " + String.join(", ", m.conflicts())
                        + ". Resolve the markers, test, commit and submit again.");
                return;
            }
            Optional<TestRun> again = tests.run(new TestRunService.Context(ws, mr.projectId(), task.id(), run.id(), mr.id(),
                    run.branch(), git.head(wt), "pre-merge"), ensureSandbox(run), wt);
            if (again.isPresent() && !again.get().succeeded()) {
                reopen(mr, task, run, "After bringing in the latest " + target + ", the tests fail (tr-" + again.get().number() + "):\n" + again.get().failingExcerpt());
                return;
            }
        }
        String message = "Merge !" + mr.number() + ": " + mr.title();
        String commit = GitService.DEVELOP.equals(target)
                ? git.mergeIntoDevelop(repo, mr.projectId(), run.branch(), message, GitService.Author.PLATFORM)
                : git.mergeIntoMain(repo, run.branch(), message, GitService.Author.PLATFORM);
        sandboxes.stop(run.sandboxId());
        tx.executeWithoutResult(s -> {
            mrs.markMerged(mr.id(), commit, userId);
            Actor merger = userId != null ? Actor.human(userId)
                    : mr.reviewerAgentId() != null ? Actor.agent(mr.reviewerAgentId()) : Actor.system();
            events.publish(ws, mr.projectId(), "merge_request.merged", merger, new Subject("merge_request", mr.id()),
                    Map.of("number", mr.number(), "commit", commit, "taskId", task.id(), "into", target));
            runs.step(run, "PLATFORM", null, "Merged into " + target + " as " + commit.substring(0, 7) + " · task done", Map.of("commit", commit), "OK", 0, 0, 0);
            runs.status(run, "DONE", null);
            tasks.complete(task.id(), Actor.system(), Map.of("mrId", mr.id(), "commit", commit));
            if (!runs.agentBusy(author.id())) agents.setResting(author.id(), "AVAILABLE", null);
        });
        git.removeWorktree(repo, wt);
        try {
            branches.deleteMergedTaskBranch(ws, mr.projectId(), run.branch(), target, mr.number());
        } catch (RuntimeException e) {
            log.warn("Could not delete the merged branch {}", run.branch(), e); // the merge itself is done
        }
        if (GitService.DEVELOP.equals(target)) branches.afterTaskMerged(ws, mr.projectId());
    }

    private boolean baseIncluded(Path wt, String base) {
        try {
            return git.changes(wt, "HEAD", base).isEmpty();
        } catch (GitService.GitException e) {
            return false;
        }
    }

    private void reopen(MergeRequest mr, Task task, TaskRun run, String text) {
        tx.executeWithoutResult(s -> {
            mrs.update(mr.id(), "CHANGES_REQUESTED", null, null);
            tasks.advance(task.id(), "IN_PROGRESS", Actor.system(), Map.of("mrId", mr.id()));
            runs.status(run, "RUNNING", null);
            runs.step(run, "PLATFORM", null, "Merge stopped: the target branch moved", Map.of("text", text), "ERROR", 0, 0, 0);
            agents.setResting(run.agentId(), "WORKING", null);
            jobs.enqueue(runs.workspaceOf(run), mr.projectId(), driver(run), STEP, Map.of("runId", run.id()), run.id());
        });
    }

    /** Removes sandboxes whose run is over (or unknown), e.g. after a restart. Branches and worktrees stay. */
    @Scheduled(fixedDelayString = "${aiw.execution.cleanup-ms:300000}", initialDelayString = "${aiw.execution.cleanup-delay-ms:60000}")
    void cleanup() {
        branches.deleteLeftoverMergedBranches();
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
        @Override public void onDeferred(AgentJob job, String reason) { exec.deferred(job, reason); }
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
