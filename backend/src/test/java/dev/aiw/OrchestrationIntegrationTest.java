package dev.aiw;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aiw.support.StubModelServer;
import dev.aiw.support.TestApi;
import dev.aiw.support.TestcontainersConfig;
import java.util.ArrayList;
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
import tools.jackson.databind.JsonNode;

/**
 * UC-1 from the specification import to task assignment (increment 3 pass criterion), with a scripted model:
 * Lead analysis and questions, answers, architecture and team proposal, revision, hiring, kickoff meeting,
 * minutes in shared memory, plan with dependencies and assignments. Also covers a failed Lead step and its retry.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "aiw.security.master-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "aiw.web.allowed-origins=http://localhost:5180",
        "aiw.execution.auto-start=false", "aiw.data-dir=${java.io.tmpdir}/aiw-test-data"
})
@Import(TestcontainersConfig.class)
class OrchestrationIntegrationTest {
    static final String SPEC = """
            # Banking Core
            Customers open accounts and transfer money between them.
            The accounts service must expose a REST API with full audit of every transfer.
            """;

    static StubModelServer stub;

    @LocalServerPort
    int port;

    @BeforeAll
    static void startStub() throws Exception {
        stub = new StubModelServer();
    }

    @AfterAll
    static void stopStub() {
        stub.close();
    }

    static String script(String body) {
        if (body.contains("analyze the project specification")) {
            return """
                    {"summary": "A core banking backend for accounts and transfers.",
                     "functional": ["Open accounts", "Transfer money"], "nonFunctional": ["Audit every transfer"],
                     "domains": ["Accounts", "Transfers"],
                     "risks": [{"risk": "Double spending", "impact": "HIGH", "mitigation": "Idempotent transfers"}],
                     "questions": [{"text": "Which database?", "why": "Shapes persistence"},
                                   {"text": "Expected load?", "why": "Shapes scaling"}]}""";
        }
        if (body.contains("revise your previous proposal")) {
            return """
                    {"architecture": "# Architecture v2\\nOne Spring service.", "rationale": "Two backend engineers.",
                     "roles": [{"templateId": "rt_java", "count": 2, "department": "BACKEND", "seniority": "SENIOR",
                                "justification": "Accounts and transfers", "quote": "this text is not in the spec",
                                "skills": ["Java"], "responsibilities": ["Build the accounts service"]},
                               {"templateId": "rt_qa", "count": 1, "department": "QA", "seniority": "MID",
                                "justification": "Transfers must be tested", "quote": null}]}""";
        }
        if (body.contains("Now propose:")) {
            return """
                    {"architecture": "# Architecture\\nOne Spring service with PostgreSQL.", "rationale": "Small team.",
                     "roles": [{"templateId": "rt_java", "count": 1, "department": "BACKEND", "seniority": "SENIOR",
                                "justification": "REST API", "quote": "The accounts service must expose a REST API",
                                "skills": ["Java", "Spring"], "responsibilities": ["Build the accounts service"]},
                               {"templateId": "rt_qa", "count": 1, "department": "QA", "seniority": "MID",
                                "justification": "Transfers must be tested", "quote": null},
                               {"templateId": "rt_devops", "count": 1, "department": "DEVOPS", "seniority": "MID",
                                "justification": "Deployment", "quote": null}]}""";
        }
        if (body.contains("It is your turn")) {
            return "I will own my part and I see a dependency on the accounts API.";
        }
        if (body.contains("Write its minutes")) {
            return """
                    {"summary": "The team agreed on the plan.",
                     "decisions": [{"title": "Use PostgreSQL", "detail": "Chosen for transactions."}],
                     "disagreements": [],
                     "actionItems": [{"title": "Draft the API contract", "owner": "Java Engineer 01", "due": "Friday"}]}""";
        }
        if (body.contains("Break the project down")) {
            return """
                    {"tasks": [
                      {"ref": "t1", "title": "Create the accounts service", "description": "Spring Boot skeleton",
                       "acceptanceCriteria": ["Build passes", "Health endpoint answers"], "dependsOn": [],
                       "assignee": "Java Engineer 01", "estimateDays": 2, "priority": "HIGH", "brief": "Start here."},
                      {"ref": "t2", "title": "Test transfers", "description": "Integration tests",
                       "acceptanceCriteria": ["Transfer tests pass"], "dependsOn": ["t1"],
                       "assignee": "QA Engineer 01", "estimateDays": 1, "priority": "MEDIUM", "brief": "Wait for t1."},
                      {"ref": "t3", "title": "Transfer endpoint", "description": "POST /transfers",
                       "acceptanceCriteria": ["Endpoint returns 201", "Transfer is audited"], "dependsOn": ["t1"],
                       "assignee": "Java Engineer 02", "estimateDays": 3, "priority": "CRITICAL", "brief": ""}]}""";
        }
        return "OK";
    }

    record Setup(TestApi api, String workspaceId, String projectId, String leadId) {}

    Setup setup() {
        TestApi api = new TestApi(port);
        api.register("cto");
        String ws = api.post("/api/v1/workspaces", Map.of("name", "Bank")).json().get("id").asString();
        assertThat(api.post("/api/v1/workspaces/" + ws + "/providers", Map.of(
                "kind", "ANTHROPIC", "name", "Anthropic", "baseUrl", stub.baseUrl(), "apiKey", "sk-ant-test")).status())
                .isEqualTo(201);
        String project = api.post("/api/v1/workspaces/" + ws + "/projects", Map.of("name", "Banking Core")).json()
                .get("id").asString();
        String lead = api.get("/api/v1/projects/" + project + "/agents").json().get(0).get("id").asString();
        return new Setup(api, ws, project, lead);
    }

    @Test
    void leadTakesTheProjectFromSpecificationToAssignedTasks() {
        stub.anthropicResponder = OrchestrationIntegrationTest::script;
        Setup s = setup();
        TestApi api = s.api();
        String p = s.projectId();

        // No specification yet: the Lead cannot be briefed.
        assertThat(api.post("/api/v1/projects/" + p + "/lead/briefings", Map.of()).status()).isEqualTo(400);
        assertThat(api.upload("/api/v1/projects/" + p + "/documents", "spec.md", SPEC).status()).isEqualTo(201);

        // 1-2. Brief the Lead: analysis and questions.
        TestApi.Response started = api.post("/api/v1/projects/" + p + "/lead/briefings", Map.of());
        assertThat(started.status()).isEqualTo(202);
        String briefingId = started.json().get("id").asString();
        JsonNode analyzed = await(() -> api.get("/api/v1/briefings/" + briefingId).json(),
                d -> d.get("briefing").get("status").asString().equals("AWAITING_ANSWERS"));
        assertThat(analyzed.get("briefing").get("questions")).hasSize(2);
        assertThat(analyzed.get("briefing").get("analysis").get("domains").toString()).contains("Transfers");
        assertThat(api.post("/api/v1/projects/" + p + "/lead/briefings", Map.of()).status()).isEqualTo(409);

        // 3. Answers: all are required.
        assertThat(api.put("/api/v1/briefings/" + briefingId + "/answers", Map.of("answers", Map.of("q1", "PostgreSQL")))
                .status()).isEqualTo(400);
        assertThat(api.put("/api/v1/briefings/" + briefingId + "/answers",
                Map.of("answers", Map.of("q1", "PostgreSQL", "q2", "100 transfers per second"))).status()).isEqualTo(202);

        // 4. Architecture and justified team; quotes must really come from the specification.
        JsonNode proposed = await(() -> api.get("/api/v1/briefings/" + briefingId).json(),
                d -> d.get("briefing").get("status").asString().equals("PROPOSED"));
        assertThat(proposed.get("briefing").get("architecture").asString()).contains("PostgreSQL");
        JsonNode v1 = proposed.get("proposals").get(0);
        assertThat(v1.get("lines")).hasSize(3);
        assertThat(v1.get("lines").get(0).get("quote").asString()).contains("REST API");
        assertThat(v1.get("estimatedDailyUsd").isNull()).isFalse();
        String sentToModel = stub.requests.getLast().body();
        assertThat(sentToModel).contains("100 transfers per second").contains("rt_java");

        // 5. The CTO asks for a revision, then accepts the new version with edits.
        assertThat(api.post("/api/v1/hiring-proposals/" + v1.get("id").asString() + "/revise",
                Map.of("comment", "Two backend engineers, no DevOps for now.")).status()).isEqualTo(202);
        JsonNode revised = await(() -> api.get("/api/v1/briefings/" + briefingId).json(),
                d -> d.get("proposals").size() == 2 && d.get("briefing").get("status").asString().equals("PROPOSED"));
        JsonNode v2 = revised.get("proposals").get(0);
        assertThat(v2.get("version").asInt()).isEqualTo(2);
        assertThat(revised.get("proposals").get(1).get("status").asString()).isEqualTo("SUPERSEDED");
        assertThat(v2.get("lines").get(0).get("quote").isNull()).isTrue();

        TestApi.Response accepted = api.post("/api/v1/hiring-proposals/" + v2.get("id").asString() + "/accept", Map.of(
                "lines", List.of(
                        Map.of("templateId", "rt_java", "count", 2, "department", "BACKEND", "seniority", "SENIOR"),
                        Map.of("templateId", "rt_qa", "count", 1, "department", "QA", "seniority", "MID")),
                "kickoff", true));
        assertThat(accepted.status()).isEqualTo(200);
        assertThat(accepted.json()).hasSize(3);
        Map<String, String> ids = new java.util.HashMap<>();
        for (JsonNode a : api.get("/api/v1/projects/" + p + "/agents").json()) ids.put(a.get("name").asString(), a.get("id").asString());
        assertThat(ids).containsKeys("Java Engineer 01", "Java Engineer 02", "QA Engineer 01");

        // 7. Kickoff meeting: every participant speaks, the Lead publishes minutes; decisions go to shared memory.
        JsonNode meeting = await(() -> api.get("/api/v1/projects/" + p + "/meetings").json(),
                m -> m.size() == 1 && m.get(0).get("status").asString().equals("ENDED")).get(0);
        assertThat(meeting.get("purpose").asString()).isEqualTo("KICKOFF");
        JsonNode detail = api.get("/api/v1/meetings/" + meeting.get("id").asString()).json();
        assertThat(detail.get("participantIds")).hasSize(4);
        assertThat(detail.get("messages")).hasSize(4);
        JsonNode action = meeting.get("minutes").get("actionItems").get(0);
        assertThat(action.get("ownerAgentId").asString()).isEqualTo(ids.get("Java Engineer 01"));
        String memory = api.get("/api/v1/projects/" + p + "/memory").body();
        assertThat(memory).contains("ARCHITECTURE").contains("Use PostgreSQL").contains("REQUIREMENT").contains("RISK");
        assertThat(api.get("/api/v1/projects/" + p + "/memory?q=postgresql").json().size()).isGreaterThanOrEqualTo(1);

        // 8. Plan: tasks with dependencies; only tasks whose dependencies are done are assigned.
        JsonNode tasks = await(() -> api.get("/api/v1/projects/" + p + "/tasks").json(), t -> t.size() == 3);
        JsonNode t1 = tasks.get(0), t2 = tasks.get(1), t3 = tasks.get(2);
        assertThat(t1.get("status").asString()).isEqualTo("ASSIGNED");
        assertThat(t1.get("assigneeId").asString()).isEqualTo(ids.get("Java Engineer 01"));
        assertThat(t1.get("acceptanceCriteria")).hasSize(2);
        assertThat(t2.get("status").asString()).isEqualTo("CREATED");
        assertThat(t2.get("dependsOn").get(0).asString()).isEqualTo(t1.get("id").asString());
        assertThat(t3.get("priority").asString()).isEqualTo("CRITICAL");

        // The Lead briefed assignees through real messages; agents are available again.
        JsonNode conversations = api.get("/api/v1/projects/" + p + "/conversations").json();
        assertThat(conversations.toString()).contains("ASSIGNMENT").contains(ids.get("Java Engineer 01"));
        for (JsonNode a : api.get("/api/v1/projects/" + p + "/agents").json()) {
            assertThat(a.get("activity").asString()).as(a.get("name").asString()).isEqualTo("AVAILABLE");
        }
        String events = api.get("/api/v1/workspaces/" + s.workspaceId() + "/events?limit=500").body();
        assertThat(events).contains("briefing.analyzed").contains("hiring_proposal.created").contains("meeting.started")
                .contains("meeting.minutes_published").contains("plan.created").contains("task.assigned")
                .contains("\"to\":\"IN_MEETING\"").contains("\"to\":\"THINKING\"").contains("agent.step_completed");
        assertThat(api.get("/api/v1/agents/" + s.leadId() + "/steps").json().size()).isGreaterThanOrEqualTo(5);

        // Mode B on the same graph: cycles are refused, the human assigns, blocks and unblocks.
        assertThat(api.post("/api/v1/tasks/" + t1.get("id").asString() + "/dependencies",
                Map.of("dependsOn", List.of(t2.get("id").asString()))).status()).isEqualTo(409);
        JsonNode manual = api.post("/api/v1/projects/" + p + "/tasks", Map.of("title", "Write the README",
                "acceptanceCriteria", List.of("README exists"), "assigneeId", ids.get("QA Engineer 01"))).json();
        assertThat(manual.get("status").asString()).isEqualTo("ASSIGNED");
        String manualId = manual.get("id").asString();
        assertThat(api.post("/api/v1/tasks/" + manualId + "/block", Map.of("reason", "Waiting for the CTO")).json()
                .get("status").asString()).isEqualTo("BLOCKED");
        assertThat(api.post("/api/v1/tasks/" + manualId + "/unblock", Map.of()).json().get("status").asString())
                .isEqualTo("ASSIGNED");
        assertThat(api.put("/api/v1/tasks/" + manualId + "/assignee", Map.of()).json().get("status").asString())
                .isEqualTo("READY");

        // Meeting action item → task, once.
        String actionUrl = "/api/v1/meetings/" + meeting.get("id").asString() + "/action-items/0/task";
        TestApi.Response fromAction = api.post(actionUrl, Map.of());
        assertThat(fromAction.status()).isEqualTo(201);
        assertThat(fromAction.json().get("status").asString()).isEqualTo("ASSIGNED");
        assertThat(api.post(actionUrl, Map.of()).status()).isEqualTo(409);

        // Another workspace cannot see any of it.
        TestApi other = new TestApi(port);
        other.register("other");
        assertThat(other.get("/api/v1/projects/" + p + "/tasks").status()).isEqualTo(404);
        assertThat(other.get("/api/v1/briefings/" + briefingId).status()).isEqualTo(404);
    }

    @Test
    void failedLeadStepBlocksTheLeadUntilRetried() {
        Setup s = setup();
        TestApi api = s.api();
        String p = s.projectId();
        api.upload("/api/v1/projects/" + p + "/documents", "spec.md", SPEC);
        stub.anthropicResponder = body -> body.contains("analyze the project specification") ? "I cannot answer in JSON." : script(body);
        String briefingId = api.post("/api/v1/projects/" + p + "/lead/briefings", Map.of()).json().get("id").asString();

        JsonNode failed = await(() -> api.get("/api/v1/briefings/" + briefingId).json(),
                d -> d.get("briefing").get("status").asString().equals("FAILED"));
        assertThat(failed.get("briefing").get("error").asString()).contains("usable answer");
        JsonNode lead = api.get("/api/v1/agents/" + s.leadId()).json();
        assertThat(lead.get("activity").asString()).isEqualTo("BLOCKED");
        assertThat(lead.get("blockedReason").asString()).contains("usable answer");

        JsonNode job = await(() -> api.get("/api/v1/projects/" + p + "/jobs?open=true").json(),
                j -> j.size() == 1 && j.get(0).get("status").asString().equals("FAILED")).get(0);
        stub.anthropicResponder = OrchestrationIntegrationTest::script;
        assertThat(api.post("/api/v1/jobs/" + job.get("id").asString() + "/retry", Map.of()).status()).isEqualTo(200);
        await(() -> api.get("/api/v1/briefings/" + briefingId).json(),
                d -> d.get("briefing").get("status").asString().equals("AWAITING_ANSWERS"));
        assertThat(api.get("/api/v1/agents/" + s.leadId()).json().get("activity").asString()).isEqualTo("AVAILABLE");
    }

    private static JsonNode await(Supplier<JsonNode> read, Predicate<JsonNode> done) {
        long deadline = System.currentTimeMillis() + 30_000;
        List<String> seen = new ArrayList<>();
        while (System.currentTimeMillis() < deadline) {
            JsonNode node = read.get();
            if (done.test(node)) return node;
            if (seen.isEmpty() || !seen.getLast().equals(node.toString())) seen.add(node.toString());
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("Condition not met in time; last state: " + (seen.isEmpty() ? "-" : seen.getLast()));
    }
}
