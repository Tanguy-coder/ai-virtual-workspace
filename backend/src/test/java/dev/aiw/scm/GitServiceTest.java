package dev.aiw.scm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import dev.aiw.shared.Processes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Branches, conflict detection and merges on a real repository. */
class GitServiceTest {
    @TempDir
    Path dir;

    private static final GitService.Author A = new GitService.Author("Agent A", "a@aiw.local");
    private static final GitService.Author B = new GitService.Author("Agent B", "b@aiw.local");

    private Path repo(GitService git) throws Exception {
        Path repo = dir.resolve("repo");
        Files.createDirectories(repo);
        Processes.run(List.of("git", "init", "-q", "-b", "main"), repo, Duration.ofSeconds(10));
        Files.writeString(repo.resolve("app.js"), "function render() {\n  return 'v1';\n}\n");
        Processes.run(List.of("git", "add", "-A"), repo, Duration.ofSeconds(10));
        Processes.run(List.of("git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "init"), repo, Duration.ofSeconds(10));
        return repo;
    }

    @Test
    void detectsAConflictBeforeMergingAndLeavesMarkersForTheAgent() throws Exception {
        GitService git = new GitService(null, dir.toString());
        Path repo = repo(git);
        Path a = git.worktree(repo, "p", "agent/a/t-1-render");
        Path b = git.worktree(repo, "p", "agent/b/t-2-render");
        Files.writeString(a.resolve("app.js"), "function render() {\n  return 'from A';\n}\n");
        Files.writeString(b.resolve("app.js"), "function render() {\n  return 'from B';\n}\n");
        assertThat(git.commitAll(a, "A", A)).isPresent();
        assertThat(git.commitAll(b, "B", B)).isPresent();
        assertThat(git.commitAll(b, "nothing", B)).isEmpty();

        assertThat(git.conflictsWithMain(repo, "agent/a/t-1-render")).isEmpty();
        git.mergeIntoMain(repo, "agent/a/t-1-render", "Merge !1", A);
        assertThat(Files.readString(repo.resolve("app.js"))).contains("from A");

        // B now conflicts with main: detected without touching any worktree, then surfaced in B's worktree.
        assertThat(git.conflictsWithMain(repo, "agent/b/t-2-render")).containsExactly("app.js");
        GitService.MergeOutcome outcome = git.mergeMainInto(b, B);
        assertThat(outcome.clean()).isFalse();
        assertThat(outcome.conflicts()).containsExactly("app.js");
        assertThat(Files.readString(b.resolve("app.js"))).contains("<<<<<<<").contains("from A").contains("from B");

        // B resolves, commits: no more conflict, and main still has A's work until B is merged.
        Files.writeString(b.resolve("app.js"), "function render() {\n  return 'from A and B';\n}\n");
        git.commitAll(b, "Resolve", B);
        assertThat(git.conflictsWithMain(repo, "agent/b/t-2-render")).isEmpty();
        assertThat(git.changes(b, "main", "HEAD")).extracting(GitService.FileChange::path).containsExactly("app.js");
        assertThat(git.branches(repo)).extracting(GitService.Branch::name).contains("main", "agent/a/t-1-render", "agent/b/t-2-render");
    }
}
