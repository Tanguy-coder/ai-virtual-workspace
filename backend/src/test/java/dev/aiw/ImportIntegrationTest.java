package dev.aiw;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aiw.support.TestApi;
import dev.aiw.support.TestcontainersConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * CTO requirement 2026-11-18 (docs/17): a project starts from existing code, a folder (with or without its own Git
 * history) or a Git repository over HTTPS. The code becomes main; a map of it becomes a specification for the Lead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "aiw.security.master-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "aiw.web.allowed-origins=http://localhost:5180", "aiw.execution.auto-start=false"
})
@Import(TestcontainersConfig.class)
class ImportIntegrationTest {
    static Path dataDir;
    static Path importDir;

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void dirs(DynamicPropertyRegistry r) throws Exception {
        dataDir = Files.createTempDirectory("aiw-import-data");
        importDir = Files.createTempDirectory("aiw-imports");
        r.add("aiw.data-dir", () -> dataDir.toString());
        r.add("aiw.import-dir", () -> importDir.toString());
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("git", "-c", "user.name=t", "-c", "user.email=t@t"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        assertThat(p.waitFor()).as(new String(p.getInputStream().readAllBytes())).isZero();
    }

    @Test
    void existingCodeBecomesTheProjectsRepository() throws Exception {
        // A plain folder: dependencies and secrets stay out.
        Path legacy = Files.createDirectories(importDir.resolve("legacy"));
        Files.writeString(legacy.resolve("package.json"), "{\"name\": \"legacy\", \"scripts\": {\"test\": \"node --test\"}, \"dependencies\": {\"express\": \"4\"}}");
        Files.createDirectories(legacy.resolve("src"));
        Files.writeString(legacy.resolve("src/app.js"), "module.exports = 1;\n");
        Files.createDirectories(legacy.resolve("node_modules/express"));
        Files.writeString(legacy.resolve("node_modules/express/index.js"), "x");
        Files.writeString(legacy.resolve(".env"), "SECRET=1");
        Files.writeString(legacy.resolve("README.md"), "# Legacy app\nServes the legacy API.\n");

        // A folder that is a Git repository on master, with history.
        Path history = Files.createDirectories(importDir.resolve("history"));
        git(history, "init", "-q", "-b", "master");
        Files.writeString(history.resolve("main.py"), "print('v1')\n");
        git(history, "add", "-A");
        git(history, "commit", "-q", "-m", "First");
        Files.writeString(history.resolve("main.py"), "print('v2')\n");
        git(history, "commit", "-q", "-am", "Second");

        TestApi api = new TestApi(port);
        api.register("cto-import");
        String ws = api.post("/api/v1/workspaces", Map.of("name", "Import")).json().get("id").asString();
        assertThat(api.get("/api/v1/workspaces/" + ws + "/import-folders").json().get("folders").toString())
                .contains("legacy").contains("history");

        String p1 = api.post("/api/v1/workspaces/" + ws + "/projects", Map.of("name", "Legacy")).json().get("id").asString();
        JsonNode done = api.post("/api/v1/projects/" + p1 + "/repo/import", Map.of("kind", "FOLDER", "folder", "legacy")).json();
        assertThat(done.get("files").asInt()).isEqualTo(3);
        assertThat(done.get("map").asString()).contains("npm run test: node --test").contains("express").contains("Legacy app")
                .contains("Plan changes to it");
        assertThat(api.get("/api/v1/projects/" + p1 + "/repo/file?ref=main&path=src/app.js").json().get("content").asString())
                .isEqualTo("module.exports = 1;\n");
        assertThat(api.get("/api/v1/projects/" + p1 + "/repo/file?ref=main&path=.env").status()).isEqualTo(404);
        assertThat(api.get("/api/v1/projects/" + p1 + "/repo/file?ref=main&path=node_modules/express/index.js").status()).isEqualTo(404);
        // The map is a specification: the Lead reads it when it plans.
        assertThat(api.get("/api/v1/projects/" + p1 + "/documents").json().toString()).contains("Existing code (repository map)");
        // Imported code is never replaced by a second import.
        assertThat(api.post("/api/v1/projects/" + p1 + "/repo/import", Map.of("kind", "FOLDER", "folder", "history")).status()).isEqualTo(409);

        String p2 = api.post("/api/v1/workspaces/" + ws + "/projects", Map.of("name", "History")).json().get("id").asString();
        JsonNode cloned = api.post("/api/v1/projects/" + p2 + "/repo/import", Map.of("kind", "FOLDER", "folder", "history")).json();
        assertThat(cloned.get("branch").asString()).isEqualTo("master");
        assertThat(api.get("/api/v1/projects/" + p2 + "/repo/file?ref=main&path=main.py").json().get("content").asString()).contains("v2");
        assertThat(api.get("/api/v1/projects/" + p2 + "/repo/log?ref=main").json().toString()).contains("First").contains("Second");

        // Only HTTPS repositories, and only folders of the import directory.
        String p3 = api.post("/api/v1/workspaces/" + ws + "/projects", Map.of("name", "Bad")).json().get("id").asString();
        assertThat(api.post("/api/v1/projects/" + p3 + "/repo/import", Map.of("kind", "GIT", "url", "file:///etc")).status()).isEqualTo(400);
        assertThat(api.post("/api/v1/projects/" + p3 + "/repo/import", Map.of("kind", "FOLDER", "folder", "../etc")).status()).isEqualTo(400);
        String events = api.get("/api/v1/workspaces/" + ws + "/events?limit=200").body();
        assertThat(events).contains("repository.imported");
    }
}
