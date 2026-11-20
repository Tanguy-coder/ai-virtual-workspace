package dev.aiw.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aiw.scm.GitService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** A model must not loop on its own syntax errors (2026-11-19: twelve broken edits in a row of one test file). */
class BrokenEditsTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static AgentTools tools(Path dir) throws Exception {
        var c = GitService.class.getDeclaredConstructor(org.springframework.jdbc.core.simple.JdbcClient.class, String.class);
        c.setAccessible(true);
        return new AgentTools(null, c.newInstance(null, dir.resolve("data").toString()));
    }

    private static AgentTools.Result write(AgentTools tools, AgentTools.Workspace ws, String content) {
        return tools.run(ws, "file.write", JSON.createObjectNode().put("path", "config.json").put("content", content));
    }

    @Test
    void aChangeThatBreaksAValidFileIsNotApplied(@TempDir Path dir) throws Exception {
        AgentTools tools = tools(dir);
        Path repo = Files.createDirectories(dir.resolve("repo"));
        var ws = new AgentTools.Workspace(repo, null, GitService.Author.PLATFORM);
        assertThat(write(tools, ws, "{\"a\": 1}\n").outcome()).isEqualTo("OK");

        AgentTools.Result broken = write(tools, ws, "{\"a\": 1,,}\n");
        assertThat(broken.outcome()).isEqualTo("ERROR");
        assertThat(broken.output()).contains("NOT APPLIED").contains("config.json is unchanged");
        assertThat(Files.readString(repo.resolve("config.json"))).isEqualTo("{\"a\": 1}\n");
        assertThat(write(tools, ws, "{\"a\": 2}\n").outcome()).isEqualTo("OK");
    }

    @Test
    void aFileThatStaysBrokenComesBackToItsLastValidVersion(@TempDir Path dir) throws Exception {
        AgentTools tools = tools(dir);
        Path repo = Files.createDirectories(dir.resolve("repo"));
        var ws = new AgentTools.Workspace(repo, null, GitService.Author.PLATFORM);
        assertThat(write(tools, ws, "{\"a\": 1}\n").outcome()).isEqualTo("OK");
        Files.writeString(repo.resolve("config.json"), "{oops\n"); // broken outside the tools (another agent, a merge)

        assertThat(write(tools, ws, "{oops 1\n").output()).contains("SYNTAX ERROR").doesNotContain("restored");
        assertThat(write(tools, ws, "{oops 2\n").output()).doesNotContain("restored");
        AgentTools.Result third = write(tools, ws, "{oops 3\n");
        assertThat(third.output()).contains(AgentTools.BROKEN_EDITS + " changes in a row").contains("restored its last version");
        assertThat(Files.readString(repo.resolve("config.json"))).isEqualTo("{\"a\": 1}\n");
    }
}
