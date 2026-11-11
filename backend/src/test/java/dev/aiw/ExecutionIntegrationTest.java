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
 * Increment 4 pass criterion, end to end with a real Git repository and a real Docker sandbox (scripted model):
 * an assigned task starts by itself, the agent writes code and tests on its branch, the platform runs the tests
 * (a failure goes back to the agent, who fixes it), the Lead reviews with evidence, the validation engine checks
 * every gate, the Lead's approval merges the branch into develop and the task is DONE. The Lead then asks the CTO to
 * merge develop into main, which happens only on the CTO's approval (CTO requirement, 2026-11-11).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "aiw.security.master-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "aiw.web.allowed-origins=http://localhost:5180",
        "aiw.execution.sweep-ms=500",
        "aiw.execution.sweep-delay-ms=500"
})
@Import(TestcontainersConfig.class)
class ExecutionIntegrationTest {
    static StubModelServer stub;
    static Path dataDir;

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry r) throws Exception {
        dataDir = Files.createTempDirectory("aiw-exec-test");
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

    static final String FIRST = """
            {"thought": "Set up node:test, write sum and its test, then submit.", "actions": [
              {"tool": "file.write", "args": {"path": "package.json", "content": "{\\"name\\": \\"calc\\", \\"version\\": \\"1.0.0\\", \\"scripts\\": {\\"test\\": \\"node --test\\"}}\\n"}},
              {"tool": "file.write", "args": {"path": "src/sum.js", "content": "exports.sum = (a, b) => a - b;\\n"}},
              {"tool": "file.write", "args": {"path": "tests/sum.test.js", "content": "const test = require('node:test');\\nconst assert = require('node:assert');\\nconst { sum } = require('../src/sum.js');\\ntest('adds two numbers', () => assert.strictEqual(sum(2, 3), 5));\\n"}},
              {"tool": "git.commit", "args": {"message": "Add sum with its test"}},
              {"tool": "task.submit", "args": {"summary": "sum(a, b) added; test 'adds two numbers' proves criterion 1."}}]}""";

    static final String FIX = """
            {"thought": "The test shows subtraction; fix the operator.", "actions": [
              {"tool": "file.edit", "args": {"path": "src/sum.js", "old": "a - b", "new": "a + b"}},
              {"tool": "exec", "args": {"command": "npm test"}},
              {"tool": "git.commit", "args": {"message": "Fix sum"}},
              {"tool": "task.submit", "args": {"summary": "Fixed; test 'adds two numbers' passes."}}]}""";

    static final String REVIEW = """
            {"decision": "APPROVE", "summary": "Correct and tested.", "comments": [],
             "criteria": [{"index": 1, "met": true, "evidence": "test 'adds two numbers'"}]}""";

    static final String LOOP = """
            {"thought": "Read the config.", "actions": [{"tool": "file.read", "args": {"path": "config/missing.json"}}]}""";

    static final String SOLO = FIRST.replace("a - b", "a + b");

    static String script(String body) {
        if (body.contains("<orders_manual>") && body.contains("Ask me again for main")) {
            return "Asking you for the merge.\n<orders>[{\"do\": \"merge_main\", \"note\": \"Solo sum\"}, {\"do\": \"merge_main\"}]</orders>";
        }
        if (body.contains("Loop forever")) return LOOP;
        if (body.contains("Solo sum") && body.contains("<tools>")) return SOLO;
        if (body.contains("You review merge request")) return REVIEW;
        if (body.contains("<tools>")) return body.contains("Tests failed on submission") ? FIX : FIRST;
        return "OK";
    }

    @Test
    void anAssignedTaskIsExecutedReviewedValidatedAndMerged() {
        stub.anthropicResponder = ExecutionIntegrationTest::script;
        TestApi api = new TestApi(port);
        api.register("cto");
        String ws = api.post("/api/v1/workspaces", Map.of("name", "Exec")).json().get("id").asString();
        api.post("/api/v1/workspaces/" + ws + "/providers", Map.of(
                "kind", "ANTHROPIC", "name", "Anthropic", "baseUrl", stub.baseUrl(), "apiKey", "sk-ant-test"));
        String p = api.post("/api/v1/workspaces/" + ws + "/projects", Map.of("name", "Calc")).json().get("id").asString();
        String lead = api.get("/api/v1/projects/" + p + "/agents").json().get(0).get("id").asString();
        String dev = api.post("/api/v1/projects/" + p + "/agents", Map.of("roleTemplateId", "rt_java")).json().get("id").asString();
        api.post("/api/v1/projects/" + p + "/agents", Map.of("roleTemplateId", "rt_qa")).json().get("id").asString();

        // Mode B: the CTO creates and assigns a task; the platform starts the work by itself.
        String taskId = api.post("/api/v1/projects/" + p + "/tasks", Map.of("title", "Sum two numbers",
                "acceptanceCriteria", List.of("sum(2, 3) returns 5"), "assigneeId", dev)).json().get("id").asString();

        // The first submission fails its tests (run by the platform); the agent fixes it and submits again. The Lead
        // reviews; its approval merges the branch into develop.
        JsonNode task = await(() -> api.get("/api/v1/tasks/" + taskId).json().get("task"), t -> t.get("status").asString().equals("DONE"));
        JsonNode mr = api.get("/api/v1/projects/" + p + "/merge-requests").json().get(0);
        assertThat(mr.get("status").asString()).isEqualTo("MERGED");
        assertThat(mr.get("targetBranch").asString()).isEqualTo("develop");
        assertThat(mr.get("reviewerAgentId").asString()).isEqualTo(lead);
        assertThat(mr.get("checks").toString()).contains("\"tests\"").doesNotContain("failed");

        JsonNode run = api.get("/api/v1/tasks/" + taskId + "/run").json();
        assertThat(run.get("run").get("branch").asString()).startsWith("agent/java-engineer-01/t-1-sum-two-numbers");
        String steps = run.get("steps").toString();
        assertThat(steps).contains("created from develop").contains("Tests failed on submission").contains("file.edit src/sum.js")
                .contains("Submitted: tests passed").contains("merging into develop").contains("Merged into develop");
        JsonNode testRuns = api.get("/api/v1/projects/" + p + "/test-runs").json();
        assertThat(testRuns).hasSize(2);
        assertThat(testRuns.get(1).get("status").asString()).isEqualTo("FAILED");
        assertThat(testRuns.get(1).get("failingExcerpt").asString()).contains("adds two numbers");
        assertThat(testRuns.get(0).get("status").asString()).isEqualTo("PASSED");
        assertThat(testRuns.get(0).get("passed").asInt()).isEqualTo(1);

        assertThat(task.get("acceptanceCriteria").get(0).get("met").asBoolean()).isTrue();
        assertThat(task.get("acceptanceCriteria").get(0).get("evidence").asString()).contains("adds two numbers");

        assertThat(api.get("/api/v1/projects/" + p + "/repo/file?ref=develop&path=src/sum.js").json().get("content").asString())
                .contains("a + b");
        assertThat(api.get("/api/v1/projects/" + p + "/repo/log?ref=develop").json().toString()).contains("Merge !1");

        // Nothing reaches main before the CTO approves: every task being merged, the Lead asked for it.
        assertThat(api.get("/api/v1/projects/" + p + "/repo/file?ref=main&path=src/sum.js").status()).isEqualTo(404);
        JsonNode approval = await(() -> api.get("/api/v1/workspaces/" + ws + "/approvals?status=PENDING").json(), a -> a.size() == 1).get(0);
        assertThat(approval.get("action").asString()).isEqualTo("branch.merge_main");
        assertThat(approval.get("requestedById").asString()).isEqualTo(lead);
        assertThat(approval.get("details").get("tasks").toString()).contains("T-1 Sum two numbers");
        api.post("/api/v1/workspaces/" + ws + "/approvals/" + approval.get("id").asString() + "/decision", Map.of("approve", true));
        assertThat(api.get("/api/v1/projects/" + p + "/repo/file?ref=main&path=src/sum.js").json().get("content").asString())
                .contains("a + b");
        assertThat(api.get("/api/v1/projects/" + p + "/repo/log").json().toString()).contains("Merge develop into main");
        assertThat(api.get("/api/v1/agents/" + dev).json().get("activity").asString()).isEqualTo("AVAILABLE");
        String events = api.get("/api/v1/workspaces/" + ws + "/events?limit=500").body();
        assertThat(events).contains("task_run.started").contains("task_run.step").contains("test_run.completed")
                .contains("merge_request.opened").contains("merge_request.reviewed").contains("merge_request.merged")
                .contains("branch.created").contains("branch.merged");
    }

    @Test
    void theWatchdogStopsALoopAndTheCtoReviewsWhenNoTeammateCan() {
        stub.anthropicResponder = ExecutionIntegrationTest::script;
        TestApi api = new TestApi(port);
        api.register("cto2");
        String ws = api.post("/api/v1/workspaces", Map.of("name", "Solo")).json().get("id").asString();
        api.post("/api/v1/workspaces/" + ws + "/providers", Map.of(
                "kind", "ANTHROPIC", "name", "Anthropic", "baseUrl", stub.baseUrl(), "apiKey", "sk-ant-test"));
        String p = api.post("/api/v1/workspaces/" + ws + "/projects", Map.of("name", "Solo")).json().get("id").asString();
        String lead = api.get("/api/v1/projects/" + p + "/agents").json().get(0).get("id").asString();

        // The same failing call three times: the watchdog blocks the task and tells the Lead.
        String looping = api.post("/api/v1/projects/" + p + "/tasks", Map.of("title", "Loop forever",
                "acceptanceCriteria", List.of("never"), "assigneeId", lead)).json().get("id").asString();
        JsonNode blocked = await(() -> api.get("/api/v1/tasks/" + looping).json().get("task"),
                t -> t.get("status").asString().equals("BLOCKED"));
        assertThat(blocked.get("blockedReason").asString()).contains("watchdog");
        assertThat(api.get("/api/v1/tasks/" + looping + "/run").json().get("run").get("status").asString()).isEqualTo("BLOCKED");
        api.post("/api/v1/tasks/" + looping + "/cancel", Map.of());

        // The only agent is the Lead, author of the work: it cannot approve itself, so the CTO reviews, and that approval
        // merges into develop.
        String solo = api.post("/api/v1/projects/" + p + "/tasks", Map.of("title", "Solo sum",
                "acceptanceCriteria", List.of("sum(2, 3) returns 5"), "assigneeId", lead)).json().get("id").asString();
        JsonNode mr = await(() -> api.get("/api/v1/projects/" + p + "/merge-requests").json(),
                m -> m.size() == 1 && m.get(0).get("status").asString().equals("IN_REVIEW")).get(0);
        assertThat(mr.get("reviewerAgentId").isNull()).isTrue();
        assertThat(api.post("/api/v1/merge-requests/" + mr.get("id").asString() + "/review", Map.of("approve", false)).status())
                .isEqualTo(400);
        api.post("/api/v1/merge-requests/" + mr.get("id").asString() + "/review", Map.of("approve", true));
        await(() -> api.get("/api/v1/tasks/" + solo).json().get("task"), t -> t.get("status").asString().equals("DONE"));
        assertThat(api.get("/api/v1/projects/" + p + "/repo/file?ref=develop&path=src/sum.js").json().get("content").asString())
                .contains("a + b");
        assertThat(api.get("/api/v1/projects/" + p + "/repo/file?ref=main&path=src/sum.js").status()).isEqualTo(404);

        // A CTO refusal leaves main untouched; the Lead can ask again from its chat.
        JsonNode approval = await(() -> api.get("/api/v1/workspaces/" + ws + "/approvals?status=PENDING").json(), a -> a.size() == 1).get(0);
        assertThat(approval.get("action").asString()).isEqualTo("branch.merge_main");
        api.post("/api/v1/workspaces/" + ws + "/approvals/" + approval.get("id").asString() + "/decision",
                Map.of("approve", false, "reason", "Not yet"));
        assertThat(api.get("/api/v1/projects/" + p + "/repo/file?ref=main&path=src/sum.js").status()).isEqualTo(404);

        api.post("/api/v1/agents/" + lead + "/messages", Map.of("content", "Ask me again for main"));
        JsonNode reply = await(() -> api.get("/api/v1/agents/" + lead + "/messages").json(),
                m -> m.size() == 2 && m.get(1).get("status").asString().equals("FINAL")).get(1);
        assertThat(reply.get("actions").get(0).get("ok").asBoolean()).isTrue();
        assertThat(reply.get("actions").get(1).get("ok").asBoolean()).isFalse();
        assertThat(reply.get("actions").get(1).get("detail").asString()).contains("already waiting");
        assertThat(stub.requests.stream().anyMatch(r -> r.body().contains("Ask me again for main") && r.body().contains("ahead of main"))).isTrue();
        JsonNode again = api.get("/api/v1/workspaces/" + ws + "/approvals?status=PENDING").json();
        assertThat(again.size()).isEqualTo(1);
        api.post("/api/v1/workspaces/" + ws + "/approvals/" + again.get(0).get("id").asString() + "/decision", Map.of("approve", true));
        assertThat(api.get("/api/v1/projects/" + p + "/repo/file?ref=main&path=src/sum.js").json().get("content").asString())
                .contains("a + b");
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
