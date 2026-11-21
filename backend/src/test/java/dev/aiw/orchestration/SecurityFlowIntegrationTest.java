package dev.aiw.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aiw.quality.SecurityService;
import dev.aiw.scm.GitService;
import dev.aiw.support.TestApi;
import dev.aiw.support.TestcontainersConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Increment 5 pass criterion (docs/18): a real scanner (Gitleaks, in its container) finds a credential on develop;
 * the finding carries its proof (file, line, masked value), its severity and its fix; a fix task is created and
 * assigned by code; develop cannot go to main while it is open; once the code is fixed, the next scan marks it fixed
 * and develop may go to main.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "aiw.security.master-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "aiw.web.allowed-origins=http://localhost:5180", "aiw.execution.auto-start=false",
        "aiw.security.scans-enabled=true", "aiw.security.static-kinds=SECRETS"
})
@Import(TestcontainersConfig.class)
class SecurityFlowIntegrationTest {
    static Path dataDir;
    static Path importDir;

    @LocalServerPort
    int port;
    @Autowired
    BranchFlow branches;
    @Autowired
    GitService git;
    @Autowired
    SecurityService security;

    @DynamicPropertySource
    static void dirs(DynamicPropertyRegistry r) throws Exception {
        dataDir = Files.createTempDirectory("aiw-sec-data");
        importDir = Files.createTempDirectory("aiw-sec-imports");
        r.add("aiw.data-dir", () -> dataDir.toString());
        r.add("aiw.import-dir", () -> importDir.toString());
        r.add("aiw.sandbox.stop-on-shutdown", () -> "true");
    }

    private JsonNode waitForScan(TestApi api, String p, int scans) throws Exception {
        Instant end = Instant.now().plus(Duration.ofMinutes(3));
        while (Instant.now().isBefore(end)) {
            JsonNode list = api.get("/api/v1/projects/" + p + "/security/scans").json();
            if (list.size() >= scans && !"RUNNING".equals(list.get(0).get("status").asString())
                    && !api.get("/api/v1/projects/" + p + "/security").json().get("scanning").asBoolean()) {
                return list.get(0);
            }
            Thread.sleep(500);
        }
        throw new AssertionError("the scan did not finish");
    }

    @Test
    void aSecretIsFoundProvenFixedByATaskAndKeepsDevelopOutOfMainUntilThen() throws Exception {
        String token = "ghp_" + "aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789";
        Path app = Files.createDirectories(importDir.resolve("leaky/src"));
        Files.writeString(app.resolve("app.js"), "const api = 'https://api.github.com';\nconst ghToken = \"" + token + "\";\nmodule.exports = { api, ghToken };\n");
        Files.writeString(importDir.resolve("leaky/package.json"), "{\"name\": \"leaky\", \"scripts\": {\"test\": \"node --test\"}}");

        TestApi api = new TestApi(port);
        api.register("cto-security");
        String ws = api.post("/api/v1/workspaces", Map.of("name", "Sec")).json().get("id").asString();
        String p = api.post("/api/v1/workspaces/" + ws + "/projects", Map.of("name", "Leaky")).json().get("id").asString();
        String dev = api.post("/api/v1/projects/" + p + "/agents", Map.of("roleTemplateId", "rt_node")).json().get("id").asString();
        assertThat(api.post("/api/v1/projects/" + p + "/repo/import", Map.of("kind", "FOLDER", "folder", "leaky")).status()).isEqualTo(200);
        Path repo = git.repo(p).orElseThrow();
        git.ensureDevelop(repo);

        // The CTO's "Scan now": Gitleaks runs on a snapshot of develop.
        assertThat(api.post("/api/v1/projects/" + p + "/security/scans", Map.of()).status()).isEqualTo(200);
        JsonNode scan = waitForScan(api, p, 1);
        assertThat(scan.get("status").asString()).isEqualTo("DONE");
        assertThat(scan.get("tool").asString()).isEqualTo("gitleaks");

        JsonNode f = api.get("/api/v1/projects/" + p + "/security/findings").json().get(0);
        assertThat(f.get("severity").asString()).isEqualTo("CRITICAL");
        assertThat(f.get("file").asString()).isEqualTo("src/app.js");
        assertThat(f.get("line").asInt()).isEqualTo(2);
        assertThat(f.get("evidence").asString()).contains("ghToken").contains("****").doesNotContain("aBcDeF");
        assertThat(f.get("recommendation").asString()).contains("environment variable");
        String findingId = f.get("id").asString();

        // Critical: a fix task, written and assigned by code, with the proof and what to do.
        assertThat(f.get("status").asString()).isEqualTo("FIX_TASK");
        JsonNode task = api.get("/api/v1/projects/" + p + "/tasks").json().get(0);
        assertThat(task.get("title").asString()).startsWith("Fix F-1:");
        assertThat(task.get("sourceKind").asString()).isEqualTo("FINDING");
        assertThat(task.get("assigneeId").asString()).isEqualTo(dev);
        assertThat(task.get("description").asString()).contains("src/app.js:2").contains("Proof:").contains("gitleaks on your branch");
        assertThat(task.get("priority").asString()).isEqualTo("CRITICAL");

        // develop cannot go to main while the finding is open.
        Path developTree = git.worktree(repo, p, GitService.DEVELOP, GitService.MAIN);
        Files.writeString(developTree.resolve("README.md"), "# Leaky\n");
        git.commitAll(developTree, "Readme", GitService.Author.PLATFORM);
        BranchFlow.MainMerge refused = branches.requestMainMerge(ws, p, null);
        assertThat(refused.requested()).isFalse();
        assertThat(refused.detail()).contains("critical or high security finding").contains("F-1 CRITICAL");
        // A fix task's submission is checked the same way: the tool reruns on the branch.
        assertThat(security.stillPresent(findingId, developTree, "HEAD")).get().asString().contains("finding F-1 is still there");

        // The fix: the value comes from the environment. The next scan sees it gone.
        Files.writeString(developTree.resolve("src/app.js"), "const api = 'https://api.github.com';\nconst ghToken = process.env.GH_TOKEN;\nmodule.exports = { api, ghToken };\n");
        git.commitAll(developTree, "Read the token from the environment", GitService.Author.PLATFORM);
        assertThat(security.stillPresent(findingId, developTree, "HEAD")).isEmpty();
        api.post("/api/v1/projects/" + p + "/security/scans", Map.of());
        assertThat(waitForScan(api, p, 2).get("summary").get("fixed").asInt()).isEqualTo(1);
        JsonNode fixed = api.get("/api/v1/projects/" + p + "/security/findings").json().get(0);
        assertThat(fixed.get("status").asString()).isEqualTo("FIXED");
        assertThat(api.get("/api/v1/projects/" + p + "/security").json().get("blocking").asInt()).isZero();
        assertThat(branches.requestMainMerge(ws, p, null).requested()).isTrue();

        // A fixed finding cannot be accepted as a risk; the audit trail and the events tell the story.
        assertThat(api.post("/api/v1/projects/" + p + "/security/findings/" + findingId + "/accept-risk", Map.of("reason", "x")).status()).isEqualTo(409);
        String events = api.get("/api/v1/workspaces/" + ws + "/events?limit=200").body();
        assertThat(events).contains("security_scan.completed").contains("finding.opened").contains("finding.fix_task").contains("finding.fixed");
    }
}
