package dev.aiw;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aiw.support.StubModelServer;
import dev.aiw.support.TestApi;
import dev.aiw.support.TestcontainersConfig;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

/**
 * Any provider can run the agents: a workspace whose only provider is OpenAI-compatible (created from a preset)
 * lists the provider's models, picks a default, and its agents are bound to it and answer through it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "aiw.security.master-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "aiw.web.allowed-origins=http://localhost:5180",
        "aiw.execution.auto-start=false", "aiw.data-dir=${java.io.tmpdir}/aiw-test-data"
})
@Import(TestcontainersConfig.class)
class ProvidersIntegrationTest {
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

    @Test
    void anOpenAiCompatibleProviderRunsTheAgents() throws Exception {
        TestApi api = new TestApi(port);
        api.register("cto");
        String ws = api.post("/api/v1/workspaces", Map.of("name", "Multi")).json().get("id").asString();
        String project = api.post("/api/v1/workspaces/" + ws + "/projects", Map.of("name", "P")).json().get("id").asString();
        JsonNode lead = api.get("/api/v1/projects/" + project + "/agents").json().get(0);
        assertThat(lead.get("providerId").isNull()).isTrue();

        // The catalog offers ready-made providers beyond Anthropic.
        JsonNode presets = api.get("/api/v1/provider-presets").json();
        assertThat(presets.toString()).contains("\"openai\"").contains("\"gemini\"").contains("\"mistral\"").contains("\"ollama\"");

        // A preset that needs a key refuses to be created without one.
        assertThat(api.post("/api/v1/workspaces/" + ws + "/providers", Map.of("preset", "mistral", "name", "Mistral"))
                .status()).isEqualTo(400);

        // A local preset with a custom URL: no key required. It is the first provider, so it becomes the default.
        Map<String, Object> body = new HashMap<>(Map.of("preset", "ollama", "name", "Local", "baseUrl", stub.baseUrl() + "/v1"));
        JsonNode provider = api.post("/api/v1/workspaces/" + ws + "/providers", body).json();
        String pid = provider.get("id").asString();
        assertThat(provider.get("kind").asString()).isEqualTo("OPENAI_COMPATIBLE");
        assertThat(provider.get("isDefault").asBoolean()).isTrue();
        assertThat(provider.get("preset").asString()).isEqualTo("ollama");

        // Models come from the provider itself (Gemini-style "models/" prefixes are removed).
        assertThat(api.get("/api/v1/workspaces/" + ws + "/providers/" + pid + "/models").json().toString())
                .isEqualTo("[\"stub-large\",\"stub-small\"]");

        // Choosing the default model binds the agents that had none.
        api.put("/api/v1/workspaces/" + ws + "/providers/" + pid + "/models",
                Map.of("defaultModel", "stub-large", "economyModel", "stub-small"));
        JsonNode bound = api.get("/api/v1/agents/" + lead.get("id").asString()).json();
        assertThat(bound.get("providerId").asString()).isEqualTo(pid);
        assertThat(bound.get("model").asString()).isEqualTo("stub-large");

        // The agent answers through the OpenAI-compatible API.
        int before = stub.requests.size();
        api.post("/api/v1/agents/" + lead.get("id").asString() + "/messages", Map.of("content", "Hello?"));
        long deadline = System.currentTimeMillis() + 20_000;
        JsonNode messages;
        do {
            Thread.sleep(150);
            messages = api.get("/api/v1/agents/" + lead.get("id").asString() + "/messages").json();
        } while (messages.size() < 2 && System.currentTimeMillis() < deadline);
        assertThat(messages.get(messages.size() - 1).get("content").asString()).isEqualTo("Hello from stub");
        StubModelServer.Captured sent = stub.requests.get(before);
        assertThat(sent.path()).isEqualTo("/v1/chat/completions");
        assertThat(TestApi.JSON.readTree(sent.body()).get("model").asString()).isEqualTo("stub-large");

        // A second provider can take over as default; agents hired afterwards use it.
        JsonNode second = api.post("/api/v1/workspaces/" + ws + "/providers", Map.of("preset", "custom", "name", "Other",
                "baseUrl", stub.baseUrl() + "/v1", "defaultModel", "stub-small")).json();
        assertThat(second.get("isDefault").asBoolean()).isFalse();
        assertThat(api.put("/api/v1/workspaces/" + ws + "/providers/" + second.get("id").asString() + "/default", Map.of())
                .json().get("isDefault").asBoolean()).isTrue();
        JsonNode providers = api.get("/api/v1/workspaces/" + ws + "/providers").json();
        assertThat(providers.findValues("isDefault").stream().filter(JsonNode::asBoolean).count()).isEqualTo(1);
    }

    /**
     * CTO requirement 2026-11-14 (docs/15): a model out of its daily quota does not stop the work. The call moves to
     * the best other model for the same kind of work, and the next calls go there directly until the quota resets.
     */
    @Test
    void aCallMovesToAnotherModelWhenItsDailyQuotaIsReached() throws Exception {
        stub.models = """
                {"object":"list","data":[
                  {"id":"openai/gpt-oss-120b","context_window":131072},
                  {"id":"moonshotai/kimi-k2-instruct-0905","context_window":262144},
                  {"id":"llama-3.1-8b-instant","context_window":131072},
                  {"id":"whisper-large-v3","context_window":448}]}
                """;
        TestApi api = new TestApi(port);
        api.register("cto-routing");
        String ws = api.post("/api/v1/workspaces", Map.of("name", "Routing")).json().get("id").asString();
        String project = api.post("/api/v1/workspaces/" + ws + "/projects", Map.of("name", "P")).json().get("id").asString();
        api.post("/api/v1/workspaces/" + ws + "/providers",
                new HashMap<>(Map.of("preset", "ollama", "name", "Local", "baseUrl", stub.baseUrl() + "/v1")));
        String leadId = api.get("/api/v1/projects/" + project + "/agents").json().get(0).get("id").asString();

        // The CTO hands the choice to the platform: the Lead decides and reviews, so the best reasoning model.
        JsonNode lead = api.post("/api/v1/agents/" + leadId + "/model/auto", Map.of()).json();
        assertThat(lead.get("model").asString()).isEqualTo("openai/gpt-oss-120b");
        assertThat(lead.get("modelReason").asString()).contains("reasoning");

        // Groq's daily quota answer for that model: the call moves instead of waiting 27 minutes.
        stub.openAiErrors.add(new Object[] {429, """
                {"error":{"message":"Rate limit reached for model `openai/gpt-oss-120b` in organization `org` service tier `on_demand` on tokens per day (TPD): Limit 200000, Used 199500, Requested 2100. Please try again in 27m0s.","type":"tokens","code":"rate_limit_exceeded"}}
                """});
        int before = stub.requests.size();
        assertThat(lastReply(api, leadId, "Hello?", 2)).isEqualTo("Hello from stub");
        java.util.List<String> models = stub.requests.subList(before, stub.requests.size()).stream()
                .filter(r -> r.path().endsWith("/chat/completions"))
                .map(r -> TestApi.JSON.readTree(r.body()).get("model").asString()).toList();
        assertThat(models).containsExactly("openai/gpt-oss-120b", "moonshotai/kimi-k2-instruct-0905");

        // The next call goes straight to the replacement: the exhausted model is not asked again.
        before = stub.requests.size();
        assertThat(lastReply(api, leadId, "Again?", 4)).isEqualTo("Hello from stub");
        assertThat(stub.requests.subList(before, stub.requests.size()).stream()
                .filter(r -> r.path().endsWith("/chat/completions"))
                .map(r -> TestApi.JSON.readTree(r.body()).get("model").asString()).toList())
                .containsExactly("moonshotai/kimi-k2-instruct-0905");
        // T-27: a model that refuses max_tokens is asked again with max_completion_tokens, and from then on directly.
        stub.openAiErrors.add(new Object[] {400, """
                {"error":{"message":"Unsupported parameter: 'max_tokens' is not supported with this model. Use 'max_completion_tokens' instead.","type":"invalid_request_error","param":"max_tokens","code":"unsupported_parameter"}}
                """});
        before = stub.requests.size();
        assertThat(lastReply(api, leadId, "Reasoning?", 6)).isEqualTo("Hello from stub");
        java.util.List<String> bodies = stub.requests.subList(before, stub.requests.size()).stream()
                .filter(r -> r.path().endsWith("/chat/completions")).map(r -> r.body()).toList();
        assertThat(bodies).hasSize(2);
        assertThat(bodies.get(0)).contains("\"max_tokens\"");
        assertThat(bodies.get(1)).contains("\"max_completion_tokens\"").doesNotContain("\"max_tokens\"");
        before = stub.requests.size();
        assertThat(lastReply(api, leadId, "Again, reasoning?", 8)).isEqualTo("Hello from stub");
        assertThat(stub.requests.subList(before, stub.requests.size()).stream()
                .filter(r -> r.path().endsWith("/chat/completions")).map(r -> r.body()).toList())
                .allMatch(b -> b.contains("\"max_completion_tokens\""));

        // The agent keeps its own model: it comes back to it once the quota resets.
        assertThat(api.get("/api/v1/agents/" + leadId).json().get("model").asString()).isEqualTo("openai/gpt-oss-120b");
    }

    /** Seen live on 2026-11-14: Groq out of daily quota, OpenRouter out of credits; the call must end on Groq's next best. */
    @Test
    void aProviderWithoutCreditsIsSkippedForTheNextBestModel() throws Exception {
        try (StubModelServer groq = new StubModelServer(); StubModelServer openrouter = new StubModelServer()) {
            groq.models = """
                    {"object":"list","data":[{"id":"openai/gpt-oss-120b","active":true,"context_window":131072},
                      {"id":"moonshotai/kimi-k2-instruct-0905","active":true,"context_window":262144}]}
                    """;
            openrouter.models = """
                    {"data":[{"id":"openai/gpt-5.6-luna","context_length":400000,"pricing":{"prompt":"0.0000002","completion":"0.0000008"}}]}
                    """;
            TestApi api = new TestApi(port);
            api.register("cto-credits");
            String ws = api.post("/api/v1/workspaces", Map.of("name", "Credits")).json().get("id").asString();
            String project = api.post("/api/v1/workspaces/" + ws + "/projects", Map.of("name", "P")).json().get("id").asString();
            String g = api.post("/api/v1/workspaces/" + ws + "/providers",
                    new HashMap<>(Map.of("preset", "ollama", "name", "Groq", "baseUrl", groq.baseUrl() + "/v1"))).json().get("id").asString();
            api.post("/api/v1/workspaces/" + ws + "/providers",
                    new HashMap<>(Map.of("preset", "lmstudio", "name", "OpenRouter", "baseUrl", openrouter.baseUrl() + "/v1")));
            String leadId = api.get("/api/v1/projects/" + project + "/agents").json().get(0).get("id").asString();
            api.put("/api/v1/agents/" + leadId + "/model", Map.of("providerId", g, "model", "openai/gpt-oss-120b"));

            groq.openAiErrors.add(new Object[] {429, """
                    {"error":{"message":"Rate limit reached for model `openai/gpt-oss-120b` on tokens per day (TPD): Limit 200000, Used 199500, Requested 2100. Please try again in 27m0s.","code":"rate_limit_exceeded"}}
                    """});
            openrouter.openAiErrors.add(new Object[] {402, """
                    {"error":{"message":"Insufficient credits. This account never purchased credits.","code":402}}
                    """});
            assertThat(lastReply(api, leadId, "Hello?", 2)).isEqualTo("Hello from stub");
            assertThat(groq.requests.stream().filter(r -> r.path().endsWith("/chat/completions"))
                    .map(r -> TestApi.JSON.readTree(r.body()).get("model").asString()).toList())
                    .containsExactly("openai/gpt-oss-120b", "moonshotai/kimi-k2-instruct-0905");
        }
    }

    private static String lastReply(TestApi api, String agentId, String content, int expected) throws Exception {
        api.post("/api/v1/agents/" + agentId + "/messages", Map.of("content", content));
        long deadline = System.currentTimeMillis() + 20_000;
        JsonNode messages;
        do {
            Thread.sleep(150);
            messages = api.get("/api/v1/agents/" + agentId + "/messages").json();
        } while (messages.size() < expected && System.currentTimeMillis() < deadline);
        return messages.get(messages.size() - 1).get("content").asString();
    }
}
