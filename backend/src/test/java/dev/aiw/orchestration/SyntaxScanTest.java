package dev.aiw.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aiw.execution.SandboxService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * T-28 (2026-11-19): the tests failed with "Unexpected token '}'" at their own location while the broken file was
 * app.js, line 149, broken by two fixes of the Lead that no syntax check saw.
 */
class SyntaxScanTest {
    @Test
    void aFailureNamesTheFileThatDoesNotParse() throws Exception {
        Path dir = Files.createTempDirectory("aiw-syntax-scan");
        Files.writeString(dir.resolve("package.json"), "{\"type\": \"module\"}");
        Files.writeString(dir.resolve("app.js"), "export function a() {\n  return 1;\n}\n}\n");
        Files.createDirectories(dir.resolve("tests"));
        Files.writeString(dir.resolve("tests/a.test.js"), "import { a } from '../app.js';\n");
        Files.createDirectories(dir.resolve("node_modules/x"));
        Files.writeString(dir.resolve("node_modules/x/index.js"), "}}}");
        var ctor = java.util.Arrays.stream(SandboxService.class.getDeclaredConstructors())
                .filter(c -> c.getParameterCount() == 6).findFirst().orElseThrow();
        ctor.setAccessible(true);
        SandboxService sandboxes = (SandboxService) ctor.newInstance(true, "1", "512m", 120, dir.getParent().toString(), "");
        String id = sandboxes.ensure("scan-test-" + System.nanoTime(), null, dir, "node:22-bookworm-slim");
        try {
            String scan = AgentTools.syntaxScan(sandboxes, id, dir);
            assertThat(LeadUnblocker.scannedFiles(scan)).containsExactly("app.js");
            assertThat(scan)
                    .startsWith("Files with a SYNTAX ERROR")
                    .contains("- app.js line 4: SyntaxError: Unexpected token '}'")
                    .doesNotContain("tests/a.test.js").doesNotContain("node_modules");
            Files.writeString(dir.resolve("app.js"), "export function a() {\n  return 1;\n}\n");
            assertThat(AgentTools.syntaxScan(sandboxes, id, dir)).isEmpty();
        } finally {
            sandboxes.stop(id);
        }
    }

    @Test
    void aFixThatTurnsCodeIntoCommentsIsRefused() throws Exception {
        Path dir = Files.createTempDirectory("aiw-commented");
        Files.writeString(dir.resolve("app.js"), "const li = make();\nli.className = 'x';\nlist.appendChild(li);\n");
        var json = JsonMapper.builder().build();
        var placeholder = json.readTree("[{\"path\":\"app.js\",\"from\":2,\"to\":3,\"new\":\"  // Fix unfinished template literal here\\n  // Ensure functions are closed\"}]");
        assertThat(LeadUnblocker.commentedOut(placeholder, dir)).contains("deletes the code instead of fixing it");
        var real = json.readTree("[{\"path\":\"app.js\",\"from\":2,\"to\":2,\"new\":\"li.className = 'done';\"}]");
        assertThat(LeadUnblocker.commentedOut(real, dir)).isNull();
        var note = json.readTree("[{\"path\":\"app.js\",\"from\":2,\"to\":1,\"new\":\"// renders one task\"}]");
        assertThat(LeadUnblocker.commentedOut(note, dir)).isNull();
    }
}
