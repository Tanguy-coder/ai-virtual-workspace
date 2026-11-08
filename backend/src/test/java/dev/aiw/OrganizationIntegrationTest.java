package dev.aiw;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aiw.support.TestApi;
import dev.aiw.support.TestcontainersConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "aiw.security.master-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "aiw.web.allowed-origins=http://localhost:5180"
})
@Import(TestcontainersConfig.class)
class OrganizationIntegrationTest {
    @LocalServerPort
    int port;

    @Test
    void recruitmentCustomRolesAndDepartmentChanges() {
        TestApi api = new TestApi(port);
        api.register("cto");
        String ws = api.post("/api/v1/workspaces", Map.of("name", "WS")).json().get("id").asString();
        String project = api.post("/api/v1/workspaces/" + ws + "/projects", Map.of("name", "P")).json().get("id").asString();

        // Hire from the catalog with a full configuration and a department override.
        TestApi.Response hired = api.post("/api/v1/projects/" + project + "/agents", Map.of(
                "roleTemplateId", "rt_senior_backend", "name", "Backend Architect 01", "department", "ARCHITECTURE",
                "seniority", "SENIOR", "simulatedYears", 12, "skills", List.of("Java", "Kafka"),
                "responsibilities", List.of("Design backend services")));
        assertThat(hired.status()).isEqualTo(201);
        JsonNode agent = hired.json();
        assertThat(agent.get("department").asString()).isEqualTo("ARCHITECTURE");
        assertThat(agent.get("simulatedYears").asInt()).isEqualTo(12);
        assertThat(agent.get("systemInstructions").asString()).contains("simulated 12 years").contains("Kafka");

        // Org chart: move the agent to another department.
        String agentId = agent.get("id").asString();
        TestApi.Response moved = api.put("/api/v1/agents/" + agentId + "/department", Map.of("department", "BACKEND"));
        assertThat(moved.json().get("department").asString()).isEqualTo("BACKEND");

        // Extensible catalog: a custom role is visible to this workspace only.
        TestApi.Response custom = api.post("/api/v1/workspaces/" + ws + "/role-templates", Map.of(
                "family", "BACKEND", "title", "Rust Engineer", "department", "BACKEND", "skills", List.of("Rust", "Tokio")));
        assertThat(custom.status()).isEqualTo(201);
        assertThat(custom.json().get("builtin").asBoolean()).isFalse();
        assertThat(api.get("/api/v1/workspaces/" + ws + "/role-templates").body()).contains("Rust Engineer");

        TestApi other = new TestApi(port);
        other.register("other");
        String otherWs = other.post("/api/v1/workspaces", Map.of("name", "Other")).json().get("id").asString();
        assertThat(other.get("/api/v1/workspaces/" + otherWs + "/role-templates").body()).doesNotContain("Rust Engineer");

        String events = api.get("/api/v1/workspaces/" + ws + "/events?limit=100").body();
        assertThat(events).contains("agent.department_changed").contains("role_template.created");
    }
}
