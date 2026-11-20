package dev.aiw.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** T-29 (2026-11-20): tests from develop broken by the branch's rewrite of src/app.js, found and explained by code. */
class RegressionsTest {
    @Test
    void aTestFromDevelopBrokenByTheBranchNamesTheFileAndTheLostExports(@TempDir Path dir) throws Exception {
        var c = dev.aiw.scm.GitService.class.getDeclaredConstructor(org.springframework.jdbc.core.simple.JdbcClient.class, String.class);
        c.setAccessible(true);
        dev.aiw.scm.GitService git = c.newInstance(null, dir.resolve("data").toString());
        Path repo = dir.resolve("repo");
        Files.createDirectories(repo.resolve("tests"));
        Files.createDirectories(repo.resolve("src"));
        sh(repo, "git", "init", "-q", "-b", "develop");
        Files.writeString(repo.resolve("src/app.js"), "export function addTask() {}\nexport function deleteTask() {}\nexport { renderTasks };\nfunction renderTasks() {}\n");
        Files.writeString(repo.resolve("tests/deleteTask.test.js"), "import { deleteTask } from '../src/app.js';\ntest('x', () => deleteTask());\n");
        sh(repo, "git", "add", "-A");
        sh(repo, "git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "develop");
        sh(repo, "git", "checkout", "-q", "-b", "task");
        // The branch rewrites app.js (uncommitted, as the agent left it) and adds its own failing test.
        Files.writeString(repo.resolve("src/app.js"), "export function addTask() {}\nexport function toggleTask() {}\n");
        Files.writeString(repo.resolve("tests/toggle.test.js"), "import { toggleTask } from '../src/app.js';\n");

        String failure = "SyntaxError: The requested module '../src/app.js' does not provide an export named 'deleteTask'\n"
                + "not ok 6 - tests/deleteTask.test.js\n  location: '/work/tests/deleteTask.test.js:1:1'\nnot ok 7 - tests/toggle.test.js";
        var found = Regressions.find(git, repo, failure);
        assertThat(found).hasSize(1); // toggle.test.js is the branch's own test, not a regression
        assertThat(found.getFirst().test()).isEqualTo("tests/deleteTask.test.js");
        assertThat(found.getFirst().culprits().getFirst().lostExports()).containsExactly("deleteTask", "renderTasks");
        String text = Regressions.explain(found, false);
        assertThat(text).contains("REGRESSION").contains("tests/deleteTask.test.js, through src/app.js (it no longer exports deleteTask, renderTasks)")
                .contains("develop's src/app.js exports: addTask, deleteTask, renderTasks").contains("\"version\": \"develop\"");
        assertThat(Regressions.explain(found, true)).contains("<file path=\"src/app.js\" version=\"develop\"").contains("2: export function deleteTask() {}");

        assertThat(Regressions.restore(git, repo, found)).containsExactly("src/app.js");
        assertThat(Files.readString(repo.resolve("src/app.js"))).contains("deleteTask");
        assertThat(Regressions.find(git, repo, failure)).isEmpty();
    }

    @Test
    void nothingWhenTheFailingTestIsTheBranchsOwn(@TempDir Path dir) {
        assertThat(Regressions.find(null, dir, "not ok 1 - tests/a.test.js")).isEmpty();
        assertThat(Regressions.explain(java.util.List.of(), true)).isEmpty();
    }

    private static void sh(Path dir, String... cmd) throws Exception {
        assertThat(new ProcessBuilder(cmd).directory(dir.toFile()).inheritIO().start().waitFor()).isZero();
    }
}
