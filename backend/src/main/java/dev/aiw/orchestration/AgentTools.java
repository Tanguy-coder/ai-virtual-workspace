package dev.aiw.orchestration;

import dev.aiw.execution.SandboxService;
import dev.aiw.scm.GitService;
import dev.aiw.quality.TestOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The agent's tools (docs/10, T7). File tools act on the task's worktree from the platform, commands run in the
 * sandbox. Results sent back to the model are bounded: line ranges, matches, failures only.
 */
@Component
class AgentTools {
    static final String MANUAL = """
            <tools>
            Reply with JSON: {"thought": "one short sentence", "actions": [{"tool": "...", "args": {...}}]}
            1 to 5 actions per reply; they run in order and stop at the first error. Paths are relative to the repository root.
            - repo.tree {"path": ".", "depth": 3}: files and folders, no content.
            - code.search {"query": "exact text", "path": "optional folder"}: matching lines with numbers.
            - file.read {"path": "src/app.js", "from": 1, "to": 150}: numbered lines, at most 200 per call.
            - file.write {"path": "...", "content": "..."}: create a file, or replace a small file entirely.
            - file.edit {"path": "...", "old": "exact existing text", "new": "replacement"}: old must appear exactly once.
            - file.delete {"path": "..."}
            - exec {"command": "npm test"}: one command in your sandbox, no shell operators. Allowed programs: %s.
              Package installs (npm install, pip install) get network access; nothing else does.
            - git.commit {"message": "..."}: commit everything on your branch.
            - task.submit {"summary": "what you did and how each acceptance criterion is proven"}: the platform runs the \
            tests, then asks the Lead to review: if the Lead approves, your branch merges into develop. Commit first.
            - task.block {"reason": "..."}: you cannot continue without someone else; the Lead is told.
            - message.send {"to": "Lead or an exact teammate name", "content": "..."}
            - memory.record {"title": "...", "content": "..."}: a decision the whole team must know.
            </tools>
            <rules>
            - Work only in this repository, on your branch. Read only what you need; prefer code.search and line ranges.
            - Prove every acceptance criterion with an automated test. The project must have a test command: for a \
            plain JavaScript project, a package.json with "scripts": {"test": "node --test"} and tests in tests/*.test.js \
            (node:test and node:assert, no dependency) is enough.
            - Edit with file.edit; use file.write for new files. Never rewrite a whole file to change a few lines.
            - Do not touch CI files, Dockerfiles or .env files: they need the CTO's approval (use task.block to ask).
            - Be brief in thoughts, commit messages and summaries.
            </rules>
            """.formatted(String.join(", ", SandboxService.ALLOWED.stream().sorted().toList()));

    static final Set<String> TOOLS = Set.of("repo.tree", "code.search", "file.read", "file.write", "file.edit",
            "file.delete", "exec", "git.commit", "task.submit", "task.block", "message.send", "memory.record");
    private static final Set<String> SKIP = Set.of(".git", "node_modules", "__pycache__", ".venv", "target", "dist");
    private static final int MAX_READ_LINES = 200;
    private static final int MAX_OUTPUT_CHARS = 6_000;

    private final SandboxService sandboxes;
    private final GitService git;

    AgentTools(SandboxService sandboxes, GitService git) {
        this.sandboxes = sandboxes;
        this.git = git;
    }

    enum End { NONE, SUBMIT, BLOCK }

    /**
     * Result of one call. {@code output} goes back to the model (bounded); {@code detail} is kept for the UI
     * (e.g. the diff of an edit). Collaboration tools are executed by the caller, which owns those services.
     */
    record Result(String outcome, String summary, String output, Map<String, Object> detail, End end) {
        static Result ok(String summary, String output) {
            return new Result("OK", summary, output, Map.of(), End.NONE);
        }

        static Result error(String summary, String output) {
            return new Result("ERROR", summary, output, Map.of(), End.NONE);
        }

        static Result refused(String summary, String why) {
            return new Result("REFUSED", summary, why, Map.of(), End.NONE);
        }

        Result withDetail(Map<String, Object> d) {
            return new Result(outcome, summary, output, d, end);
        }
    }

    record Workspace(Path worktree, String sandboxId, GitService.Author author) {}

    Result run(Workspace ws, String tool, JsonNode args) {
        try {
            return switch (tool) {
                case "repo.tree" -> tree(ws, text(args, "path", "."), args.path("depth").asInt(3));
                case "code.search" -> search(ws, text(args, "query", ""), text(args, "path", null));
                case "file.read" -> read(ws, text(args, "path", ""), args.path("from").asInt(1), args.path("to").asInt(0));
                case "file.write" -> write(ws, text(args, "path", ""), text(args, "content", null));
                case "file.edit" -> edit(ws, text(args, "path", ""), text(args, "old", null), text(args, "new", null));
                case "file.delete" -> delete(ws, text(args, "path", ""));
                case "exec" -> exec(ws, text(args, "command", ""));
                case "git.commit" -> commit(ws, text(args, "message", "Work in progress"));
                default -> Result.refused(tool, "Unknown tool " + tool + ".");
            };
        } catch (IOException e) {
            return Result.error(tool + " failed", e.getMessage());
        } catch (GitService.GitException | SandboxService.SandboxException e) {
            return Result.error(tool + " failed", e.getMessage());
        }
    }

    private Result tree(Workspace ws, String path, int depth) throws IOException {
        Path root = resolve(ws, path);
        if (root == null) return Result.refused("repo.tree " + path, "Path outside the repository.");
        if (!Files.isDirectory(root)) return Result.error("repo.tree " + path, "No such folder.");
        int max = Math.min(Math.max(depth, 1), 6);
        List<String> lines = new ArrayList<>();
        Files.walkFileTree(root, Set.of(), max, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && SKIP.contains(dir.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                if (!dir.equals(root)) lines.add(ws.worktree().relativize(dir) + "/");
                return lines.size() > 300 ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isDirectory()) lines.add(ws.worktree().relativize(file) + "  (" + attrs.size() + " B)");
                return lines.size() > 300 ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
        });
        lines.sort(String::compareTo);
        return Result.ok("repo.tree " + path + " · " + lines.size() + " entries", lines.isEmpty() ? "(empty)" : String.join("\n", lines));
    }

    private Result search(Workspace ws, String query, String path) {
        if (query.isBlank()) return Result.refused("code.search", "Give a query.");
        if (path != null && resolve(ws, path) == null) return Result.refused("code.search", "Path outside the repository.");
        String hits = git.grep(ws.worktree(), query, path, 50);
        long n = hits.lines().count();
        return Result.ok("code.search \"" + query + "\" · " + n + " hits", hits.isBlank() ? "No match." : clip(hits));
    }

    private Result read(Workspace ws, String path, int from, int to) throws IOException {
        Path file = resolve(ws, path);
        if (file == null) return Result.refused("file.read " + path, "Path outside the repository.");
        if (!Files.isRegularFile(file)) return Result.error("file.read " + path, "No such file.");
        List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
        int start = Math.max(1, from), end = to <= 0 ? Math.min(all.size(), start + MAX_READ_LINES - 1) : Math.min(to, all.size());
        end = Math.min(end, start + MAX_READ_LINES - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i <= end; i++) sb.append(i).append(": ").append(all.get(i - 1)).append('\n');
        String note = end < all.size() ? "\n[" + (all.size() - end) + " more lines; read from " + (end + 1) + "]" : "";
        return Result.ok("file.read " + path + " lines " + start + "–" + end + " of " + all.size(),
                all.isEmpty() ? "(empty file)" : sb + note);
    }

    private Result write(Workspace ws, String path, String content) throws IOException {
        Path file = resolve(ws, path);
        if (file == null) return Result.refused("file.write " + path, "Path outside the repository.");
        String sensitive = sensitive(path);
        if (sensitive != null) return Result.refused("file.write " + path, sensitive);
        if (content == null) return Result.refused("file.write " + path, "Give the content.");
        boolean existed = Files.exists(file);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        long lines = content.lines().count();
        return Result.ok("file.write " + path + " · " + (existed ? "replaced" : "created") + " · " + lines + " lines", "Written.")
                .withDetail(Map.of("path", path, "added", lines, "created", !existed, "excerpt", clip(content, 1_500)));
    }

    private Result edit(Workspace ws, String path, String old, String replacement) throws IOException {
        Path file = resolve(ws, path);
        if (file == null) return Result.refused("file.edit " + path, "Path outside the repository.");
        String sensitive = sensitive(path);
        if (sensitive != null) return Result.refused("file.edit " + path, sensitive);
        if (!Files.isRegularFile(file)) return Result.error("file.edit " + path, "No such file; use file.write to create it.");
        if (old == null || old.isEmpty() || replacement == null) return Result.refused("file.edit " + path, "Give old and new.");
        String content = Files.readString(file, StandardCharsets.UTF_8);
        int at = content.indexOf(old);
        if (at < 0) return Result.error("file.edit " + path, "old text not found. Read the exact lines first.");
        if (content.indexOf(old, at + 1) >= 0) return Result.error("file.edit " + path, "old text appears more than once; include more context.");
        Files.writeString(file, content.substring(0, at) + replacement + content.substring(at + old.length()), StandardCharsets.UTF_8);
        long added = replacement.lines().count(), removed = old.lines().count();
        int line = (int) content.substring(0, at).chars().filter(c -> c == '\n').count() + 1;
        Map<String, Object> detail = new HashMap<>();
        detail.put("path", path);
        detail.put("line", line);
        detail.put("old", clip(old, 1_500));
        detail.put("new", clip(replacement, 1_500));
        return Result.ok("file.edit " + path + " · +" + added + " −" + removed + " at line " + line, "Edited.").withDetail(detail);
    }

    private Result delete(Workspace ws, String path) throws IOException {
        Path file = resolve(ws, path);
        if (file == null) return Result.refused("file.delete " + path, "Path outside the repository.");
        String sensitive = sensitive(path);
        if (sensitive != null) return Result.refused("file.delete " + path, sensitive);
        if (!Files.isRegularFile(file)) return Result.error("file.delete " + path, "No such file.");
        Files.delete(file);
        return Result.ok("file.delete " + path, "Deleted.");
    }

    private Result exec(Workspace ws, String command) {
        String refusal = SandboxService.refusal(command);
        if (refusal != null) return Result.refused("exec " + command, refusal);
        if (ws.sandboxId() == null) return Result.error("exec " + command, "The sandbox is not running.");
        SandboxService.Command r = sandboxes.exec(ws.sandboxId(), command, null);
        String out = r.output();
        String shown;
        if (r.ok()) {
            List<String> lines = out.lines().toList();
            shown = lines.size() <= 25 ? out : "[" + (lines.size() - 25) + " lines omitted]\n" + String.join("\n", lines.subList(lines.size() - 25, lines.size()));
        } else {
            shown = r.timedOut() ? "The command timed out." : TestOutput.failingExcerpt(out, 40);
        }
        TestOutput.Counts counts = TestOutput.counts(out);
        String tests = counts.passed() != null ? " · " + counts.passed() + " passed" + (counts.failed() != null && counts.failed() > 0 ? ", " + counts.failed() + " failed" : "") : "";
        String summary = "exec " + command + " · " + (r.ok() ? "exit 0" : r.timedOut() ? "timed out" : "exit " + r.exit()) + tests
                + " · " + (r.durationMs() / 100) / 10.0 + " s";
        Map<String, Object> detail = Map.of("command", command, "exit", r.exit(), "output", clip(out, 20_000), "durationMs", r.durationMs());
        return (r.ok() ? Result.ok(summary, "exit 0\n" + clip(shown)) : Result.error(summary, "exit " + r.exit() + "\n" + clip(shown))).withDetail(detail);
    }

    private Result commit(Workspace ws, String message) {
        if (git.mergeInProgress(ws.worktree()) && !git.grep(ws.worktree(), "<<<<<<< ", null, 5).isBlank()) {
            return Result.error("git.commit", "Conflict markers are still in the files listed by the platform; resolve them first.");
        }
        var sha = git.commitAll(ws.worktree(), message, ws.author());
        return sha.map(s -> Result.ok("git.commit \"" + message + "\" · " + s.substring(0, 7), "Committed " + s.substring(0, 7) + ".")
                        .withDetail(Map.of("sha", s, "message", message)))
                .orElse(Result.ok("git.commit · nothing to commit", "Nothing to commit."));
    }

    /** The path inside the worktree, or null when it escapes it or points into .git. */
    static Path resolve(Workspace ws, String path) {
        if (path == null) return null;
        Path root = ws.worktree().toAbsolutePath().normalize();
        Path p = root.resolve(path.startsWith("/") ? path.substring(1) : path).normalize();
        if (!p.startsWith(root)) return null;
        Path rel = root.relativize(p);
        if (rel.getNameCount() > 0 && rel.getName(0).toString().equals(".git")) return null;
        return p;
    }

    static String sensitive(String path) {
        String p = path.replace('\\', '/').toLowerCase();
        if (p.startsWith(".github/") || p.equals(".gitlab-ci.yml") || p.endsWith("dockerfile") || p.matches("(.*/)?\\.env(\\..*)?")) {
            return "This file needs the CTO's approval to change (CI, Dockerfile, .env). Use task.block to ask.";
        }
        return null;
    }

    private static String text(JsonNode args, String field, String fallback) {
        JsonNode n = args.path(field);
        return n.isString() ? n.asString() : n.isMissingNode() || n.isNull() ? fallback : n.toString();
    }

    static String clip(String s) {
        return clip(s, MAX_OUTPUT_CHARS);
    }

    static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "\n[... " + (s.length() - max) + " more characters]";
    }
}
