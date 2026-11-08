package dev.aiw;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aiw.support.StubModelServer;
import dev.aiw.support.TestApi;
import dev.aiw.support.TestApi.Response;
import dev.aiw.support.TestcontainersConfig;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "aiw.security.master-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "aiw.web.allowed-origins=http://localhost:5180",
        "aiw.events.relay-interval-ms=50"
})
@Import(TestcontainersConfig.class)
class FoundationIntegrationTest {
    static StubModelServer stub;

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    StringRedisTemplate redis;

    @BeforeAll
    static void startStub() throws Exception {
        stub = new StubModelServer();
    }

    @AfterAll
    static void stopStub() {
        stub.close();
    }

    record Setup(TestApi api, String workspaceId, String projectId, String leadId) {}

    Setup newProject() {
        TestApi api = new TestApi(port);
        api.register("cto");
        Response ws = api.post("/api/v1/workspaces", Map.of("name", "Personal"));
        assertThat(ws.status()).isEqualTo(201);
        String workspaceId = ws.json().get("id").asString();
        Response project = api.post("/api/v1/workspaces/" + workspaceId + "/projects",
                Map.of("name", "Banking Core", "description", "Accounts and wallets"));
        assertThat(project.status()).isEqualTo(201);
        String projectId = project.json().get("id").asString();
        JsonNode agents = api.get("/api/v1/projects/" + projectId + "/agents").json();
        assertThat(agents.size()).isEqualTo(1);
        assertThat(agents.get(0).get("lead").asBoolean()).isTrue();
        return new Setup(api, workspaceId, projectId, agents.get(0).get("id").asString());
    }

    @Test
    void leadConversationGoesThroughGatewayWithUsageCostEventsAndAudit() {
        Setup s = newProject();
        TestApi api = s.api();

        Response doc = api.upload("/api/v1/projects/" + s.projectId() + "/documents", "spec.md",
                "# Spec\nCustomers hold accounts and wallets. Unique marker: ZEBRA-42.");
        assertThat(doc.status()).isEqualTo(201);

        // No provider yet: the message fails with an explanation instead of a fake reply.
        api.post("/api/v1/agents/" + s.leadId() + "/messages", Map.of("content", "Hello"));
        JsonNode failed = awaitMessage(api, s.leadId(), m -> m.get("status").asString().equals("FAILED"));
        assertThat(failed.get("error").asString()).contains("No AI model is configured");

        String apiKey = "sk-test-secret-" + System.nanoTime();
        Response provider = api.post("/api/v1/workspaces/" + s.workspaceId() + "/providers", Map.of(
                "kind", "OPENAI_COMPATIBLE", "name", "Local stub", "baseUrl", stub.baseUrl() + "/v1", "apiKey", apiKey));
        assertThat(provider.status()).isEqualTo(201);
        assertThat(provider.body()).doesNotContain(apiKey);
        assertThat(provider.json().get("hasKey").asBoolean()).isTrue();
        String providerId = provider.json().get("id").asString();

        // The key is stored encrypted, never in clear.
        byte[] ciphertext = jdbc.sql("SELECT ciphertext FROM secrets WHERE name = ?").param("provider:" + providerId)
                .query(byte[].class).single();
        assertThat(new String(ciphertext, StandardCharsets.ISO_8859_1)).doesNotContain(apiKey);

        assertThat(api.put("/api/v1/workspaces/" + s.workspaceId() + "/model-prices", Map.of(
                "providerKind", "OPENAI_COMPATIBLE", "model", "stub-model",
                "inputUsdPerMtok", 2, "outputUsdPerMtok", 10)).status()).isEqualTo(204);
        assertThat(api.put("/api/v1/agents/" + s.leadId() + "/model",
                Map.of("providerId", providerId, "model", "stub-model")).status()).isEqualTo(200);

        int before = stub.requests.size();
        assertThat(api.post("/api/v1/agents/" + s.leadId() + "/messages",
                Map.of("content", "What are the main domains?")).status()).isEqualTo(202);
        JsonNode reply = awaitMessage(api, s.leadId(), m -> m.get("authorKind").asString().equals("AGENT"));
        assertThat(reply.get("content").asString()).isEqualTo("Hello from stub");

        StubModelServer.Captured call = stub.requests.get(before);
        assertThat(call.path()).isEqualTo("/v1/chat/completions");
        assertThat(header(call, "authorization")).isEqualTo("Bearer " + apiKey);
        assertThat(call.body()).contains("ZEBRA-42").contains("What are the main domains?")
                .contains("In a direct conversation you can only converse");

        // Real usage and cost: 1000 in x $2/M + 500 out x $10/M = $0.007
        JsonNode dashboard = api.get("/api/v1/projects/" + s.projectId() + "/dashboard").json();
        assertThat(new BigDecimal(dashboard.get("spend").get("totalUsd").asString())).isEqualByComparingTo("0.007");
        assertThat(dashboard.get("spend").get("callsTotal").asLong()).isEqualTo(1);
        assertThat(dashboard.get("agentsByState").get("AVAILABLE").asLong()).isEqualTo(1);

        List<String> types = eventTypes(api, s.workspaceId());
        assertThat(types).contains("project.created", "agent.hired", "document.added", "message.sent",
                "message.failed", "provider.created", "agent.model_bound", "agent.state_changed", "model.call_completed");
        assertThat(api.get("/api/v1/workspaces/" + s.workspaceId() + "/events?limit=500").body())
                .contains("\"to\":\"THINKING\"");

        // Budget: total limit below what was already spent blocks the next call.
        assertThat(api.put("/api/v1/projects/" + s.projectId() + "/budget",
                Map.of("totalLimitUsd", "0.005")).status()).isEqualTo(200);
        api.post("/api/v1/agents/" + s.leadId() + "/messages", Map.of("content", "Another question"));
        JsonNode blocked = awaitMessage(api, s.leadId(),
                m -> m.get("status").asString().equals("FAILED") && m.get("content").asString().equals("Another question"));
        assertThat(blocked.get("error").asString()).contains("Total budget");

        JsonNode audit = api.get("/api/v1/workspaces/" + s.workspaceId() + "/audit/verify").json();
        assertThat(audit.get("intact").asBoolean()).isTrue();
    }

    @Test
    void budgetThresholdEventFiresWhenCrossingEightyPercent() {
        Setup s = newProject();
        TestApi api = s.api();
        String providerId = api.post("/api/v1/workspaces/" + s.workspaceId() + "/providers", Map.of(
                "kind", "OPENAI_COMPATIBLE", "name", "Stub", "baseUrl", stub.baseUrl() + "/v1")).json().get("id").asString();
        api.put("/api/v1/workspaces/" + s.workspaceId() + "/model-prices", Map.of(
                "providerKind", "OPENAI_COMPATIBLE", "model", "stub-model", "inputUsdPerMtok", 2, "outputUsdPerMtok", 10));
        api.put("/api/v1/agents/" + s.leadId() + "/model", Map.of("providerId", providerId, "model", "stub-model"));
        api.put("/api/v1/projects/" + s.projectId() + "/budget", Map.of("dailyLimitUsd", "0.008"));
        api.post("/api/v1/agents/" + s.leadId() + "/messages", Map.of("content", "Hi"));
        awaitMessage(api, s.leadId(), m -> m.get("authorKind").asString().equals("AGENT"));
        String events = api.get("/api/v1/workspaces/" + s.workspaceId() + "/events?limit=500").body();
        assertThat(events).contains("budget.threshold_reached").contains("\"threshold\":\"80%\"");
    }

    @Test
    void anthropicAdapterUsesSdkWithFallbacksAndListPrice() {
        Setup s = newProject();
        TestApi api = s.api();
        Response provider = api.post("/api/v1/workspaces/" + s.workspaceId() + "/providers", Map.of(
                "kind", "ANTHROPIC", "name", "Anthropic", "baseUrl", stub.baseUrl(), "apiKey", "sk-ant-test"));
        assertThat(provider.status()).isEqualTo(201);
        String providerId = provider.json().get("id").asString();

        // Creating the first Anthropic provider binds unbound agents to the default model.
        JsonNode lead = api.get("/api/v1/agents/" + s.leadId()).json();
        assertThat(lead.get("providerId").asString()).isEqualTo(providerId);
        assertThat(lead.get("model").asString()).isEqualTo("claude-opus-5-5");

        int before = stub.requests.size();
        JsonNode test = api.post("/api/v1/workspaces/" + s.workspaceId() + "/providers/" + providerId + "/test",
                Map.of("model", "claude-opus-5-5")).json();
        assertThat(test.get("ok").asBoolean()).isTrue();
        assertThat(test.get("reply").asString()).isEqualTo("OK");
        // 12 x $4/M + 3 x $20/M = $0.000108
        assertThat(new BigDecimal(test.get("costUsd").asString())).isEqualByComparingTo("0.000108");

        StubModelServer.Captured call = stub.requests.get(before);
        assertThat(call.path()).isEqualTo("/v1/messages");
        // The SDK may send the key as x-api-key or as a bearer token; either way it must be the configured key.
        String sent = header(call, "x-api-key") != null ? header(call, "x-api-key") : header(call, "authorization");
        assertThat(sent).isIn("sk-ant-test", "Bearer sk-ant-test");
        assertThat(header(call, "anthropic-beta")).contains("server-side-fallback-2026-07-01");
        JsonNode body = TestApi.JSON.readTree(call.body());
        assertThat(body.get("fallbacks").asString()).isEqualTo("default");
        assertThat(body.get("output_config").get("effort").asString()).isEqualTo("low");
        assertThat(body.has("thinking")).isFalse();
    }

    @Test
    void nonMembersCannotReachAnotherWorkspace() {
        Setup s = newProject();
        TestApi stranger = new TestApi(port);
        stranger.register("stranger");
        assertThat(stranger.get("/api/v1/workspaces/" + s.workspaceId()).status()).isEqualTo(404);
        assertThat(stranger.get("/api/v1/projects/" + s.projectId()).status()).isEqualTo(404);
        assertThat(stranger.get("/api/v1/agents/" + s.leadId()).status()).isEqualTo(404);
        assertThat(stranger.get("/api/v1/workspaces/" + s.workspaceId() + "/events").status()).isEqualTo(404);
        assertThat(stranger.post("/api/v1/agents/" + s.leadId() + "/messages", Map.of("content", "hi")).status())
                .isEqualTo(404);
        assertThat(new TestApi(port).get("/api/v1/workspaces").status()).isEqualTo(401);
    }

    @Test
    void stateChangingRequestsRequireCsrfToken() {
        TestApi api = new TestApi(port);
        api.register("csrf");
        assertThat(api.postWithoutCsrf("/api/v1/workspaces", Map.of("name", "x")).status()).isEqualTo(403);
    }

    @Test
    void eventsAreRelayedToRedisStream() {
        Setup s = newProject();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            Long unpublished = jdbc.sql("SELECT count(*) FROM events WHERE workspace_id = ? AND published_at IS NULL")
                    .param(s.workspaceId()).query(Long.class).single();
            if (unpublished == 0) break;
            sleep(50);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM events WHERE workspace_id = ? AND published_at IS NULL")
                .param(s.workspaceId()).query(Long.class).single()).isZero();
        assertThat(redis.opsForStream().size("aiw:events")).isPositive();
    }

    @Test
    void auditLogIsAppendOnly() {
        Setup s = newProject();
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () ->
                jdbc.sql("UPDATE audit_log SET action = 'tampered' WHERE workspace_id = ?").param(s.workspaceId()).update());
    }

    private JsonNode awaitMessage(TestApi api, String agentId, Predicate<JsonNode> match) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            for (JsonNode m : api.get("/api/v1/agents/" + agentId + "/messages").json()) {
                if (match.test(m)) return m;
            }
            sleep(100);
        }
        throw new AssertionError("Message not found; history: " + api.get("/api/v1/agents/" + agentId + "/messages").body());
    }

    private List<String> eventTypes(TestApi api, String workspaceId) {
        List<String> types = new ArrayList<>();
        api.get("/api/v1/workspaces/" + workspaceId + "/events?limit=500").json().forEach(e -> types.add(e.get("type").asString()));
        return types;
    }

    private static String header(StubModelServer.Captured c, String name) {
        return c.headers().entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(e -> String.join(",", e.getValue())).findFirst().orElse(null);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
