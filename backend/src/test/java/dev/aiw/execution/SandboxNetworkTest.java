package dev.aiw.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * A package install gets the network for its duration only (docs/10). Regression: the sandbox starts on the "none"
 * network, which Docker refuses to combine with another one, so installs used to run offline (EAI_AGAIN).
 */
class SandboxNetworkTest {
    private static final String LOOKUP = "node -e \\\"require('dns').lookup('registry.npmjs.org',(e)=>console.log(e?'NET-'+'DOWN':'NET-'+'UP'))\\\"";

    @Test
    void installsAreOnlineAndEverythingElseOffline() throws Exception {
        Path dir = Files.createTempDirectory("aiw-sandbox-net");
        Files.writeString(dir.resolve("package.json"), """
                {"name": "net-check", "version": "1.0.0", "scripts": {"preinstall": "%s"}}""".formatted(LOOKUP));
        SandboxService sandboxes = new SandboxService(true, "1", "512m", 120, dir.getParent().toString(), "");
        String runId = "net-test-" + System.nanoTime();
        String id = sandboxes.ensure(runId, null, dir, "node:22-bookworm-slim");
        try {
            // Another platform on the same Docker daemon (here: another data dir) must not see it, so never cleans it up.
            assertThat(sandboxes.running()).containsKey(runId);
            Path elsewhere = Files.createTempDirectory("aiw-sandbox-other");
            assertThat(new SandboxService(true, "1", "512m", 120, elsewhere.toString(), "").running()).doesNotContainKey(runId);
            SandboxService.Command install = sandboxes.exec(id, "npm install", Duration.ofMinutes(2));
            assertThat(install.output()).contains("NET-UP");
            SandboxService.Command after = sandboxes.exec(id, LOOKUP.replace("\\\"", "\""), Duration.ofSeconds(30));
            assertThat(after.output()).contains("NET-DOWN");
        } finally {
            sandboxes.stop(id);
        }
    }
}
