package dev.aiw.scm;

import dev.aiw.shared.Processes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Git on the platform's disk, through the git CLI: one repository per project ({@code main} checked out in it),
 * one worktree per task branch. Agents never run git themselves; the platform commits, diffs and merges for them,
 * so every change is attributed and nothing reaches {@code main} without the merge flow.
 */
@Service
public class GitService {
    public static final String MAIN = "main";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final Map<String, String> ENV = Map.of("GIT_TERMINAL_PROMPT", "0", "LC_ALL", "C");

    private final JdbcClient jdbc;
    private final Path dataDir;

    GitService(JdbcClient jdbc, @Value("${aiw.data-dir}") String dataDir) {
        this.jdbc = jdbc;
        this.dataDir = Path.of(dataDir).toAbsolutePath();
    }

    public record Author(String name, String email) {
        public static final Author PLATFORM = new Author("AI Engineering Workspace", "platform@aiw.local");
    }

    public static class GitException extends RuntimeException {
        GitException(String message) {
            super(message);
        }
    }

    /** The project's repository, created with a README on first use. */
    public Path ensureRepo(String workspaceId, String projectId, String projectName, String description) {
        Optional<String> existing = jdbc.sql("SELECT path FROM repositories WHERE project_id = ?").param(projectId)
                .query(String.class).optional();
        if (existing.isPresent() && Files.isDirectory(Path.of(existing.get(), ".git"))) return Path.of(existing.get());
        Path repo = dataDir.resolve("repos").resolve(projectId);
        try {
            Files.createDirectories(repo);
            if (!Files.isDirectory(repo.resolve(".git"))) {
                git(repo, "init", "-q", "-b", MAIN);
                Files.writeString(repo.resolve("README.md"), "# " + projectName + "\n\n"
                        + (description == null || description.isBlank() ? "" : description.strip() + "\n"));
                Files.writeString(repo.resolve(".gitignore"), "node_modules/\n__pycache__/\n.venv/\ntarget/\ndist/\n.env\n");
                git(repo, "add", "-A");
                commitAs(repo, "Initial commit", Author.PLATFORM);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        jdbc.sql("""
                INSERT INTO repositories (project_id, workspace_id, path) VALUES (?, ?, ?)
                ON CONFLICT (project_id) DO UPDATE SET path = EXCLUDED.path
                """).params(projectId, workspaceId, repo.toString()).update();
        return repo;
    }

    public Optional<Path> repo(String projectId) {
        return jdbc.sql("SELECT path FROM repositories WHERE project_id = ?").param(projectId).query(String.class)
                .optional().map(Path::of);
    }

    /** The worktree of {@code branch}; the branch is created from main when it does not exist yet. */
    public Path worktree(Path repo, String projectId, String branch) {
        Path dir = dataDir.resolve("worktrees").resolve(projectId).resolve(branch.replace('/', '_'));
        if (Files.isDirectory(dir) && Files.exists(dir.resolve(".git"))) return dir;
        try {
            Files.createDirectories(dir.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        git(repo, "worktree", "prune");
        boolean exists = run(repo, "rev-parse", "--verify", "--quiet", "refs/heads/" + branch).ok();
        if (exists) git(repo, "worktree", "add", "-q", dir.toString(), branch);
        else git(repo, "worktree", "add", "-q", "-b", branch, dir.toString(), MAIN);
        return dir;
    }

    public boolean hasBranch(Path repo, String branch) {
        return run(repo, "rev-parse", "--verify", "--quiet", "refs/heads/" + branch).ok();
    }

    public void removeWorktree(Path repo, Path worktree) {
        run(repo, "worktree", "remove", "--force", worktree.toString());
    }

    /** Commits every change in the worktree; returns the new commit, or empty when there was nothing to commit. */
    public Optional<String> commitAll(Path worktree, String message, Author author) {
        git(worktree, "add", "-A");
        if (run(worktree, "diff", "--cached", "--quiet").ok()) return Optional.empty();
        commitAs(worktree, message, author);
        return Optional.of(head(worktree));
    }

    public String head(Path dir) {
        return git(dir, "rev-parse", "HEAD").strip();
    }

    public String revParse(Path dir, String ref) {
        return git(dir, "rev-parse", ref).strip();
    }

    public boolean dirty(Path worktree) {
        return !git(worktree, "status", "--porcelain").isBlank();
    }

    public record FileChange(String status, String path, int added, int removed) {}

    /** Files changed on the branch since it left main (three-dot), with line counts. */
    public List<FileChange> changes(Path dir, String base, String ref) {
        List<FileChange> out = new ArrayList<>();
        String status = git(dir, "diff", "--name-status", base + "..." + ref);
        String numstat = git(dir, "diff", "--numstat", base + "..." + ref);
        Map<String, int[]> counts = new java.util.HashMap<>();
        for (String l : numstat.split("\n")) {
            String[] p = l.split("\t");
            if (p.length < 3) continue;
            counts.put(p[2], new int[] {p[0].equals("-") ? 0 : Integer.parseInt(p[0]), p[1].equals("-") ? 0 : Integer.parseInt(p[1])});
        }
        for (String l : status.split("\n")) {
            String[] p = l.split("\t");
            if (p.length < 2) continue;
            String path = p[p.length - 1];
            int[] c = counts.getOrDefault(path, new int[2]);
            out.add(new FileChange(p[0].substring(0, 1), path, c[0], c[1]));
        }
        return out;
    }

    /** Unified diff of the branch since it left main, optionally for one file. */
    public String diff(Path dir, String base, String ref, String path) {
        List<String> args = new ArrayList<>(List.of("diff", "--no-color", "-U3", base + "..." + ref));
        if (path != null) {
            args.add("--");
            args.add(path);
        }
        return git(dir, args.toArray(String[]::new));
    }

    /** Would merging {@code branch} into main conflict? Checked without touching any worktree. */
    public List<String> conflictsWithMain(Path repo, String branch) {
        Processes.Result r = run(repo, "merge-tree", "--write-tree", "--name-only", "--no-messages", MAIN, branch);
        if (r.exit() == 0) return List.of();
        if (r.exit() != 1) throw new GitException("git merge-tree failed: " + r.combined());
        List<String> files = new ArrayList<>();
        String[] lines = r.out().split("\n");
        for (int i = 1; i < lines.length; i++) if (!lines[i].isBlank()) files.add(lines[i].strip());
        return files;
    }

    public record MergeOutcome(boolean clean, List<String> conflicts) {}

    /** Brings main into the branch's worktree. On conflict the markers stay in the files for the agent to resolve. */
    public MergeOutcome mergeMainInto(Path worktree, Author author) {
        if (run(worktree, "merge-base", "--is-ancestor", MAIN, "HEAD").ok()) return new MergeOutcome(true, List.of());
        Processes.Result r = Processes.run(List.of("git", "-c", "user.name=" + author.name(), "-c", "user.email=" + author.email(),
                "merge", "--no-edit", "-q", MAIN), worktree, TIMEOUT, ENV, null);
        if (r.ok()) return new MergeOutcome(true, List.of());
        String unmerged = git(worktree, "diff", "--name-only", "--diff-filter=U");
        List<String> files = unmerged.lines().filter(s -> !s.isBlank()).toList();
        if (files.isEmpty()) throw new GitException("git merge failed: " + r.combined());
        return new MergeOutcome(false, files);
    }

    public boolean mergeInProgress(Path worktree) {
        return run(worktree, "rev-parse", "-q", "--verify", "MERGE_HEAD").ok();
    }

    /** Merges the branch into main (no fast-forward, so the task stays visible in history). Returns the merge commit. */
    public String mergeIntoMain(Path repo, String branch, String message, Author author) {
        git(repo, "checkout", "-q", MAIN);
        Processes.Result r = Processes.run(List.of("git", "-c", "user.name=" + author.name(), "-c", "user.email=" + author.email(),
                "merge", "--no-ff", "-q", "-m", message, branch), repo, TIMEOUT, ENV, null);
        if (!r.ok()) {
            run(repo, "merge", "--abort");
            throw new GitException("The merge into main failed: " + r.combined());
        }
        return head(repo);
    }

    public record Branch(String name, String head, int ahead, int behind, String lastSubject, String lastDate) {}

    public List<Branch> branches(Path repo) {
        String out = git(repo, "for-each-ref", "--sort=-committerdate", "--format=%(refname:short)\t%(objectname:short)\t%(subject)\t%(committerdate:iso-strict)", "refs/heads");
        List<Branch> list = new ArrayList<>();
        for (String l : out.split("\n")) {
            String[] p = l.split("\t", -1);
            if (p.length < 4) continue;
            int ahead = 0, behind = 0;
            if (!p[0].equals(MAIN)) {
                String[] lr = git(repo, "rev-list", "--left-right", "--count", MAIN + "..." + p[0]).strip().split("\\s+");
                behind = Integer.parseInt(lr[0]);
                ahead = Integer.parseInt(lr[1]);
            }
            list.add(new Branch(p[0], p[1], ahead, behind, p[2], p[3]));
        }
        return list;
    }

    public record Commit(String sha, String author, String date, String subject) {}

    public List<Commit> log(Path repo, String ref, int max) {
        String out = git(repo, "log", "--max-count=" + max, "--format=%h\t%an\t%aI\t%s", ref);
        return out.lines().map(l -> l.split("\t", 4)).filter(p -> p.length == 4)
                .map(p -> new Commit(p[0], p[1], p[2], p[3])).toList();
    }

    public record Entry(String path, String type, long size) {}

    /** Files of a ref, recursively (blobs only). */
    public List<Entry> tree(Path repo, String ref) {
        String out = git(repo, "ls-tree", "-r", "-l", "--full-tree", ref);
        return out.lines().map(l -> {
            String[] meta = l.split("\t", 2);
            String[] m = meta[0].split("\\s+");
            return new Entry(meta[1], m[1], m[3].equals("-") ? 0 : Long.parseLong(m[3]));
        }).filter(e -> e.type().equals("blob")).toList();
    }

    public Optional<String> show(Path repo, String ref, String path) {
        Processes.Result r = run(repo, "show", ref + ":" + path);
        return r.ok() ? Optional.of(r.out()) : Optional.empty();
    }

    /** Lines matching a fixed string in the worktree, including files not yet committed. */
    public String grep(Path worktree, String query, String path, int max) {
        List<String> args = new ArrayList<>(List.of("grep", "-n", "-I", "--untracked", "-F", "-e", query));
        if (path != null && !path.isBlank()) {
            args.add("--");
            args.add(path);
        }
        Processes.Result r = run(worktree, args.toArray(String[]::new));
        if (r.exit() == 1) return "";
        if (!r.ok()) throw new GitException("git grep failed: " + r.combined());
        return r.out().lines().limit(max).reduce("", (a, b) -> a + b + "\n");
    }

    private void commitAs(Path dir, String message, Author author) {
        Processes.Result r = Processes.run(List.of("git", "-c", "user.name=" + author.name(), "-c", "user.email=" + author.email(),
                "commit", "-q", "--no-verify", "-m", message), dir, TIMEOUT, ENV, null);
        if (!r.ok()) throw new GitException("git commit failed: " + r.combined());
    }

    private String git(Path dir, String... args) {
        Processes.Result r = run(dir, args);
        if (!r.ok()) throw new GitException("git " + args[0] + " failed: " + r.combined().strip());
        return r.out();
    }

    private Processes.Result run(Path dir, String... args) {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        return Processes.run(cmd, dir, TIMEOUT, ENV, null);
    }
}
