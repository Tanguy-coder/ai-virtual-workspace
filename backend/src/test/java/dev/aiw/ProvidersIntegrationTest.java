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
        "aiw.web.allowed-origins=http://localhost:5180"
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
}
