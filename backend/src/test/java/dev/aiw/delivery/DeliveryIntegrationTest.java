package dev.aiw.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aiw.orchestration.MainMerged;
import dev.aiw.scm.GitService;
import dev.aiw.support.TestApi;
import dev.aiw.support.TestcontainersConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Increment 6 pass criterion (docs/18): an approved deployment of the project's own Dockerfile runs healthy on the test
 * environment (a real container, reached on its host port). The Lead's request waits for a human; a failed version
 * leaves the previous one running; rollback, release tag, members and the metrics endpoint are covered too.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "aiw.security.master-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "aiw.web.allowed-origins=http://localhost:5180", "aiw.execution.auto-start=false",
        "aiw.security.metrics-token=scrape-secret", "aiw.delivery.health-timeout-seconds=40"
})
@Import(TestcontainersConfig.class)
class DeliveryIntegrationTest {
    static Path dataDir;
    static Path importDir;

    @LocalServerPort
    int port;
    @Autowired
    GitService git;
    @Autowired
    ApplicationEventPublisher appEvents;
    @Autowired
    TransactionTemplate tx;

    @DynamicPropertySource
    static void dirs(DynamicPropertyRegistry r) throws Exception {
        dataDir = Files.createTempDirectory("aiw-delivery-data");
        importDir = Files.createTempDirectory("aiw-delivery-imports");
        r.add("aiw.data-dir", () -> dataDir.toString());
        r.add("aiw.import-dir", () -> importDir.toString());
        r.add("aiw.sandbox.stop-on-shutdown", () -> "true");
    }

    private static final String SERVER = """
            const http = require('http');
            const version = '%s';
            http.createServer((req, res) => {
              if (req.url === '/health') { res.writeHead(200, {'Content-Type': 'application/json'}); return res.end(JSON.stringify({ok: true, version})); }
              res.writeHead(200, {'Content-Type': 'text/plain'}); res.end('booking ' + version);
            }).listen(process.env.PORT || 3000);
            """;

    private JsonNode waitFor(TestApi api, String p, int number, String... statuses) throws Exception {
        Instant end = Instant.now().plus(Duration.ofMinutes(4));
        while (Instant.now().isBefore(end)) {
            for (JsonNode d : api.get("/api/v1/projects/" + p + "/delivery").json().get("deployments")) {
                if (d.get("number").asInt() == number && java.util.List.of(statuses).contains(d.get("status").asString())) return d;
            }
            Thread.sleep(1_000);
        }
        throw new AssertionError("deployment d-" + number + " never reached " + String.join("/", statuses));
    }

    private static String fetch(String url) throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString());
        return r.statusCode() + " " + r.body();
    }

    @Test
    void anApprovedDeploymentRunsHealthyOnTheTestEnvironment() throws Exception {
        Path app = Files.createDirectories(importDir.resolve("booking"));
        Files.writeString(app.resolve("server.js"), SERVER.formatted("v1"));
        Files.writeString(app.resolve("Dockerfile"), "FROM node:22-bookworm-slim\nWORKDIR /app\nCOPY server.js .\nEXPOSE 3000\nCMD [\"node\", \"server.js\"]\n");

        TestApi api = new TestApi(port);
        api.register("cto-delivery");
        String ws = api.post("/api/v1/workspaces", Map.of("name", "Delivery")).json().get("id").asString();
        String p = api.post("/api/v1/workspaces/" + ws + "/projects", Map.of("name", "Booking")).json().get("id").asString();
        assertThat(api.post("/api/v1/projects/" + p + "/repo/import", Map.of("kind", "FOLDER", "folder", "booking")).status()).isEqualTo(200);
        Path repo = git.repo(p).orElseThrow();

        // 1. The CTO deploys main: his click is the approval. The project's Dockerfile is built and runs on the host.
        JsonNode d1 = api.post("/api/v1/projects/" + p + "/deployments", Map.of("ref", "main")).json();
        assertThat(d1.get("status").asString()).isEqualTo("QUEUED");
        JsonNode healthy = waitFor(api, p, 1, "HEALTHY", "FAILED");
        assertThat(healthy.get("status").asString()).as(api.get("/api/v1/deployments/" + healthy.get("id").asString() + "/log").body()).isEqualTo("HEALTHY");
        JsonNode env = api.get("/api/v1/projects/" + p + "/delivery").json().get("environments").get(0);
        assertThat(env.get("status").asString()).isEqualTo("HEALTHY");
        String url = env.get("url").asString();
        assertThat(url).startsWith("http://localhost:195");
        assertThat(fetch(url + "/health")).startsWith("200").contains("\"version\":\"v1\"");
        assertThat(api.get("/api/v1/deployments/" + healthy.get("id").asString() + "/log").json().get("log").asString())
                .contains("docker build").contains("Health check: HTTP 200");

        // 2. develop reaches main: the Lead asks; nothing runs until a human approves in the Inbox.
        Path tree = git.worktree(repo, p, GitService.DEVELOP, GitService.MAIN);
        Files.writeString(tree.resolve("server.js"), SERVER.formatted("v2"));
        git.commitAll(tree, "v2", GitService.Author.PLATFORM);
        String v2 = git.mergeIntoMain(repo, GitService.DEVELOP, "Merge !1: Show version two", GitService.Author.PLATFORM);
        tx.executeWithoutResult(s -> appEvents.publishEvent(new MainMerged(ws, p, v2, null)));
        JsonNode approval = null;
        for (JsonNode a : api.get("/api/v1/workspaces/" + ws + "/approvals").json()) if ("deployment.start".equals(a.get("action").asString())) approval = a;
        assertThat(approval).isNotNull();
        assertThat(waitFor(api, p, 2, "PENDING_APPROVAL").get("commitSha").asString()).isEqualTo(v2);
        assertThat(fetch(url + "/health")).contains("v1");
        api.post("/api/v1/workspaces/" + ws + "/approvals/" + approval.get("id").asString() + "/decision", Map.of("approve", true));
        waitFor(api, p, 2, "HEALTHY");
        assertThat(fetch(url + "/health")).contains("\"version\":\"v2\"");
        assertThat(waitFor(api, p, 1, "SUPERSEDED")).isNotNull();

        // 3. A version that does not start leaves the previous one serving.
        Files.writeString(tree.resolve("server.js"), "process.exit(1);\n");
        git.commitAll(tree, "broken", GitService.Author.PLATFORM);
        git.mergeIntoMain(repo, GitService.DEVELOP, "Merge !2: Break it", GitService.Author.PLATFORM);
        api.post("/api/v1/projects/" + p + "/deployments", Map.of("ref", "main"));
        JsonNode broken = waitFor(api, p, 3, "FAILED");
        assertThat(broken.get("error").asString()).contains("health check");
        Thread.sleep(2_000);
        assertThat(fetch(url + "/health")).contains("\"version\":\"v2\"");

        // 4. Rollback to v1 (a healthy deployment's image), then a release tag with notes from the merged tasks.
        api.post("/api/v1/projects/" + p + "/deployments/" + healthy.get("id").asString() + "/rollback", Map.of());
        assertThat(waitFor(api, p, 4, "HEALTHY").get("rollbackOf").asString()).isEqualTo(healthy.get("id").asString());
        assertThat(fetch(url + "/health")).contains("\"version\":\"v1\"");
        JsonNode proposal = api.get("/api/v1/projects/" + p + "/delivery").json().get("proposal");
        assertThat(proposal.get("next").get("minor").asString()).isEqualTo("0.1.0");
        assertThat(proposal.get("notes").asString()).contains("- Show version two (!1)").contains("- Break it (!2)");
        JsonNode release = api.post("/api/v1/projects/" + p + "/releases", Map.of("bump", "minor")).json();
        assertThat(release.get("status").asString()).isEqualTo("RELEASED");
        assertThat(git.tags(repo)).contains("v0.1.0");
        assertThat(api.post("/api/v1/projects/" + p + "/releases", Map.of("bump", "patch")).status()).isEqualTo(409);
        // Only HTTPS remotes; the token is never returned.
        assertThat(api.put("/api/v1/projects/" + p + "/remote", Map.of("provider", "GITLAB", "url", "file:///etc", "token", "x")).status()).isEqualTo(400);
        JsonNode remote = api.put("/api/v1/projects/" + p + "/remote", Map.of("provider", "GITLAB", "url", "https://gitlab.example.test/o/r.git", "token", "glpat-secret")).json();
        assertThat(remote.toString()).contains("\"hasToken\":true").doesNotContain("glpat-secret");

        api.post("/api/v1/projects/" + p + "/environments/test/stop", Map.of());
        assertThat(api.get("/api/v1/projects/" + p + "/delivery").json().get("environments").get(0).get("status").asString()).isEqualTo("STOPPED");
        String events = api.get("/api/v1/workspaces/" + ws + "/events?limit=300").body();
        assertThat(events).contains("deployment.healthy").contains("deployment.failed").contains("deployment.restored").contains("release.created");
    }

    @Test
    void severalHumansShareAWorkspaceWithRoles() {
        TestApi owner = new TestApi(port);
        owner.register("owner");
        String ws = owner.post("/api/v1/workspaces", Map.of("name", "Team")).json().get("id").asString();
        TestApi guest = new TestApi(port);
        String guestId = guest.register("guest");
        String guestEmail = guest.get("/api/v1/auth/me").json().get("email").asString();

        JsonNode inv = owner.post("/api/v1/workspaces/" + ws + "/invitations", Map.of("email", guestEmail, "role", "VIEWER")).json();
        String token = inv.get("token").asString();
        // The link says what it is for before signing in; it works for the invited address only, once.
        assertThat(new TestApi(port).get("/api/v1/invitations/" + token).json().get("workspaceName").asString()).isEqualTo("Team");
        TestApi stranger = new TestApi(port);
        stranger.register("stranger");
        assertThat(stranger.post("/api/v1/invitations/" + token + "/accept", Map.of()).status()).isEqualTo(403);
        assertThat(guest.post("/api/v1/invitations/" + token + "/accept", Map.of()).json().get("role").asString()).isEqualTo("VIEWER");
        assertThat(guest.post("/api/v1/invitations/" + token + "/accept", Map.of()).status()).isEqualTo(409);

        // A viewer reads but does not decide or create.
        assertThat(guest.get("/api/v1/workspaces/" + ws + "/members").json().size()).isEqualTo(2);
        assertThat(guest.post("/api/v1/workspaces/" + ws + "/projects", Map.of("name", "X")).status()).isEqualTo(403);
        assertThat(guest.post("/api/v1/workspaces/" + ws + "/invitations", Map.of("email", "a@b.cd", "role", "MEMBER")).status()).isEqualTo(403);

        // Promoted to admin, the guest manages members but not the owner; the workspace always keeps an owner.
        assertThat(owner.put("/api/v1/workspaces/" + ws + "/members/" + guestId, Map.of("role", "ADMIN")).status()).isEqualTo(200);
        String ownerId = owner.get("/api/v1/auth/me").json().get("id").asString();
        assertThat(guest.put("/api/v1/workspaces/" + ws + "/members/" + ownerId, Map.of("role", "MEMBER")).status()).isEqualTo(403);
        assertThat(owner.delete("/api/v1/workspaces/" + ws + "/members/" + ownerId).status()).isEqualTo(409);
        assertThat(guest.delete("/api/v1/workspaces/" + ws + "/members/" + guestId).status()).isEqualTo(200);
        assertThat(owner.get("/api/v1/workspaces/" + ws + "/members").json().size()).isEqualTo(1);
    }

    @Test
    void prometheusReadsTheMetricsWithItsTokenOnly() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        URI uri = URI.create("http://localhost:" + port + "/actuator/prometheus");
        assertThat(http.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertThat(http.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer wrong").build(), HttpResponse.BodyHandlers.ofString()).statusCode())
                .isIn(401, 403);
        HttpResponse<String> ok = http.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer scrape-secret").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).contains("aiw_model_tokens_total").contains("aiw_findings_open").contains("aiw_runs");
    }
}
