package dev.aiw.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Edits small models get right: by line numbers, tolerant of indentation, with the closest lines on a miss. */
class LineEditsTest {
    private static final String FILE = """
            function init() {
              const taskInput = document.getElementById('new-task');
              list.addEventListener('click', (e) => {
                toggle(e.target);
              });
            }
            """;

    @Test
    void linesAreReplacedInsertedAndDeleted() {
        assertThat(LineEdits.replaceLines(FILE, 4, 4, "    remove(e.target);")).contains("    remove(e.target);").doesNotContain("toggle(");
        String inserted = LineEdits.replaceLines(FILE, 2, 1, "  // input");
        assertThat(inserted.lines().toList().get(1)).isEqualTo("  // input");
        assertThat(LineEdits.replaceLines(FILE, 3, 5, "").lines().count()).isEqualTo(3);
        assertThat(LineEdits.replaceLines(FILE, 1, 1, "function start() {")).endsWith("}\n");
        assertThatThrownBy(() -> LineEdits.replaceLines(FILE, 9, 9, "x")).hasMessageContaining("outside the file (6 lines)");
    }

    @Test
    void indentationDoesNotMatterWhenTheMatchIsUnique() {
        List<String> lines = FILE.lines().toList();
        assertThat(LineEdits.looseMatch(lines, "list.addEventListener('click', (e) => {\n toggle(e.target);")).isEqualTo(2);
        assertThat(LineEdits.looseMatch(lines, "nothing like this")).isEqualTo(-1);
        assertThat(LineEdits.looseMatch(List.of("x();", "y();", "x();"), "x();")).isEqualTo(-2);
    }

    /** Seen on 2026-11-18: the agent guessed a selector three times; the closest line is shown, numbered. */
    @Test
    void aMissShowsTheClosestLines() {
        List<String> lines = FILE.lines().toList();
        int near = LineEdits.closestLine(lines, "const taskInput = document.querySelector('input[name=taskInput]');");
        assertThat(near).isEqualTo(1);
        assertThat(LineEdits.numbered(lines, near, 1)).isEqualTo("""
                1: function init() {
                2:   const taskInput = document.getElementById('new-task');
                3:   list.addEventListener('click', (e) => {
                """);
    }

    @Test
    void nodeSyntaxErrorsGiveTheirLine() {
        Object[] err = LineEdits.nodeSyntaxError("""
                /work/src/app.js:70
                  }
                  ^

                SyntaxError: Unexpected token '}'
                    at wrapSafe (node:internal/modules/cjs/loader:1469:18)
                """);
        assertThat(err).containsExactly(70, "SyntaxError: Unexpected token '}'");
        assertThat(LineEdits.nodeSyntaxError("")).isNull();
    }

    @Test
    void theLeadSeesTheFilesNamedInTheFailure() {
        assertThat(LeadUnblocker.named("Error at /work/src/app.js:70 in tests/app.test.js; node_modules/x/index.js"))
                .containsExactly("src/app.js", "tests/app.test.js");
    }

    @Test
    void aFixThatChangesNothingIsSeen(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Files.writeString(dir.resolve("index.html"), "<ul id=\"task-list\"></ul>\n<p>x</p>\n");
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        // T-16: the Lead set the id the file already had.
        var same = json.readTree("[{\"path\":\"index.html\",\"from\":1,\"to\":1,\"new\":\"<ul id=\\\"task-list\\\"></ul>\"}]");
        assertThat(LeadUnblocker.edited(dir, same)).isEmpty();
        var real = json.readTree("[{\"path\":\"index.html\",\"from\":2,\"to\":1,\"new\":\"<script src=\\\"app.js\\\"></script>\"}]");
        assertThat(LeadUnblocker.edited(dir, real)).containsValue("<ul id=\"task-list\"></ul>\n<script src=\"app.js\"></script>\n<p>x</p>\n");
        assertThat(java.nio.file.Files.readString(dir.resolve("index.html"))).doesNotContain("script");
    }

    /** T-16: an agent rewrote a test from develop that then failed; the develop version comes back, a new test stays. */
    @Test
    void aTestFromDevelopThatTheBranchRewroteIsRestored(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var c = dev.aiw.scm.GitService.class.getDeclaredConstructor(org.springframework.jdbc.core.simple.JdbcClient.class, String.class);
        c.setAccessible(true);
        dev.aiw.scm.GitService git = c.newInstance(null, dir.resolve("data").toString());
        java.nio.file.Path repo = dir.resolve("repo");
        java.nio.file.Files.createDirectories(repo.resolve("tests"));
        sh(repo, "git", "init", "-q", "-b", "develop");
        java.nio.file.Files.writeString(repo.resolve("tests/app.test.js"), "test('works', () => {});\n");
        sh(repo, "git", "add", "-A");
        sh(repo, "git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "develop");
        sh(repo, "git", "checkout", "-q", "-b", "task");
        java.nio.file.Files.writeString(repo.resolve("tests/app.test.js"), "test('broken', () => { throw 1; });\n");
        java.nio.file.Files.writeString(repo.resolve("tests/new.test.js"), "test('mine', () => { throw 1; });\n");
        sh(repo, "git", "add", "-A");
        sh(repo, "git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "task");

        String failure = "not ok 3 - x\n  location: '/work/tests/app.test.js:56:1'\nnot ok 4 - y\n  location: '/work/tests/new.test.js:1:1'";
        assertThat(LeadUnblocker.restoreChangedTests(git, repo, failure)).containsExactly("tests/app.test.js");
        assertThat(java.nio.file.Files.readString(repo.resolve("tests/app.test.js"))).isEqualTo("test('works', () => {});\n");
        assertThat(java.nio.file.Files.readString(repo.resolve("tests/new.test.js"))).contains("mine");
        assertThat(git.dirty(repo)).isFalse();
        // Already the develop version: nothing more to restore.
        assertThat(LeadUnblocker.restoreChangedTests(git, repo, failure)).isEmpty();
    }

    private static void sh(java.nio.file.Path dir, String... cmd) throws Exception {
        assertThat(new ProcessBuilder(cmd).directory(dir.toFile()).inheritIO().start().waitFor()).isZero();
    }
}
