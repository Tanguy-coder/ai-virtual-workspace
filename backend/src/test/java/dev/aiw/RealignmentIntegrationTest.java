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

    @Test
    void anExhaustedBudgetIsRaisedAndATooBigTaskIsSplitByTheLead() {
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

        JsonNode run = await(() -> api.get("/api/v1/tasks/" + big + "/run").json().get("run"),
                r -> !r.isNull() && r.get("status").asString().equals("BLOCKED"));
        assertThat(run.get("tokenBudget").asLong()).isEqualTo(10_000);
        assertThat(run.get("blockedReason").asString()).contains("Token budget reached");

        // Resuming without more budget is refused; a budget below what was used is refused; a real raise resumes.
        assertThat(api.post("/api/v1/tasks/" + big + "/run/resume", Map.of()).status()).isEqualTo(409);
        assertThat(api.put("/api/v1/tasks/" + big + "/run/budget", Map.of("tokenBudget", 10_001)).status()).isEqualTo(400);
        JsonNode raised = api.put("/api/v1/tasks/" + big + "/run/budget", Map.of("tokenBudget", 1_000_000)).json();
        assertThat(raised.get("tokenBudget").asLong()).isEqualTo(1_000_000);
        assertThat(raised.get("status").asString()).isEqualTo("RUNNING");
        await(() -> api.get("/api/v1/tasks/" + big + "/run").json(), v -> v.get("steps").toString().contains("Token budget changed by the CTO")
                && v.get("steps").toString().contains("Resumed"));

        // Splitting needs the work paused first.
        assertThat(api.post("/api/v1/tasks/" + big + "/split", Map.of()).status()).isEqualTo(409);
        api.post("/api/v1/tasks/" + big + "/run/pause", Map.of());
        String branch = await(() -> api.get("/api/v1/tasks/" + big + "/run").json().get("run"),
                r -> r.get("status").asString().equals("PAUSED")).get("branch").asString();
        assertThat(api.post("/api/v1/tasks/" + big + "/split", Map.of("guidance", "Keep the parts tiny")).status()).isEqualTo(202);

        JsonNode tasks = await(() -> api.get("/api/v1/projects/" + p + "/tasks").json(), t -> t.size() == 4);
        JsonNode original = find(tasks, "Big task"), one = find(tasks, "Part one"), two = find(tasks, "Part two");
        assertThat(original.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(one.get("sourceKind").asString()).isEqualTo("SPLIT");
        assertThat(one.get("sourceId").asString()).isEqualTo(big);
        assertThat(two.get("dependsOn").toString()).contains(one.get("id").asString());
        assertThat(find(tasks, "Downstream").get("dependsOn").toString())
                .contains(one.get("id").asString()).contains(two.get("id").asString()).doesNotContain(big);
        assertThat(api.get("/api/v1/tasks/" + big + "/run").json().get("run").get("status").asString()).isEqualTo("CANCELLED");
        assertThat(stub.requests.stream().anyMatch(r -> r.body().contains("too big for one run") && r.body().contains("Keep the parts tiny")
                && r.body().contains("10000 tokens"))).isTrue();

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
                          {"do": "pause", "task": 99}]</orders>""".formatted(devName);
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
        assertThat(actions.size()).isEqualTo(3);
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
}
