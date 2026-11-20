package dev.aiw.orchestration;

import dev.aiw.agentruntime.AgentJob;
import dev.aiw.agentruntime.AgentJobs;
import dev.aiw.agentruntime.AgentRunner;
import dev.aiw.agentruntime.JobHandler;
import dev.aiw.events.EventPublisher;
import dev.aiw.events.Subject;
import dev.aiw.governance.AuditLog;
import dev.aiw.organization.Agent;
import dev.aiw.organization.AgentChatService;
import dev.aiw.organization.AgentService;
import dev.aiw.scm.GitService;
import dev.aiw.shared.Actor;
import dev.aiw.shared.ApiException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Mode A (CTO requirement, 2026-11-18, docs/16): when an agent blocks its task, the Lead decides first. It answers the
 * agent and resumes the work, rewrites the task, has it split, or escalates to the CTO in its chat when the question
 * is the CTO's (a product choice, an approval, money). One short call on the Lead's model, with only what the block
 * is about: the task, the reason, the last steps of the work, the code committed and the files the reason names.
 * From its fourth failure on the same task the Lead takes the task over and works it itself ({@link #TAKEOVER_AFTER}).
 */
@Service
public class LeadUnblocker {
    static final String UNBLOCK = "LEAD_UNBLOCK";
    /**
     * The Lead's ladder on one task: the first {@link #PATCHES} decisions may fix or answer; then the task itself is the
     * problem (seen on T-28: three patches while the tests needed a library that was not there), so only a rewrite, a
     * split, or a takeover; from the fourth failure, a takeover. A decision the CTO asks for starts a fresh count.
     */
    static final int PATCHES = 2;
    /**
     * At its {@code TAKEOVER_AFTER + 1}th failure the Lead takes the task over and works it itself (CTO requirement,
     * 2026-11-20: "if a task fails 4 times the Lead must take over and repair it so the agent goes on"). At most
     * {@link #MAX_TAKEOVERS} takeovers before the CTO is asked.
     */
    static final int TAKEOVER_AFTER = 3;
    static final int MAX_TAKEOVERS = 2;
    private static final Set<String> DECISIONS = Set.of("fix", "answer", "rewrite", "split", "escalate", "takeover");
    /** Decisions that answer a failure of the agent (escalations and the ends of a takeover are not). */
    private static final String FAILURES = "('fix', 'answer', 'rewrite', 'restore_tests', 'restore_parsing', 'restore_regressed', 'takeover')";
    /** File paths in a block reason ("tests/lint.test.js", "package.json"). */
    private static final Pattern PATH = Pattern.compile("(?<![\\w/.-])((?:[\\w.-]+/)*[\\w.-]+\\.(?:js|mjs|cjs|ts|tsx|jsx|json|py|java|md|css|html|yml|yaml|toml|txt))\\b");

    private final TaskService tasks;
    private final TaskRuns runs;
    private final TaskExecution execution;
    private final AgentService agents;
    private final AgentChatService chat;
    private final AgentRunner runner;
    private final GitService git;
    private final EventPublisher events;
    private final AuditLog audit;
    private final TransactionTemplate tx;
    private final JdbcClient jdbc;
    private final AgentJobs jobs;

    LeadUnblocker(TaskService tasks, TaskRuns runs, TaskExecution execution, AgentService agents, AgentChatService chat,
                  AgentRunner runner, GitService git, EventPublisher events, AuditLog audit, TransactionTemplate tx,
                  JdbcClient jdbc, AgentJobs jobs) {
        this.jobs = jobs;
        this.tasks = tasks;
        this.runs = runs;
        this.execution = execution;
        this.agents = agents;
        this.chat = chat;
        this.runner = runner;
        this.git = git;
        this.events = events;
        this.audit = audit;
        this.tx = tx;
        this.jdbc = jdbc;
    }

    /** The CTO hands a blocked task to the Lead (a block from before docs/16, or after answering it in the chat). */
    public void request(String taskId, String userId) {
        Task task = tasks.find(taskId).orElseThrow(() -> ApiException.notFound("Task"));
        TaskRun run = runs.openForTask(taskId).filter(r -> "BLOCKED".equals(r.status()))
                .orElseThrow(() -> ApiException.conflict("not_blocked", "No blocked work on this task"));
        Agent lead = agents.lead(task.projectId()).filter(l -> "ACTIVE".equals(l.status()) && l.model() != null)
                .orElseThrow(() -> ApiException.conflict("no_lead", "The project has no active Lead with a model"));
        if (lead.id().equals(run.agentId())) throw ApiException.conflict("lead_task", "This is the Lead's own task: it is yours to decide");
        String ws = tasks.workspaceOf(task);
        Actor actor = Actor.human(userId);
        tx.executeWithoutResult(s -> {
            jobs.enqueue(ws, task.projectId(), lead.id(), UNBLOCK, Map.of("taskId", task.id(), "runId", run.id(),
                    "reason", run.blockedReason() == null ? "Blocked" : run.blockedReason(), "byCto", "true"), task.id());
            audit.record(ws, actor, "task.lead_decision_requested", "task", task.id(), AuditLog.Decision.RECORDED,
                    Map.of("number", String.valueOf(task.number())));
            events.publish(ws, task.projectId(), "task.lead_decision_requested", actor, new Subject("task", task.id()),
                    Map.of("number", task.number()));
        });
    }

    void decide(AgentJob job) {
        Task task = tasks.find(job.string("taskId")).orElse(null);
        TaskRun run = runs.find(job.string("runId")).orElse(null);
        // Someone already acted (the CTO resumed it, cancelled it, split it): nothing left to decide.
        if (task == null || run == null || !"BLOCKED".equals(task.status()) || !"BLOCKED".equals(run.status())) return;
        Agent lead = runner.agent(job);
        Agent worker = agents.find(run.agentId()).orElseThrow();
        String reason = job.string("reason");

        // Out of budget: the task is cut into smaller ones that go on from its branch (a task never goes past its
        // budget, CTO requirement 2026-11-20). Also when the Lead's takeover used up what was left.
        if (TaskExecution.budgetExhausted(run)) {
            splitForBudget(task, run, lead, reason);
            return;
        }
        // The takeover failed on tests develop passes (T-29): the branch's version of the files they need is what is
        // wrong. Develop's version comes back and the agent redoes only its change on top of it.
        if ("true".equals(job.string("afterTakeover")) && run.worktree() != null) {
            List<Regressions.Regression> regressions = Regressions.find(git, Path.of(run.worktree()),
                    reason + "\n" + latestFailedOutput(runs.steps(run.id())));
            List<String> restored = regressions.isEmpty() ? List.of() : Regressions.restore(git, Path.of(run.worktree()), regressions);
            if (!restored.isEmpty()) {
                String files = String.join(", ", restored);
                deliver(task, run, lead, worker, reason, "restore_regressed",
                        "Changes to " + files + " broke tests that pass on develop.",
                        "Your changes to " + files + " broke tests that come unchanged from develop, where they pass. The "
                        + "platform put back develop's version of " + files + ". Read it (file.read), then add only what your "
                        + "task needs on top of it: keep every export and behaviour it has. Run the tests after each change.");
                return;
            }
        }
        // The Lead worked the task itself and could not finish: the CTO decides, no new round.
        if ("true".equals(job.string("afterTakeover"))) {
            escalate(task, lead, reason, "I took over T-" + task.number() + " myself and could not finish it. What I changed is "
                    + "committed on its branch. Decide: resume " + worker.name() + ", rewrite or split the task, or cancel it.",
                    "takeover_failed");
            return;
        }
        long earlier = decisionsSinceCto(task.id());
        boolean byCto = "true".equals(job.string("byCto"));
        boolean forced = "true".equals(job.string("takeover")); // the CTO's order "take it over" (or the platform's)
        if (earlier >= TAKEOVER_AFTER || forced) {
            long takeovers = takeoversSinceCto(task.id());
            if (takeovers >= MAX_TAKEOVERS) {
                escalate(task, lead, reason, "I already took this task over " + takeovers + " times and it is blocked again: "
                        + "it needs your decision.", "limit");
                return;
            }
            // Too little left for the Lead to work it: cut it instead.
            if ((run.tokenBudget() - run.tokens()) * 4 < run.tokenBudget()) {
                splitForBudget(task, run, lead, reason);
                return;
            }
            execution.takeOver(task, run, lead, forced ? "asked to take it over"
                    : worker.name() + " failed " + (earlier + 1) + " times", reason);
            return;
        }

        // Code decides first: a test the branch rewrote and that now fails goes back to its develop version (T-16).
        List<String> restored = restoreChangedTests(run, reason + "\n" + latestFailedOutput(runs.steps(run.id())));
        if (!restored.isEmpty()) {
            String files = String.join(", ", restored);
            deliver(task, run, lead, worker, reason, "restore_tests",
                    "The branch changed " + files + " from develop and it failed.",
                    "The platform restored " + files + " to its develop version: tests from develop are requirements other "
                    + "work relies on, never rewrite them to make your task pass. Run the tests: if one still fails, fix "
                    + "the code of your task, not that test. To prove your own criteria, add a new test file.");
            return;
        }

        // A file that does not parse goes back to its last version that does, found in its history (T-28: the Lead
        // could not find the stray brace of app.js in three tries, and failing blocked the Lead itself).
        String syntax = execution.syntaxScan(run);
        List<String> reparsed = restoreParsingVersions(run, syntax);
        if (!reparsed.isEmpty()) {
            deliver(task, run, lead, worker, reason, "restore_parsing", syntax.strip(),
                    "These files did not parse, so every test importing them failed:\n" + syntax
                    + "The platform restored each one to its last version that parses: " + String.join("; ", reparsed)
                    + ". Changes made to them after that commit are undone. Read them (file.read), check your task's "
                    + "criteria and redo only what is missing, one small edit at a time, then run the tests.");
            return;
        }

        boolean patchesSpent = !byCto && earlier >= PATCHES;

        List<String> shown = new ArrayList<>();
        String prompt = prompt(task, run, worker, reason, shown);
        if (patchesSpent) {
            prompt += "\n\nYou already settled " + earlier + " blocks on this task with fixes or answers and it is blocked "
                    + "again. Decide \"takeover\" (you do the work yourself), \"rewrite\" (a smaller scope its tests can prove "
                    + "with what is installed), \"split\", or \"escalate\". \"fix\" and \"answer\" are not accepted any more.";
        }
        JsonNode out = runner.completeJson(job, lead, runner.context(lead, task.title() + " " + reason, 2_000, 1_500).oneShot(),
                prompt, 4_000, "medium", "Unblock T-" + task.number(), node -> {
                    validate(node);
                    if (patchesSpent && Set.of("fix", "answer").contains(LeadService.text(node, "decision"))) {
                        throw new AgentRunner.InvalidOutput("Decide takeover, rewrite, split or escalate: fixes did not settle this task.");
                    }
                    if ("fix".equals(LeadService.text(node, "decision")) && shown.isEmpty()) {
                        // T-23: a fix with no file in the prompt cannot be checked or applied.
                        throw new AgentRunner.InvalidOutput("No file is shown, so \"fix\" is not possible here: decide "
                                + "\"answer\" (tell the agent exactly what to do), \"rewrite\" or \"split\".");
                    }
                    for (JsonNode e : node.path("edits")) {
                        if (!shown.contains(LeadService.text(e, "path"))) {
                            throw new AgentRunner.InvalidOutput("Edit only the files shown: " + String.join(", ", shown) + ".");
                        }
                    }
                    if ("fix".equals(LeadService.text(node, "decision")) && run.worktree() != null) {
                        String refusal = commentedOut(node.path("edits"), Path.of(run.worktree()));
                        if (refusal != null) throw new AgentRunner.InvalidOutput(refusal);
                        // Seen on T-16: the Lead "fixed" an id the file already had, twice; the test kept failing.
                        Map<Path, String> changed;
                        try {
                            changed = edited(Path.of(run.worktree()), node.path("edits"));
                        } catch (IllegalArgumentException | java.io.IOException e) {
                            throw new AgentRunner.InvalidOutput("These edits cannot apply: " + e.getMessage());
                        }
                        if (changed.isEmpty()) {
                            throw new AgentRunner.InvalidOutput("These edits change nothing: the files already read so. "
                                    + "The cause is elsewhere; look at the failing test itself.");
                        }
                        String broken = syntaxAfter(run, changed);
                        if (broken != null) throw new AgentRunner.InvalidOutput(broken);
                    }
                });
        if ("fix".equals(LeadService.text(out, "decision"))) {
            String failure = applyFix(run, lead, out.path("edits"));
            if (failure != null) {
                escalate(task, lead, reason, "My fix could not be applied (" + failure + "). It needs your decision.", "fix_failed");
                return;
            }
        }
        apply(task, run, lead, worker, reason, out);
    }

    /**
     * The Lead's decisions on the task since the CTO last stepped in (asked the Lead, resumed, unblocked or changed the
     * task): each intervention starts a fresh ladder. Escalations are not decisions: they hand the task to the CTO.
     */
    long decisionsSinceCto(String taskId) {
        // Every failure counts, the platform's own repairs too (T-28 looped through restores as well); a hand-back
        // after a takeover starts a fresh count, as the CTO stepping in does.
        return jdbc.sql("""
                SELECT count(*) FROM events d
                WHERE d.type = 'task.lead_decided' AND d.subject_id = :task
                  AND d.payload->>'decision' IN %s
                  AND d.seq > GREATEST(
                        COALESCE((SELECT max(h.seq) FROM events h WHERE h.subject_id = :task AND h.actor_kind = 'HUMAN'), 0),
                        COALESCE((SELECT max(b.seq) FROM events b WHERE b.subject_id = :task AND b.type = 'task.lead_decided'
                                    AND b.payload->>'decision' = 'handback'), 0))
                """.formatted(FAILURES)).param("task", taskId).query(Long.class).single();
    }

    /** The Lead's takeovers of the task since the CTO last stepped in. */
    long takeoversSinceCto(String taskId) {
        return jdbc.sql("""
                SELECT count(*) FROM events d
                WHERE d.type = 'task.lead_decided' AND d.subject_id = :task AND d.payload->>'decision' = 'takeover'
                  AND d.seq > COALESCE((SELECT max(h.seq) FROM events h WHERE h.subject_id = :task AND h.actor_kind = 'HUMAN'), 0)
                """).param("task", taskId).query(Long.class).single();
    }

    /** The Lead's last decisions on the task (escalations aside), oldest first, in one line each. */
    List<String> earlierDecisions(String taskId) {
        List<String> out = new ArrayList<>(jdbc.sql("""
                SELECT payload->>'decision' || ': ' || COALESCE(NULLIF(payload->>'message', ''), payload->>'why', '')
                FROM events WHERE type = 'task.lead_decided' AND subject_id = :task AND payload->>'decision' <> 'escalate'
                ORDER BY seq DESC LIMIT 6
                """).param("task", taskId).query(String.class).list());
        java.util.Collections.reverse(out);
        return out.stream().map(d -> clip(d, 300)).toList();
    }

    String prompt(Task task, TaskRun run, Agent worker, String reason) {
        return prompt(task, run, worker, reason, new ArrayList<>());
    }

    /** {@code shown}: receives the paths of the files put in the prompt (the only ones a "fix" may edit). */
    String prompt(Task task, TaskRun run, Agent worker, String reason, List<String> shown) {
        StringBuilder sb = new StringBuilder();
        sb.append(worker.name()).append(" (").append(worker.title()).append(") stopped T-").append(task.number())
                .append(" and needs a decision. You lead the team: settle it yourself whenever you can; escalate only what ")
                .append("is the CTO's to decide.\n\n");
        sb.append("<task number=\"T-").append(task.number()).append("\">\n").append(task.title()).append("\n");
        if (!task.description().isBlank()) sb.append(clip(task.description().strip(), 2_000)).append("\n");
        sb.append("Acceptance criteria:\n");
        for (int i = 0; i < task.acceptanceCriteria().size(); i++) {
            sb.append(i + 1).append(". ").append(task.acceptanceCriteria().get(i).text()).append("\n");
        }
        sb.append("</task>\n<blocked_because>\n").append(clip(reason, 2_000)).append("\n</blocked_because>\n");

        List<RunStep> steps = runs.steps(run.id());
        sb.append("<last_steps>\n");
        steps.stream().skip(Math.max(0, steps.size() - 12)).forEach(st -> sb.append("- ").append(st.kind())
                .append(st.tool() == null ? "" : " " + st.tool()).append(" ").append(st.outcome()).append(": ")
                .append(clip(st.summary(), 200)).append("\n"));
        sb.append("</last_steps>\n");
        // Seen on T-16: without them the Lead made the same fix and the same rewrite again, round after round.
        List<String> earlier = earlierDecisions(task.id());
        if (!earlier.isEmpty()) {
            sb.append("<your_earlier_decisions on=\"this task\" outcome=\"it is blocked again\">\n");
            earlier.forEach(d -> sb.append("- ").append(d).append("\n"));
            sb.append("</your_earlier_decisions>\n");
        }
        // The latest test or command output says more than any summary.
        String failedOutput = "";
        for (int i = steps.size() - 1; i >= Math.max(0, steps.size() - 12); i--) {
            RunStep st = steps.get(i);
            Map<String, Object> d = st.detail() == null ? Map.of() : st.detail();
            // A failed command's output, or the platform's test run on submission ("Tests failed on submission").
            Object text = d.get("output") != null ? d.get("output") : st.summary().startsWith("Tests ") ? d.get("text") : null;
            if (text instanceof String o && !o.isBlank() && !"OK".equals(st.outcome())) {
                failedOutput = dev.aiw.quality.TestOutput.failingExcerpt(o, 40);
                sb.append("<latest_failed_output>\n").append(tail(failedOutput, 2_500)).append("\n</latest_failed_output>\n");
                break;
            }
        }

        Path worktree = run.worktree() == null ? null : Path.of(run.worktree());
        if (worktree != null && Files.isDirectory(worktree)) {
            // A file that does not parse fails every test that imports it, at the test's location (T-28: app.js).
            String syntax = execution.syntaxScan(run);
            if (!syntax.isEmpty()) {
                sb.append("<syntax_errors found_by=\"platform\">\n").append(syntax).append("</syntax_errors>\n");
                failedOutput = syntax + "\n" + failedOutput;
            }
            String regressed = Regressions.explain(Regressions.find(git, worktree, failedOutput + "\n" + reason), true);
            if (!regressed.isEmpty()) {
                sb.append("<regressions found_by=\"platform\">\n").append(regressed).append("</regressions>\n");
            }
            String broken = AgentTools.testFileProblems(worktree);
            if (!broken.isEmpty()) {
                sb.append("<test_file_problems found_by=\"platform\">\n").append(broken).append("</test_file_problems>\n");
                failedOutput = failedOutput + "\n" + broken; // their files are shown below, numbered
            }
            List<String> candidates = new ArrayList<>(named(failedOutput + "\n" + reason));
            Set<String> onBranch = new LinkedHashSet<>();
            try {
                String base = git.integrationBranch(worktree);
                // The files the branch changed: a failing test often names only itself, not the code at fault.
                git.changes(worktree, base, "HEAD").stream().map(GitService.FileChange::path)
                        .filter(p -> !p.contains("lock") && !p.startsWith("node_modules/"))
                        .forEach(onBranch::add);
                onBranch.stream().filter(p -> !candidates.contains(p)).forEach(candidates::add);
            } catch (RuntimeException e) {
                // No commit yet on the branch: the files named in the failure still show the state.
            }
            int budget = 9_000;
            for (String path : candidates) {
                Path file = worktree.resolve(path).normalize();
                if (!file.startsWith(worktree) || !Files.isRegularFile(file) || budget <= 0) continue;
                try {
                    // Numbered as file.read shows them: a "fix" decision edits by these numbers.
                    List<String> lines = Files.readAllLines(file);
                    StringBuilder numbered = new StringBuilder();
                    for (int i = 0; i < lines.size() && numbered.length() < Math.min(budget, 6_000); i++) {
                        numbered.append(i + 1).append(": ").append(lines.get(i)).append('\n');
                    }
                    // A test this branch wrote may be the thing that is wrong (T-16); one from develop is a requirement.
                    String origin = !TestFileLint.isTestFile(path) ? ""
                            : onBranch.contains(path) ? " test=\"written or changed on this branch\"" : " test=\"from develop\"";
                    sb.append("<file path=\"").append(path).append("\" lines=\"").append(lines.size()).append("\"")
                            .append(origin).append(">\n")
                            .append(numbered).append("</file>\n");
                    shown.add(path);
                    budget -= numbered.length();
                } catch (Exception e) {
                    // unreadable (binary, permissions): skip it
                }
            }
        }
        sb.append("""

                Decide one of:
                - "fix": the cause is in the files above and you can correct it in a few lines: give "edits", each \
                {"path", "from", "to", "new"} with the line numbers shown above ("to" = "from" - 1 inserts), and a \
                "message" telling the agent what you changed. The platform applies and commits them; the agent runs the tests.
                - "answer": you know what to do. "message" is the instruction the agent follows at its next step \
                (concrete: what to change, where); the work resumes.
                - "rewrite": the task itself is wrong or unclear. Give the fields that change in "task" \
                ({"title", "description", "criteria"}), and a "message" for the agent; the work resumes on the new version.
                - "split": the task is too big for its budget. "guidance" says how to cut it.
                - "takeover": the agent cannot do it (its edits keep failing, it repeats the same mistake): you work on \
                its branch yourself, with the tools and the tests, then submit or hand the work back to it.
                - "escalate": only for what is the CTO's to decide (a product or scope choice the brief does not settle, \
                an approval, a credential, money, an external access). "question" is what the CTO must decide, short, \
                with the options and your recommendation.
                Never escalate a technical question you can answer from the code above.

                Prefer "fix" for a failing test or a syntax error you can see, then "answer". A file listed in \
                <syntax_errors> comes first: fix it there, whatever the tests say.
                The failing test may be the thing that is wrong, above all one written on this branch: it may test \
                outside the task, repeat another test, or be unable to run (e.g. jsdom from a file URL has no \
                localStorage). Then fix the test or empty it, rather than the code. A test from develop is a requirement: \
                never rewrite the task to forbid what it needs. Never repeat one of your earlier decisions: it did not settle it.

                JSON shape: {"decision": "fix", "why": "one sentence", "edits": [...], "message": "...", "task": {...}, \
                "guidance": "...", "question": "..."}""");
        return sb.toString();
    }

    static void validate(JsonNode node) {
        String decision = LeadService.text(node, "decision");
        if (!DECISIONS.contains(decision)) {
            throw new AgentRunner.InvalidOutput("\"decision\" must be fix, answer, rewrite, split, takeover or escalate.");
        }
        if ("fix".equals(decision)) {
            JsonNode edits = node.path("edits");
            if (!edits.isArray() || edits.isEmpty() || edits.size() > 8) throw new AgentRunner.InvalidOutput("\"edits\" must list 1 to 8 edits.");
            for (JsonNode e : edits) {
                if (LeadService.text(e, "path").isBlank() || e.path("from").asInt(0) < 1 || !e.has("new")) {
                    throw new AgentRunner.InvalidOutput("Each edit is {\"path\", \"from\", \"to\", \"new\"} with line numbers from the files shown.");
                }
            }
        }
        if (Set.of("fix", "answer", "rewrite").contains(decision) && LeadService.text(node, "message").isBlank()) {
            throw new AgentRunner.InvalidOutput("\"message\" (the instruction for the agent) is required.");
        }
        if ("rewrite".equals(decision) && !node.path("task").isObject()) {
            throw new AgentRunner.InvalidOutput("\"task\" must give the fields that change.");
        }
        if ("escalate".equals(decision) && LeadService.text(node, "question").isBlank()) {
            throw new AgentRunner.InvalidOutput("\"question\" (what the CTO must decide) is required.");
        }
    }

    private void apply(Task task, TaskRun run, Agent lead, Agent worker, String reason, JsonNode out) {
        String decision = LeadService.text(out, "decision");
        String why = clip(LeadService.text(out, "why"), 500);
        Actor actor = Actor.agent(lead.id());
        String ws = tasks.workspaceOf(task);
        switch (decision) {
            case "fix", "answer", "rewrite" -> {
                if ("rewrite".equals(decision)) {
                    JsonNode t = out.path("task");
                    String title = LeadService.text(t, "title");
                    String description = LeadService.text(t, "description");
                    List<String> criteria = t.has("criteria") ? LeadService.strings(t.path("criteria")).stream().limit(LeadService.MAX_CRITERIA).toList() : null;
                    tasks.update(task.id(), new TaskService.Draft(title.isBlank() ? null : clip(title, 200),
                            description.isBlank() ? null : description, criteria, null, null, null), actor);
                }
                deliver(task, run, lead, worker, reason, decision, why, clip(LeadService.text(out, "message"), 6_000));
            }
            case "takeover" -> {
                if (takeoversSinceCto(task.id()) >= MAX_TAKEOVERS) {
                    escalate(task, lead, reason, "I already took this task over " + MAX_TAKEOVERS + " times: it needs your decision.", "limit");
                } else {
                    execution.takeOver(task, run, lead, why.isBlank() ? "the Lead does it itself" : why, reason);
                }
            }
            case "split" -> {
                tx.executeWithoutResult(s -> recorded(ws, task, actor, decision, why, Map.of()));
                // The split job runs with the Lead's rights; it cancels this task and its run.
                tx.executeWithoutResult(s -> jobs.enqueue(ws, task.projectId(), lead.id(), TaskSplitter.SPLIT,
                        Map.of("taskId", task.id(), "guidance", clip(LeadService.text(out, "guidance"), 5_000)), task.id()));
            }
            default -> escalate(task, lead, reason, clip(LeadService.text(out, "question"), 3_000), "escalate");
        }
    }

    /** Splits of splits a task may come from before the CTO is asked: past it, cutting again did not help. */
    static final int MAX_SPLIT_DEPTH = 2;

    /**
     * The task reached its budget, or has too little left: the Lead cuts what remains into smaller tasks, each to
     * stay well under the budget, the first one continuing the branch. A task already cut twice goes to the CTO.
     */
    private void splitForBudget(Task task, TaskRun run, Agent lead, String reason) {
        int depth = splitDepth(task.id());
        if (depth >= MAX_SPLIT_DEPTH) {
            escalate(task, lead, reason, "T-" + task.number() + " comes from " + depth + " splits already and reached its budget of "
                    + run.tokenBudget() + " tokens again: cutting it further does not help. Decide: rewrite its scope, or cancel it.",
                    "split_limit");
            return;
        }
        String ws = tasks.workspaceOf(task);
        Actor actor = Actor.agent(lead.id());
        String guidance = "T-" + task.number() + " used " + run.tokens() + " of its " + run.tokenBudget() + " tokens without "
                + "finishing. Keep what is committed: the first new task finishes it on the same branch. Cut the rest into "
                + "the smallest tasks possible, each needing at most half of " + run.tokenBudget() + " tokens.";
        tx.executeWithoutResult(s -> {
            recorded(ws, task, actor, "split", "Budget reached (" + run.tokens() + " of " + run.tokenBudget() + " tokens)", Map.of());
            jobs.enqueue(ws, task.projectId(), lead.id(), TaskSplitter.SPLIT, Map.of("taskId", task.id(), "guidance", guidance), task.id());
        });
    }

    /** How many splits the task descends from (a task made by a split of a split: 2). */
    int splitDepth(String taskId) {
        return jdbc.sql("""
                WITH RECURSIVE chain(id, source_kind, source_id, depth) AS (
                    SELECT id, source_kind, source_id, 0 FROM tasks WHERE id = :id
                    UNION ALL
                    SELECT t.id, t.source_kind, t.source_id, c.depth + 1 FROM tasks t JOIN chain c ON t.id = c.source_id
                    WHERE c.source_kind = 'SPLIT' AND c.depth < 10)
                SELECT max(depth) FROM chain
                """).param("id", taskId).query(Integer.class).single();
    }

    /** The message goes to the agent and the work resumes, now or as soon as the agent is free. */
    private void deliver(Task task, TaskRun run, Agent lead, Agent worker, String reason, String decision, String why,
                         String message) {
        Actor actor = Actor.agent(lead.id());
        String ws = tasks.workspaceOf(task);
        tx.executeWithoutResult(s -> execution.leadMessage(task, run, lead, message));
        try {
            if (runs.agentBusyElsewhere(run.agentId(), run.id())) {
                // The agent is on another task: this one waits its turn, nobody needs to be asked.
                tx.executeWithoutResult(st -> execution.resumeWhenFree(run));
            } else {
                execution.resume(task.id(), actor);
            }
        } catch (ApiException e) {
            // Cannot resume (budget spent): the answer waits for the agent, the CTO decides.
            escalate(task, lead, reason, "I answered " + worker.name() + " (\"" + clip(message, 300) + "\") but the work "
                    + "cannot resume: " + e.getMessage() + ".", decision);
            return;
        }
        tx.executeWithoutResult(s -> recorded(ws, task, actor, decision, why, Map.of("message", clip(message, 1_000))));
    }

    /**
     * Test files the failure names that existed when the branch left develop and that the branch changed: back to
     * their develop version, committed by the platform. On T-16 an agent whose task was an HTML skeleton rewrote
     * tests/app.test.js into a test that could not run; no fix of the code could pass it, the develop version passed.
     * A test the branch added is left to the Lead (it may be the task's proof).
     */
    List<String> restoreChangedTests(TaskRun run, String failure) {
        return run.worktree() == null ? List.of() : restoreChangedTests(git, Path.of(run.worktree()), failure);
    }

    static List<String> restoreChangedTests(GitService git, Path worktree, String failure) {
        if (!Files.isDirectory(worktree)) return List.of();
        try {
            String base = git.mergeBase(worktree, git.integrationBranch(worktree), "HEAD");
            List<String> restored = new ArrayList<>();
            for (String path : named(failure)) {
                Path file = worktree.resolve(path).normalize();
                if (!TestFileLint.isTestFile(path) || !file.startsWith(worktree)) continue;
                var original = git.show(worktree, base, path);
                if (original.isEmpty()) continue;
                if (Files.isRegularFile(file) && Files.readString(file).equals(original.get())) continue;
                Files.createDirectories(file.getParent());
                Files.writeString(file, original.get());
                restored.add(path);
            }
            if (!restored.isEmpty()) {
                git.commitAll(worktree, "Restore " + String.join(", ", restored) + " from develop", GitService.Author.PLATFORM);
            }
            return restored;
        } catch (RuntimeException | java.io.IOException e) {
            return List.of();
        }
    }

    /**
     * Seen on T-28 (2026-11-19): the Lead "fixed" app.js by replacing real code with "// Fix unfinished template literal
     * here". An edit whose new lines are only comments or blank, over lines that held code, is not a fix.
     */
    static String commentedOut(JsonNode edits, Path worktree) {
        for (JsonNode e : edits) {
            String added = e.path("new").asString("");
            boolean onlyComments = added.lines().map(String::strip)
                    .allMatch(l -> l.isEmpty() || l.startsWith("//") || l.startsWith("/*") || l.startsWith("*") || l.startsWith("#"));
            if (!onlyComments) continue;
            int from = e.path("from").asInt(), to = e.path("to").asInt(from);
            if (to < from) continue; // an insertion of a comment removes nothing
            try {
                Path file = worktree.resolve(LeadService.text(e, "path")).normalize();
                if (!file.startsWith(worktree) || !Files.isRegularFile(file)) continue;
                List<String> lines = Files.readAllLines(file);
                boolean hadCode = lines.subList(Math.max(0, from - 1), Math.min(lines.size(), to)).stream().map(String::strip)
                        .anyMatch(l -> !l.isEmpty() && !l.startsWith("//") && !l.startsWith("/*") && !l.startsWith("*") && !l.startsWith("#"));
                if (hadCode && !added.isBlank()) {
                    return "Lines " + from + "–" + to + " of " + LeadService.text(e, "path") + " hold code and your \"new\" is only "
                            + "comments: that deletes the code instead of fixing it. Give the corrected code itself.";
                }
            } catch (java.io.IOException ex) {
                // unreadable: the other checks decide
            }
        }
        return null;
    }

    /**
     * The Lead's fix goes through the same syntax check as an agent's edit (it did not before: on T-28 two "Fix by
     * the Lead" commits left app.js unparseable for a day). The new contents are written, checked in the sandbox and
     * put back; the refusal says where the error is.
     */
    private String syntaxAfter(TaskRun run, Map<Path, String> changed) {
        Path worktree = Path.of(run.worktree());
        for (var c : changed.entrySet()) {
            String rel = worktree.relativize(c.getKey()).toString().replace('\\', '/');
            if (rel.endsWith(".json")) {
                try {
                    new tools.jackson.databind.json.JsonMapper().readTree(c.getValue());
                } catch (tools.jackson.core.JacksonException e) {
                    return "Your edit leaves " + rel + " as invalid JSON: " + e.getOriginalMessage() + ". Correct the edit.";
                }
                continue;
            }
            if (!rel.matches(".*\\.(js|mjs|cjs)$")) continue;
            try {
                String before = Files.readString(c.getKey());
                Object[] err;
                try {
                    Files.writeString(c.getKey(), c.getValue());
                    err = execution.nodeCheck(run, rel);
                } finally {
                    Files.writeString(c.getKey(), before);
                }
                if (err != null) {
                    int line = (Integer) err[0];
                    List<String> lines = c.getValue().lines().toList();
                    return "Your edit leaves a SYNTAX ERROR in " + rel + (line > 0 ? " at line " + line : "") + ": " + err[1]
                            + ". The file after your edit:\n" + (line > 0 ? LineEdits.numbered(lines, line - 1, 5) : "")
                            + "Correct the edit (line numbers are those of the file shown before your edit).";
                }
            } catch (java.io.IOException e) {
                // cannot check: the tests will
            }
        }
        return null;
    }

    private static final Pattern SCANNED = Pattern.compile("(?m)^- ([\\w./@+-]+?\\.(?:js|mjs|cjs))(?: line \\d+)?: ");

    /**
     * Each file the syntax scan lists, back to the newest version in its history (on the branch, then where the branch
     * left develop) that node can parse, committed by the platform. Returns "path (commit subject)" per restored file.
     */
    /** The files a syntax scan lists. */
    static List<String> scannedFiles(String syntax) {
        List<String> out = new ArrayList<>();
        Matcher m = SCANNED.matcher(syntax == null ? "" : syntax);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    List<String> restoreParsingVersions(TaskRun run, String syntax) {
        if (syntax == null || syntax.isBlank() || run.worktree() == null) return List.of();
        Path worktree = Path.of(run.worktree());
        List<String> restored = new ArrayList<>();
        for (String path : scannedFiles(syntax)) {
            Path file = worktree.resolve(path).normalize();
            if (!file.startsWith(worktree) || !Files.isRegularFile(file)) continue;
            try {
                String current = Files.readString(file);
                List<GitService.Commit> history = new ArrayList<>(git.fileHistory(worktree, "HEAD", path, 30));
                try {
                    String base = git.mergeBase(worktree, git.integrationBranch(worktree), "HEAD");
                    history.add(new GitService.Commit(base, "", "", "the version on develop"));
                } catch (RuntimeException e) {
                    // no integration branch: the branch history only
                }
                String found = null;
                for (GitService.Commit c : history) {
                    var content = git.show(worktree, c.sha(), path);
                    if (content.isEmpty() || content.get().equals(current)) continue;
                    Files.writeString(file, content.get());
                    if (execution.nodeCheck(run, path) == null) {
                        found = path + " from " + c.sha().substring(0, 7) + " (\"" + c.subject() + "\")";
                        break;
                    }
                }
                if (found == null) Files.writeString(file, current);
                else restored.add(found);
            } catch (java.io.IOException | RuntimeException e) {
                // cannot restore this one: the Lead decides
            }
        }
        if (!restored.isEmpty()) {
            git.commitAll(worktree, "Restore the last version that parses of " + restored.stream()
                    .map(r -> r.substring(0, r.indexOf(' '))).reduce((a, b) -> a + ", " + b).orElse(""), GitService.Author.PLATFORM);
        }
        return restored;
    }

    /** The latest failed command or submission test output among the last steps, or "". */
    static String latestFailedOutput(List<RunStep> steps) {
        for (int i = steps.size() - 1; i >= Math.max(0, steps.size() - 12); i--) {
            RunStep st = steps.get(i);
            Map<String, Object> d = st.detail() == null ? Map.of() : st.detail();
            Object text = d.get("output") != null ? d.get("output") : st.summary().startsWith("Tests ") ? d.get("text") : null;
            if (text instanceof String o && !o.isBlank() && !"OK".equals(st.outcome())) return o;
        }
        return "";
    }

    /**
     * Applies the Lead's line edits on the agent's branch and commits them as the Lead. Edits of one file apply from the
     * bottom up, so the numbers the Lead read stay right. Returns why it could not, or null.
     */
    String applyFix(TaskRun run, Agent lead, JsonNode edits) {
        Path worktree = Path.of(run.worktree());
        try {
            Map<Path, String> changed = edited(worktree, edits);
            if (changed.isEmpty()) return "the edits change nothing";
            for (var c : changed.entrySet()) Files.writeString(c.getKey(), c.getValue());
        } catch (IllegalArgumentException | java.io.IOException e) {
            return e.getMessage();
        }
        git.commitAll(worktree, "Fix by the Lead", new GitService.Author(lead.name(), "agent-" + lead.id().toLowerCase() + "@aiw.local"));
        return null;
    }

    /**
     * The files the edits change, with their new content; nothing is written. Edits of one file apply from the bottom
     * up, so the numbers the Lead read stay right. A file the edits leave as it was is left out.
     */
    static Map<Path, String> edited(Path worktree, JsonNode edits) throws java.io.IOException {
        Map<String, List<JsonNode>> byFile = new java.util.LinkedHashMap<>();
        for (JsonNode e : edits) byFile.computeIfAbsent(LeadService.text(e, "path"), k -> new ArrayList<>()).add(e);
        Map<Path, String> out = new java.util.LinkedHashMap<>();
        for (var entry : byFile.entrySet()) {
            Path file = worktree.resolve(entry.getKey()).normalize();
            if (!file.startsWith(worktree) || !Files.isRegularFile(file)) {
                throw new IllegalArgumentException(entry.getKey() + " is not a file of the branch");
            }
            String before = Files.readString(file);
            String content = before;
            List<JsonNode> sorted = new ArrayList<>(entry.getValue());
            sorted.sort((a, b) -> Integer.compare(b.path("from").asInt(), a.path("from").asInt()));
            for (JsonNode e : sorted) {
                int from = e.path("from").asInt();
                content = LineEdits.replaceLines(content, from, e.path("to").asInt(from), e.path("new").asString(""));
            }
            if (!content.equals(before)) out.put(file, content);
        }
        return out;
    }

    /** The Lead writes to the CTO in its chat; the task stays blocked until the CTO answers or acts. */
    private void escalate(Task task, Agent lead, String reason, String question, String kind) {
        Actor actor = Actor.agent(lead.id());
        String ws = tasks.workspaceOf(task);
        chat.say(lead.id(), "T-" + task.number() + " (" + task.title() + ") is blocked and needs your decision.\n\n"
                + "Blocked because: " + clip(reason, 800) + "\n\n" + question
                + "\n\nAnswer me here; I carry out your decision (resume, rewrite, split or cancel the task).");
        tx.executeWithoutResult(s -> recorded(ws, task, actor, "escalate", kind, Map.of("question", clip(question, 1_000))));
    }

    private void recorded(String ws, Task task, Actor actor, String decision, String why, Map<String, String> extra) {
        Map<String, Object> payload = new HashMap<>(extra);
        payload.put("number", task.number());
        payload.put("decision", decision);
        payload.put("why", why == null ? "" : why);
        events.publish(ws, task.projectId(), "task.lead_decided", actor, new Subject("task", task.id()), payload);
        audit.record(ws, actor, "task.lead_decided", "task", task.id(), AuditLog.Decision.RECORDED,
                Map.of("number", String.valueOf(task.number()), "decision", decision));
    }

    void failed(AgentJob job, String error) {
        Task task = tasks.find(job.string("taskId")).orElse(null);
        if (task == null) return;
        // The Lead failing one decision must not stop the Lead: it stays available.
        agents.setResting(job.agentId(), "AVAILABLE", null);
        if (job.string("retry") == null) {
            // One more try on its own, as the CTO's "Ask the Lead" used to be (T-28: the second try fixed it).
            Map<String, Object> again = new HashMap<>();
            for (String k : List.of("taskId", "runId", "reason", "byCto", "afterTakeover", "takeover")) if (job.string(k) != null) again.put(k, job.string(k));
            again.put("retry", "1");
            jobs.enqueue(job.workspaceId(), task.projectId(), job.agentId(), UNBLOCK, again, task.id());
        } else {
            agents.find(job.agentId()).ifPresent(lead -> escalate(task, lead, job.string("reason"),
                    "I could not settle it in two tries: " + clip(error, 600) + "\nIt needs your decision (or ask me again).", "failed"));
        }
        events.publish(job.workspaceId(), task.projectId(), "task.lead_decision_failed", Actor.agent(job.agentId()),
                new Subject("task", task.id()), Map.of("number", task.number(), "error", error));
    }

    static List<String> named(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = PATH.matcher(text == null ? "" : text.replace("/work/", " ").replace("file://", " "));
        while (m.find() && out.size() < 4) {
            String path = m.group(1).replaceFirst("^\\./", "");
            if (!path.startsWith("node_modules/") && !path.startsWith("node:")) out.add(path);
        }
        return new ArrayList<>(out);
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String tail(String s, int max) {
        return s.length() <= max ? s : "…" + s.substring(s.length() - max);
    }

    @Component
    static class UnblockHandler implements JobHandler {
        private final LeadUnblocker unblocker;

        UnblockHandler(LeadUnblocker unblocker) {
            this.unblocker = unblocker;
        }

        @Override public String type() { return UNBLOCK; }
        @Override public void handle(AgentJob job) { unblocker.decide(job); }
        @Override public void onFailed(AgentJob job, String error) { unblocker.failed(job, error); }
    }
}
