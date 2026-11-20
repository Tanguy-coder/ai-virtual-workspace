package dev.aiw;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aiw.support.StubModelServer;
import dev.aiw.support.TestApi;
import dev.aiw.support.TestcontainersConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * CTO feedback 2026-11-10, with a real sandbox and a scripted model: (1) a run stopped by its token budget gets a
 * new budget and resumes; a task too big is split by the Lead into small tasks that inherit its dependents and its
 * branch; (2) the CTO orders the Lead to organize a realignment meeting mid-project: running work pauses, a blocked
 * developer exposes the problem from their recorded status, the solution agreed in the minutes reaches the task and
 * the work resumes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "aiw.security.master-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "aiw.web.allowed-origins=http://localhost:5180",
        "aiw.execution.sweep-ms=500",
        "aiw.execution.sweep-delay-ms=500"
})
@Import(TestcontainersConfig.class)
class RealignmentIntegrationTest {
    static StubModelServer stub;
    static Path dataDir;

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry r) throws Exception {
        dataDir = Files.createTempDirectory("aiw-realign-test");
        r.add("aiw.data-dir", () -> dataDir.toString());
        r.add("aiw.sandbox.stop-on-shutdown", () -> "true");
    }

    @BeforeAll
    static void startStub() throws Exception {
        stub = new StubModelServer();
    }

    @AfterAll
    static void stopStub() {
        stub.close();
    }

    static final java.util.concurrent.atomic.AtomicInteger PROGRESS = new java.util.concurrent.atomic.AtomicInteger();

    /** A step that changes and commits a file, so every step succeeds and the branch carries committed work. */
    static String writeNote() {
        return """
                {"thought": "Keep going.", "actions": [{"tool": "file.write", "args": {"path": "notes.md", "content": "progress %d\\n"}},
                  {"tool": "git.commit", "args": {"message": "Progress"}}]}""".formatted(PROGRESS.incrementAndGet());
    }
    static final String BLOCK_SPEC = """
            {"thought": "I cannot go on.", "actions": [{"tool": "task.block", "args": {"reason": "Cannot reach the payments API spec"}}]}""";
    static final String WAIT = """
            {"thought": "Waiting.", "actions": [{"tool": "task.block", "args": {"reason": "Waiting for the CTO"}}]}""";

    /**
     * CTO requirement 2026-11-18 (docs/16): a blocked task goes to the Lead first. A technical question is answered
     * and the work resumes by itself; a product choice is escalated to the CTO in the Lead's chat.
     */
    @Test
    void theLeadSettlesTechnicalBlocksAndEscalatesProductChoices() {
        stub.anthropicResponder = body -> {
            if (body.contains("needs a decision. You lead the team")) {
                return body.contains("payment provider")
                        ? """
                          {"decision": "escalate", "why": "A product choice.", "question": "Stripe or PayPal? I recommend Stripe."}"""
                        : """
                          {"decision": "answer", "why": "Node returns null.", "message": "Change the test: assert.doesNotThrow(() => execSync('npm run lint'))."}""";
            }
            if (body.contains("<tools>") && body.contains("Lint check")) {
                return body.contains("assert.doesNotThrow")
                        ? """
                          {"thought": "Next question.", "actions": [{"task.block": {"reason": "Which payment provider should we use?"}}]}"""
                        : """
                          {"thought": "Unsure.", "actions": [{"task.block": {"reason": "tests/lint.test.js expects undefined but execSync returns null"}}]}""";
            }
            return "OK";
        };
        TestApi api = new TestApi(port);
        api.register("cto-unblock");
        String ws = api.post("/api/v1/workspaces", Map.of("name", "Unblock")).json().get("id").asString();
        api.post("/api/v1/workspaces/" + ws + "/providers", Map.of(
                "kind", "ANTHROPIC", "name", "Anthropic", "baseUrl", stub.baseUrl(), "apiKey", "sk-ant-test"));
        String p = api.post("/api/v1/workspaces/" + ws + "/projects", Map.of("name", "Lint")).json().get("id").asString();
        String leadId = null;
        for (JsonNode a : api.get("/api/v1/projects/" + p + "/agents").json()) if (a.get("lead").asBoolean()) leadId = a.get("id").asString();
        String lead = leadId;
        JsonNode dev = api.post("/api/v1/projects/" + p + "/agents", Map.of("roleTemplateId", "rt_java")).json();
        String task = api.post("/api/v1/projects/" + p + "/tasks", Map.of("title", "Lint check",
                "acceptanceCriteria", List.of("npm run lint passes"), "assigneeId", dev.get("id").asString())).json().get("id").asString();

        // The Lead answered the technical question: its message reached the agent and the work resumed by itself.
        JsonNode steps = await(() -> api.get("/api/v1/tasks/" + task + "/run").json().get("steps"),
                st -> st.toString().contains("Message from the Lead") && st.toString().contains("payment provider"));
        assertThat(steps.toString()).contains("Resumed").contains("assert.doesNotThrow");

        // The product choice is escalated: the task stays blocked and the Lead asks the CTO in its chat.
        JsonNode said = await(() -> api.get("/api/v1/agents/" + lead + "/messages").json(),
                m -> m.toString().contains("needs your decision"));
        assertThat(said.toString()).contains("Stripe or PayPal? I recommend Stripe.").contains("Which payment provider");
        assertThat(api.get("/api/v1/tasks/" + task).json().get("task").get("status").asString()).isEqualTo("BLOCKED");

        // The Lead saw what the block is about, and nothing more than it needs.
        String decision = stub.requests.stream().map(StubModelServer.Captured::body)
                .filter(b -> b.contains("needs a decision. You lead the team") && b.contains("lint.test.js")).findFirst().orElseThrow();
        assertThat(decision).contains("<blocked_because>").contains("<last_steps>").contains("Never escalate a technical question");
    }

    /**
     * CTO requirement 2026-11-20 (docs/17): a task that fails four times is the Lead's to repair. The Lead answers,
     * answers, rewrites; at the fourth block it takes over without a model decision, works the branch with the tools,
     * hands it back, and the agent goes on from the repaired state with a fresh context (its failed attempts left out).
     */
    @Test
    void afterFourFailuresTheLeadTakesOverRepairsAndHandsBack() {
        stub.anthropicResponder = body -> {
            if (body.contains("needs a decision. You lead the team") && body.contains("Adder")) {
                return body.contains("are not accepted any more")
                        ? """
                          {"decision": "rewrite", "why": "Smaller.", "task": {"description": "Make lib.js export add(a, b)."}, "message": "Only add()."}"""
                        : """
                          {"decision": "answer", "why": "Try again.", "message": "Export add from lib.js."}""";
            }
            if (body.contains("<takeover>")) {
                return body.contains("file.write lib.js")
                        ? """
                          {"thought": "Repaired.", "actions": [{"tool": "task.handback", "args": {"note": "lib.js exports add; tests/lib.test.js proves it."}}]}"""
                        : """
                          {"thought": "I write it myself.", "actions": [
                            {"tool": "file.write", "args": {"path": "package.json", "content": "{\\"name\\": \\"adder\\", \\"type\\": \\"module\\", \\"scripts\\": {\\"test\\": \\"node --test\\"}}\\n"}},
                            {"tool": "file.write", "args": {"path": "lib.js", "content": "export function add(a, b) {\\n  return a + b;\\n}\\n"}},
                            {"tool": "file.write", "args": {"path": "tests/lib.test.js", "content": "import { test } from 'node:test';\\nimport assert from 'node:assert';\\nimport { add } from '../lib.js';\\n\\ntest('add', () => assert.strictEqual(add(1, 2), 3));\\n"}},
                            {"tool": "git.commit", "args": {"message": "Repair lib.js"}}]}""";
            }
            if (body.contains("<tools>") && body.contains("Adder")) {
                return body.contains("I (the Lead) took over")
                        ? """
                          {"thought": "The Lead repaired it; tests prove it.", "actions": [{"tool": "task.submit", "args": {"summary": "add() exported and tested."}}]}"""
                        : """
                          {"thought": "Stuck.", "actions": [{"tool": "task.block", "args": {"reason": "I cannot make lib.js work"}}]}""";
            }
            return "OK";
        };
        TestApi api = new TestApi(port);
        api.register("cto-takeover");
        String ws = api.post("/api/v1/workspaces", Map.of("name", "Takeover")).json().get("id").asString();
        api.post("/api/v1/workspaces/" + ws + "/providers", Map.of(
                "kind", "ANTHROPIC", "name", "Anthropic", "baseUrl", stub.baseUrl(), "apiKey", "sk-ant-test"));
        String p = api.post("/api/v1/workspaces/" + ws + "/projects", Map.of("name", "Adder")).json().get("id").asString();
        JsonNode dev = api.post("/api/v1/projects/" + p + "/agents", Map.of("roleTemplateId", "rt_java")).json();
        String task = api.post("/api/v1/projects/" + p + "/tasks", Map.of("title", "Adder",
                "acceptanceCriteria", List.of("add(1, 2) is 3"), "assigneeId", dev.get("id").asString())).json().get("id").asString();

        // The agent submits after the hand-back: the platform's tests pass on the Lead's repair.
        JsonNode view = await(() -> api.get("/api/v1/tasks/" + task + "/run").json(),
                v -> v.get("steps").toString().contains("Submitted: tests passed"));
        String steps = view.get("steps").toString();
        assertThat(steps).contains("The Lead takes over").contains("Lead: I write it myself.").contains("I (the Lead) took over")
                .contains("are left for the task").doesNotContain("Budget raised");
        // Three decisions of the Lead, then the takeover by code (no fourth model decision).
        long decisions = stub.requests.stream().filter(r -> r.body().contains("needs a decision. You lead the team")
                && r.body().contains("Adder")).count();
        assertThat(decisions).isEqualTo(3);
        // The agent's prompt after the hand-back starts at the Lead's instruction: its failed attempts are left out.
        String after = stub.requests.stream().map(StubModelServer.Captured::body)
                .filter(b -> b.contains("<tools>") && b.contains("I (the Lead) took over") && !b.contains("<takeover>"))
                .findFirst().orElseThrow();
        assertThat(after).contains("<latest_instruction").doesNotContain("I cannot make lib.js work");
        // The Lead saw why the agent stopped.
        assertThat(stub.requests.stream().anyMatch(r -> r.body().contains("<takeover>") && r.body().contains("I cannot make lib.js work"))).isTrue();
        api.post("/api/v1/tasks/" + task + "/cancel", Map.of());
    }

    @Test
    void aTaskThatReachesItsBudgetIsSplitByTheLeadNeverRaised() {
        stub.anthropicInputTokens = 6_000;
        stub.anthropicResponder = body -> {
            if (body.contains("too big for one run")) {
                return """
                        {"tasks": [
                          {"ref": "s1", "title": "Part one", "description": "Finish the committed work.", "acceptanceCriteria": ["part one works"],
                           "dependsOn": [], "assignee": "%1$s", "priority": "HIGH", "brief": "Continue the branch."},
                          {"ref": "s2", "title": "Part two", "description": "The rest.", "acceptanceCriteria": ["part two works"],
                           "dependsOn": ["s1"], "assignee": "%1$s", "priority": "MEDIUM", "brief": ""}]}""".formatted(devName);
            }
            if (body.contains("<tools>")) return body.contains("Part one") ? WAIT : body.contains("Big task") ? writeNote() : WAIT;
            return "OK";
        };
        TestApi api = new TestApi(port);
        api.register("cto-budget");
        String ws = api.post("/api/v1/workspaces", Map.of("name", "Budget")).json().get("id").asString();
        api.post("/api/v1/workspaces/" + ws + "/providers", Map.of(
                "kind", "ANTHROPIC", "name", "Anthropic", "baseUrl", stub.baseUrl(), "apiKey", "sk-ant-test"));
        String p = api.post("/api/v1/workspaces/" + ws + "/projects", Map.of("name", "Big")).json().get("id").asString();
        JsonNode dev = api.post("/api/v1/projects/" + p + "/agents", Map.of("roleTemplateId", "rt_java")).json();
        devName = dev.get("name").asString();

        // The project gives each run 10 000 tokens; the scripted model spends 6 000 per step.
        JsonNode budget = api.put("/api/v1/projects/" + p + "/budget", Map.of("taskTokenBudget", 10_000)).json();
        assertThat(budget.get("taskTokenBudget").asLong()).isEqualTo(10_000);
        assertThat(budget.get("defaultTaskTokenBudget").asLong()).isEqualTo(200_000);

        String big = api.post("/api/v1/projects/" + p + "/tasks", Map.of("title", "Big task",
                "acceptanceCriteria", List.of("everything works"), "assigneeId", dev.get("id").asString())).json().get("id").asString();
        String downstream = api.post("/api/v1/projects/" + p + "/tasks", Map.of("title", "Downstream",
                "acceptanceCriteria", List.of("uses the big task"), "dependsOn", List.of(big))).json().get("id").asString();

        // A task never goes past the ceiling: neither the project setting nor a run's budget can be set above it.
        assertThat(api.put("/api/v1/projects/" + p + "/budget", Map.of("taskTokenBudget", 1_000_000)).status()).isEqualTo(400);

        // The run reaches its budget: the Lead splits the task by itself (no CTO, no raise), the first part continues the branch.
        String branch = await(() -> api.get("/api/v1/tasks/" + big + "/run").json().get("run"),
                r -> !r.isNull() && !r.get("branch").isNull()).get("branch").asString();
        JsonNode tasks = await(() -> api.get("/api/v1/projects/" + p + "/tasks").json(), t -> t.size() == 4);
        JsonNode original = find(tasks, "Big task"), one = find(tasks, "Part one"), two = find(tasks, "Part two");
        assertThat(original.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(one.get("sourceKind").asString()).isEqualTo("SPLIT");
        assertThat(one.get("sourceId").asString()).isEqualTo(big);
        assertThat(two.get("dependsOn").toString()).contains(one.get("id").asString());
        assertThat(find(tasks, "Downstream").get("dependsOn").toString())
                .contains(one.get("id").asString()).contains(two.get("id").asString()).doesNotContain(big);
        assertThat(api.get("/api/v1/tasks/" + big + "/run").json().get("run").get("status").asString()).isEqualTo("CANCELLED");
        assertThat(stub.requests.stream().anyMatch(r -> r.body().contains("too big for one run") && r.body().contains("of its 10000 tokens")
                && r.body().contains("at most HALF"))).isTrue();

        // Part one continues the committed work on the original branch.
        JsonNode oneRun = await(() -> api.get("/api/v1/tasks/" + one.get("id").asString() + "/run").json().get("run"), r -> !r.isNull());
        assertThat(oneRun.get("branch").asString()).isEqualTo(branch);
        assertThat(api.get("/api/v1/tasks/" + downstream).json().get("task").get("status").asString()).isEqualTo("CREATED");
        api.post("/api/v1/tasks/" + one.get("id").asString() + "/cancel", Map.of());
    }

    static volatile String devName;

    @Test
    void theCtoOrdersTheLeadToRealignAndABlockedDeveloperGetsASolution() {
        stub.anthropicInputTokens = 12;
        TestApi api = new TestApi(port);
        api.register("cto-realign");
        String ws = api.post("/api/v1/workspaces", Map.of("name", "Realign")).json().get("id").asString();
        api.post("/api/v1/workspaces/" + ws + "/providers", Map.of(
                "kind", "ANTHROPIC", "name", "Anthropic", "baseUrl", stub.baseUrl(), "apiKey", "sk-ant-test"));
        String p = api.post("/api/v1/workspaces/" + ws + "/projects", Map.of("name", "Payments")).json().get("id").asString();
        JsonNode dev = api.post("/api/v1/projects/" + p + "/agents", Map.of("roleTemplateId", "rt_java")).json();
        JsonNode qa = api.post("/api/v1/projects/" + p + "/agents", Map.of("roleTemplateId", "rt_qa")).json();
        String devId = dev.get("id").asString(), qaId = qa.get("id").asString();
        String devN = dev.get("name").asString(), qaN = qa.get("name").asString();

        stub.anthropicResponder = body -> {
            if (body.contains("orders you to organize a realignment meeting")) {
                return """
                        {"title": "Back to the payments scope", "agenda": "1. Scope\\n2. Status and problems\\n3. Solutions",
                         "participants": ["%s"], "rounds": 1}""".formatted(qaN);
            }
            if (body.contains("Write its minutes")) {
                return """
                        {"summary": "We refocus on payments.", "decisions": [{"title": "Payments first", "detail": "Everything else waits."}],
                         "disagreements": [], "actionItems": [],
                         "problems": [{"raisedBy": "%s", "task": "T-1", "problem": "Cannot reach the payments API spec",
                                       "solution": "Use the spec in docs/payments.md"}]}""".formatted(devN);
            }
            if (body.contains("It is your turn")) {
                sleep(1_500);
                return "My status and my view.";
            }
            if (body.contains("<tools>")) {
                if (body.contains("Stuck work")) return BLOCK_SPEC;
                sleep(800);
                return writeNote();
            }
            return "OK";
        };

        String stuck = api.post("/api/v1/projects/" + p + "/tasks", Map.of("title", "Stuck work",
                "acceptanceCriteria", List.of("payments are sent"), "assigneeId", devId)).json().get("id").asString();
        String busy = api.post("/api/v1/projects/" + p + "/tasks", Map.of("title", "Long work",
                "acceptanceCriteria", List.of("reports are tested"), "assigneeId", qaId)).json().get("id").asString();
        await(() -> api.get("/api/v1/tasks/" + stuck).json().get("task"), t -> t.get("status").asString().equals("BLOCKED"));
        await(() -> api.get("/api/v1/tasks/" + busy + "/run").json().get("run"), r -> !r.isNull() && r.get("status").asString().equals("RUNNING"));

        // Convening it myself needs the details; delegating needs only the concern.
        assertThat(api.post("/api/v1/projects/" + p + "/realignment", Map.of("concern", "Drift", "delegate", false)).status()).isEqualTo(400);
        assertThat(api.post("/api/v1/projects/" + p + "/realignment",
                Map.of("concern", "We are drifting from the payments scope", "delegate", true)).status()).isEqualTo(202);

        JsonNode meeting = await(() -> api.get("/api/v1/projects/" + p + "/meetings").json(), m -> m.size() == 1).get(0);
        assertThat(meeting.get("purpose").asString()).isEqualTo("REALIGNMENT");
        assertThat(meeting.get("agenda").asString()).startsWith("CTO's concern: We are drifting from the payments scope");
        assertThat(meeting.get("participantIds").toString()).contains(devId).contains(qaId); // the blocked developer is added by the platform
        await(() -> api.get("/api/v1/tasks/" + busy + "/run").json().get("run"), r -> r.get("status").asString().equals("PAUSED"));

        String meetingId = meeting.get("id").asString();
        JsonNode ended = await(() -> api.get("/api/v1/meetings/" + meetingId).json().get("meeting"),
                m -> m.get("status").asString().equals("ENDED"));
        assertThat(ended.get("minutes").get("problems").get(0).get("delivered").asBoolean()).isTrue();
        assertThat(ended.get("minutes").get("problems").get(0).get("taskNumber").asInt()).isEqualTo(1);

        // The developer saw their recorded status in their turn.
        assertThat(stub.requests.stream().anyMatch(r -> r.body().contains("It is your turn") && r.body().contains("your_work")
                && r.body().contains("Cannot reach the payments API spec"))).isTrue();
        String stuckSteps = await(() -> api.get("/api/v1/tasks/" + stuck + "/run").json(),
                v -> v.get("steps").toString().contains("Resumed with the solution from the meeting")).get("steps").toString();
        assertThat(stuckSteps).contains("Solution agreed in the realignment meeting").contains("docs/payments.md");
        String busySteps = await(() -> api.get("/api/v1/tasks/" + busy + "/run").json(),
                v -> v.get("steps").toString().contains("Resumed after the meeting")).get("steps").toString();
        assertThat(busySteps).contains("Paused for a realignment meeting").contains("Outcome of the realignment meeting");
        String events = api.get("/api/v1/workspaces/" + ws + "/events?limit=500").body();
        assertThat(events).contains("realignment.requested").contains("realignment.applied");

        api.post("/api/v1/tasks/" + busy + "/cancel", Map.of());
        api.post("/api/v1/tasks/" + stuck + "/cancel", Map.of());
    }

    /** CTO requirement 2026-11-11: the Lead executes the CTO's orders from its chat, and sees the recorded project state. */
    @Test
    void theLeadCarriesOutTheCtosOrdersFromItsChat() {
        stub.anthropicInputTokens = 12;
        stub.anthropicResponder = body -> {
            if (body.contains("<orders_manual>") && body.contains("Assign T-1")) {
                return """
                        Assigning it and adding the follow-up.
                        <orders>[{"do": "assign", "task": "T-1", "assignee": "%1$s"},
                          {"do": "create_task", "title": "Follow-up", "criteria": ["done"], "assignee": "%1$s", "dependsOn": [1]},
                          {"do": "pause", "task": 99},
                          {"do": "update_task", "task": 1, "description": "Vanilla HTML, no build"},
                          {"do": "cancel", "task": 2}]</orders>""".formatted(devName);
            }
            if (body.contains("<tools>")) return WAIT;
            return "Noted.";
        };
        TestApi api = new TestApi(port);
        api.register("cto-orders");
        String ws = api.post("/api/v1/workspaces", Map.of("name", "Orders")).json().get("id").asString();
        api.post("/api/v1/workspaces/" + ws + "/providers", Map.of(
                "kind", "ANTHROPIC", "name", "Anthropic", "baseUrl", stub.baseUrl(), "apiKey", "sk-ant-test"));
        String p = api.post("/api/v1/workspaces/" + ws + "/projects", Map.of("name", "Orders")).json().get("id").asString();
        devName = api.post("/api/v1/projects/" + p + "/agents", Map.of("roleTemplateId", "rt_java")).json().get("name").asString();
        String lead = null;
        for (JsonNode a : api.get("/api/v1/projects/" + p + "/agents").json()) if (a.get("lead").asBoolean()) lead = a.get("id").asString();
        api.post("/api/v1/projects/" + p + "/tasks", Map.of("title", "Login form", "acceptanceCriteria", List.of("it logs in")));

        String leadId = lead;
        assertThat(api.post("/api/v1/agents/" + leadId + "/messages", Map.of("content", "Assign T-1 to the developer")).status()).isEqualTo(202);
        JsonNode reply = await(() -> api.get("/api/v1/agents/" + leadId + "/messages").json(),
                m -> m.size() == 2 && m.get(1).get("status").asString().equals("FINAL")).get(1);
        assertThat(reply.get("content").asString()).isEqualTo("Assigning it and adding the follow-up.");
        JsonNode actions = reply.get("actions");
        assertThat(actions.size()).isEqualTo(5);
        assertThat(actions.get(3).get("detail").asString()).contains("T-1 rewritten");
        assertThat(actions.get(4).get("ok").asBoolean()).isTrue();
        assertThat(actions.get(0).get("ok").asBoolean()).isTrue();
        assertThat(actions.get(1).get("detail").asString()).contains("T-2 created");
        assertThat(actions.get(2).get("ok").asBoolean()).isFalse();
        assertThat(actions.get(2).get("detail").asString()).contains("No task T-99");

        JsonNode tasks = api.get("/api/v1/projects/" + p + "/tasks").json();
        assertThat(find(tasks, "Login form").get("assigneeId").isNull()).isFalse();
        assertThat(find(tasks, "Follow-up").get("dependsOn").toString()).contains(find(tasks, "Login form").get("id").asString());
        // The Lead saw the recorded state, and learns the outcome of its orders on the next message.
        assertThat(stub.requests.stream().anyMatch(r -> r.body().contains("project_state recorded_by") && r.body().contains("T-1 Login form"))).isTrue();
        api.post("/api/v1/agents/" + leadId + "/messages", Map.of("content", "Thanks"));
        await(() -> api.get("/api/v1/agents/" + leadId + "/messages").json(), m -> m.size() == 4);
        assertThat(stub.requests.stream().anyMatch(r -> r.body().contains("Thanks") && r.body().contains("Outcome of your orders")
                && r.body().contains("No task T-99"))).isTrue();
        // It sees closed tasks as closed, and the task as rewritten.
        assertThat(stub.requests.stream().anyMatch(r -> r.body().contains("Thanks") && r.body().contains("Closed (no longer to do): T-2 cancelled"))).isTrue();
        assertThat(find(api.get("/api/v1/projects/" + p + "/tasks").json(), "Login form").get("description").asString()).isEqualTo("Vanilla HTML, no build");
    }

    /**
     * gpt-oss behind OpenRouter answered a step with native tool calls in a normal (200) reply and an empty content,
     * which the platform took for "No JSON object" and blocked the task (CTO's project, T-14). Native calls, with
     * underscore names, are now read as the actions; a reply with too many actions runs the first five.
     */
    @Test
    void nativeToolCallsInAnOkReplyAndTooManyActionsAreRead() {
        TestApi api = new TestApi(port);
        api.register("cto-native");
        String ws = api.post("/api/v1/workspaces", Map.of("name", "Native")).json().get("id").asString();
        String pid = api.post("/api/v1/workspaces/" + ws + "/providers", Map.of("preset", "ollama", "name", "Small",
                "baseUrl", stub.baseUrl() + "/v1")).json().get("id").asString();
        api.put("/api/v1/workspaces/" + ws + "/providers/" + pid + "/models", Map.of("defaultModel", "stub-large"));
        String p = api.post("/api/v1/workspaces/" + ws + "/projects", Map.of("name", "Native")).json().get("id").asString();
        String dev = api.post("/api/v1/projects/" + p + "/agents", Map.of("roleTemplateId", "rt_java")).json().get("id").asString();

        stub.openAiResponder = body -> !body.contains("<tools>") ? "OK" : body.contains("Native reply") ? """
                RAW:{"id":"c2","object":"chat.completion","model":"stub-large","choices":[{"index":0,"finish_reason":"tool_calls",
                 "message":{"role":"assistant","content":null,"tool_calls":[
                  {"id":"t1","type":"function","function":{"name":"file_write","arguments":"{\\"path\\": \\"notes.md\\", \\"content\\": \\"hi\\\\n\\"}"}},
                  {"id":"t2","type":"function","function":{"name":"task_block","arguments":"{\\"reason\\": \\"Native calls read\\"}"}}]}}],
                 "usage":{"prompt_tokens":100,"completion_tokens":50,"total_tokens":150}}"""
                : """
                {"thought": "Many.", "actions": [{"tool": "repo.tree", "args": {}}, {"tool": "repo.tree", "args": {}},
                 {"tool": "repo.tree", "args": {}}, {"tool": "repo.tree", "args": {}},
                 {"tool": "task.block", "args": {"reason": "Five ran"}}, {"tool": "repo.tree", "args": {}}]}""";
        String t1 = api.post("/api/v1/projects/" + p + "/tasks", Map.of("title", "Native reply",
                "acceptanceCriteria", List.of("it works"), "assigneeId", dev)).json().get("id").asString();
        JsonNode run = await(() -> api.get("/api/v1/tasks/" + t1 + "/run").json(),
                r -> !r.get("run").isNull() && r.get("run").get("status").asString().equals("BLOCKED"));
        assertThat(run.get("run").get("blockedReason").asString()).contains("Native calls read");
        assertThat(run.get("steps").toString()).contains("file.write notes.md");

        String t2 = api.post("/api/v1/projects/" + p + "/tasks", Map.of("title", "Many actions",
                "acceptanceCriteria", List.of("it works"), "assigneeId", dev)).json().get("id").asString();
        JsonNode many = await(() -> api.get("/api/v1/tasks/" + t2 + "/run").json(),
                r -> !r.get("run").isNull() && r.get("run").get("status").asString().equals("BLOCKED"));
        assertThat(many.get("run").get("blockedReason").asString()).contains("Five ran");
        assertThat(many.get("steps").toString()).contains("Only the first 5 actions run");
    }

    /**
     * Small providers (Groq, OpenRouter): rate-limit answers are waited out, a request larger than the per-minute
     * output limit is shrunk and the cap remembered, and a native tool call ("tool_use_failed") is read as the action.
     * The error bodies are the ones Groq actually returned on the CTO's project.
     */
    @Test
    void smallProvidersRateLimitsAndNativeToolCallsAreHandled() {
        TestApi api = new TestApi(port);
        api.register("cto-groq");
        String ws = api.post("/api/v1/workspaces", Map.of("name", "Groq")).json().get("id").asString();
        String pid = api.post("/api/v1/workspaces/" + ws + "/providers", Map.of("preset", "ollama", "name", "Small",
                "baseUrl", stub.baseUrl() + "/v1")).json().get("id").asString();
        api.put("/api/v1/workspaces/" + ws + "/providers/" + pid + "/models", Map.of("defaultModel", "stub-large"));
        String p = api.post("/api/v1/workspaces/" + ws + "/projects", Map.of("name", "Groq")).json().get("id").asString();
        String lead = null;
        for (JsonNode a : api.get("/api/v1/projects/" + p + "/agents").json()) if (a.get("lead").asBoolean()) lead = a.get("id").asString();
        String leadId = lead;

        stub.openAiResponder = body -> "Hello from stub";
        stub.openAiErrors.add(new Object[] {429, """
                {"error":{"message":"Request too large for model `stub-large` in organization `org_1` service tier `on_demand` on output tokens per minute (OTPM): Limit 1000, Requested 4000. The request's expected output tokens exceed the enforced limit; reduce max_tokens (or the request's expected output) and try again.","type":"tokens","code":"rate_limit_exceeded"}}"""});
        stub.openAiErrors.add(new Object[] {429, """
                {"error":{"message":"Rate limit reached for model `stub-large` in organization `org_1` service tier `on_demand` on tokens per minute (TPM): Limit 8000, Used 4477, Requested 4577. Please try again in 0.2s.","type":"tokens","code":"rate_limit_exceeded"}}"""});
        int before = stub.requests.size();
        api.post("/api/v1/agents/" + leadId + "/messages", Map.of("content", "Hello"));
        JsonNode reply = await(() -> api.get("/api/v1/agents/" + leadId + "/messages").json(),
                m -> m.size() == 2 && !m.get(1).get("status").asString().equals("PENDING")).get(1);
        assertThat(reply.get("status").asString()).isEqualTo("FINAL");
        assertThat(reply.get("content").asString()).isEqualTo("Hello from stub");
        List<StubModelServer.Captured> calls = own(before, "Hello");
        assertThat(calls).hasSize(3);
        assertThat(calls.get(0).body()).contains("\"max_tokens\":4000");
        assertThat(calls.get(1).body()).contains("\"max_tokens\":1000");
        assertThat(calls.get(2).body()).contains("\"max_tokens\":1000");

        // The learned cap applies to the next call at once: no new "too large" round trip.
        before = stub.requests.size();
        api.post("/api/v1/agents/" + leadId + "/messages", Map.of("content", "Again"));
        await(() -> api.get("/api/v1/agents/" + leadId + "/messages").json(), m -> m.size() == 4);
        assertThat(own(before, "Again")).hasSize(1);
        assertThat(own(before, "Again").getLast().body()).contains("\"max_tokens\":1000");

        // OpenRouter-style limits: the model's own max_tokens ceiling, then credits for fewer tokens.
        stub.openAiErrors.add(new Object[] {400, """
                {"error":{"message":"`max_tokens` must be less than or equal to `800`, the maximum value for `max_tokens` is less than the `context_window` for this model","type":"invalid_request_error","param":"max_tokens"}}"""});
        stub.openAiErrors.add(new Object[] {402, """
                {"error":{"message":"This request requires more credits, or fewer max_tokens. You requested up to 800 tokens, but can only afford 700. To increase, visit https://openrouter.ai/settings/credits","code":402}}"""});
        before = stub.requests.size();
        api.post("/api/v1/agents/" + leadId + "/messages", Map.of("content", "Third"));
        JsonNode third = await(() -> api.get("/api/v1/agents/" + leadId + "/messages").json(),
                m -> m.size() == 6 && !m.get(5).get("status").asString().equals("PENDING")).get(5);
        assertThat(third.get("status").asString()).isEqualTo("FINAL");
        calls = own(before, "Third");
        assertThat(calls).hasSize(3);
        assertThat(calls.get(1).body()).contains("\"max_tokens\":800");
        assertThat(calls.get(2).body()).contains("\"max_tokens\":630");

        // The rest is about the engineer's calls: a paused Lead does not decide on the blocks below (docs/16), so no
        // background call takes the provider errors queued for the engineer.
        api.put("/api/v1/agents/" + leadId + "/status", Map.of("status", "PAUSED"));

        // A model that answers with a native tool call: its call is read as the action.
        JsonNode dev = api.post("/api/v1/projects/" + p + "/agents", Map.of("roleTemplateId", "rt_java")).json();
        stub.openAiResponder = body -> body.contains("<tools>") ? "unused" : "OK";
        stub.openAiErrors.add(new Object[] {400, """
                {"error":{"message":"Tool choice is none, but model called a tool","type":"invalid_request_error","code":"tool_use_failed","failed_generation":"{\\"name\\": \\"task.block\\", \\"arguments\\": {\\"reason\\": \\"Need the API contract\\"}\\n}"}}"""});
        String task = api.post("/api/v1/projects/" + p + "/tasks", Map.of("title", "Native call",
                "acceptanceCriteria", List.of("it works"), "assigneeId", dev.get("id").asString())).json().get("id").asString();
        JsonNode run = await(() -> api.get("/api/v1/tasks/" + task + "/run").json().get("run"),
                r -> !r.isNull() && r.get("status").asString().equals("BLOCKED"));
        assertThat(run.get("blockedReason").asString()).contains("Need the API contract");

        // A prompt larger than the provider's tokens per minute (Groq 413, same "Requested" after a smaller max_tokens):
        // the attempt fails with a clear reason, and the retried step is built lean, with a small output.
        String tpm = """
                {"error":{"message":"Request too large for model `stub-large` in organization `org_1` service tier `on_demand` on tokens per minute (TPM): Limit 8000, Requested 9388, please reduce your message size and try again.","type":"tokens","code":"rate_limit_exceeded"}}""";
        stub.openAiErrors.add(new Object[] {413, tpm});
        stub.openAiErrors.add(new Object[] {413, tpm});
        stub.openAiResponder = body -> body.contains("<tools>")
                ? "{\"thought\": \"Lean.\", \"actions\": [{\"tool\": \"task.block\", \"args\": {\"reason\": \"Lean prompt worked\"}}]}" : "OK";
        int mark = stub.requests.size();
        String second = api.post("/api/v1/projects/" + p + "/tasks", Map.of("title", "Long task",
                "acceptanceCriteria", List.of("it works"), "assigneeId", dev.get("id").asString())).json().get("id").asString();
        JsonNode leanRun = await(() -> api.get("/api/v1/tasks/" + second + "/run").json().get("run"),
                r -> !r.isNull() && r.get("status").asString().equals("BLOCKED"));
        assertThat(leanRun.get("blockedReason").asString()).contains("Lean prompt worked");
        // Both 413s failed their attempt (the 800-token cap learned above leaves no room to shrink); the third attempt
        // went through. The "same Requested" path is covered in ModelGatewayRateLimitTest.
        assertThat(stub.requests.subList(mark, stub.requests.size()).stream()
                .filter(r -> r.body().contains("Long task") && !r.body().contains("needs a decision. You lead the team"))).hasSize(3);

        // A daily quota (Groq TPD) with a long wait: the step waits in the queue, the task is not blocked.
        stub.openAiErrors.add(new Object[] {429, """
                {"error":{"message":"Rate limit reached for model `stub-large` in organization `org_1` service tier `on_demand` on tokens per day (TPD): Limit 200000, Used 198681, Requested 2397. Please try again in 7m45.696s.","type":"tokens","code":"rate_limit_exceeded"}}"""});
        String daily = api.post("/api/v1/projects/" + p + "/tasks", Map.of("title", "Daily quota",
                "acceptanceCriteria", List.of("it works"), "assigneeId", dev.get("id").asString())).json().get("id").asString();
        JsonNode waiting = await(() -> api.get("/api/v1/tasks/" + daily + "/run").json(),
                v -> !v.get("run").isNull() && v.get("steps").toString().contains("Waiting: "));
        // Either this step met the quota answer itself, or a background call met it first and the model is known to be out.
        assertThat(waiting.get("steps").toString()).containsAnyOf("daily limit reached", "daily quota reached")
                .containsPattern("waits [78] min");
        assertThat(waiting.get("run").get("status").asString()).isEqualTo("RUNNING");
        assertThat(api.get("/api/v1/tasks/" + daily).json().get("task").get("status").asString()).isNotEqualTo("BLOCKED");
    }

    /**
     * Small models slip on JSON: a reply with raw newlines in a string is read leniently; a malformed reply (the exact
     * Jackson error seen on the CTO's project) no longer blocks the task: the agent sees why and tries again.
     */
    @Test
    void aMalformedReplyIsRetriedInsteadOfBlockingTheTask() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        stub.anthropicInputTokens = 12;
        stub.anthropicResponder = body -> {
            if (!body.contains("<tools>")) return "OK";
            int n = calls.incrementAndGet();
            if (n <= 2) return "{\"thought\": \"Write it.\", \"actions\": [{\"tool\" {\"file.write\"}]}"; // reply + correction
            if (n == 3) return "{\"thought\": \"Raw newline\nin a string.\", \"actions\": [{\"tool\": \"file.write\", "
                    + "\"args\": {\"path\": \"a.txt\", \"content\": \"line 1\nline 2\"}},]}";
            return body.contains("Reply not usable") || body.contains("Raw newline")
                    ? "{\"thought\": \"Done.\", \"actions\": [{\"tool\": \"task.block\", \"args\": {\"reason\": \"Recovered after a bad reply\"}}]}"
                    : WAIT;
        };
        TestApi api = new TestApi(port);
        api.register("cto-json");
        String ws = api.post("/api/v1/workspaces", Map.of("name", "Json")).json().get("id").asString();
        api.post("/api/v1/workspaces/" + ws + "/providers", Map.of(
                "kind", "ANTHROPIC", "name", "Anthropic", "baseUrl", stub.baseUrl(), "apiKey", "sk-ant-test"));
        String p = api.post("/api/v1/workspaces/" + ws + "/projects", Map.of("name", "Json")).json().get("id").asString();
        JsonNode dev = api.post("/api/v1/projects/" + p + "/agents", Map.of("roleTemplateId", "rt_java")).json();
        String task = api.post("/api/v1/projects/" + p + "/tasks", Map.of("title", "Json task",
                "acceptanceCriteria", List.of("it works"), "assigneeId", dev.get("id").asString())).json().get("id").asString();

        JsonNode view = await(() -> api.get("/api/v1/tasks/" + task + "/run").json(),
                v -> !v.get("run").isNull() && v.get("run").get("status").asString().equals("BLOCKED"));
        assertThat(view.get("run").get("blockedReason").asString()).isEqualTo("Recovered after a bad reply");
        String steps = view.get("steps").toString();
        assertThat(steps).contains("Reply not usable: The model did not produce a usable answer: The JSON is malformed")
                .doesNotContain("REDACTED");
        // The lenient reading accepted raw newlines and a trailing comma: the file was written.
        assertThat(steps).contains("file.write a.txt");
    }

    private static JsonNode find(JsonNode tasks, String title) {
        for (JsonNode t : tasks) if (t.get("title").asString().equals(title)) return t;
        throw new AssertionError("No task " + title + " in " + tasks);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static JsonNode await(Supplier<JsonNode> read, Predicate<JsonNode> done) {
        long deadline = System.currentTimeMillis() + 180_000;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            last = read.get();
            if (done.test(last)) return last;
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("Condition not met in time; last state: " + last);
    }

    /**
     * Chat calls since {@code before} carrying the CTO's {@code message}: work left by other tests (the Lead deciding on
     * blocked tasks, resumed runs, docs/16) shares the stub in the background.
     */
    private static List<StubModelServer.Captured> own(int before, String message) {
        return stub.requests.subList(before, stub.requests.size()).stream()
                .filter(r -> r.body().contains("\\n\\n" + message + "\"")).toList();
    }
}
