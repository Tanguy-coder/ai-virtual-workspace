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
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
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
            - file.read {"path": "src/app.js", "from": 1, "to": 150}: numbered lines, at most 200 per call. Add \
            "version": "develop" to read the file as develop has it.
            - file.write {"path": "...", "content": "..."}: create a file, or replace a small file entirely.
            - file.edit {"path": "...", "from": 66, "to": 70, "new": "replacement lines"}: replaces lines 66 to 70 (numbers \
            from your latest file.read of that file; "to": 65 inserts before line 66). Or {"path", "old": "existing text", \
            "new"} for a short unique passage. After an edit, line numbers below it shift: read again before the next one.
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
            - Edit with file.edit by line numbers; use file.write for new files. Never rewrite a whole file to change a few lines.
            - The platform checks the syntax of every JavaScript or JSON file you write or edit. On "SYNTAX ERROR", fix \
            that line first: nothing else works until it is fixed. Test files must use only node:test (test, describe, \
            it, beforeEach...) and node:assert, plus packages declared in package.json: no expect, no Mocha or Jest globals.
            - Do not touch CI files, Dockerfiles or .env files: they need the CTO's approval (use task.block to ask).
            - Be brief in thoughts, commit messages and summaries.
            </rules>
            """.formatted(String.join(", ", SandboxService.ALLOWED.stream().sorted().toList()));

    static final Set<String> TOOLS = Set.of("repo.tree", "code.search", "file.read", "file.write", "file.edit",
            "file.delete", "exec", "git.commit", "task.submit", "task.block", "message.send", "memory.record",
            "task.handback");
    private static final Set<String> SKIP = Set.of(".git", "node_modules", "__pycache__", ".venv", "target", "dist");
    private static final int MAX_READ_LINES = 200;
    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();
    private static final int MAX_OUTPUT_CHARS = 6_000;

    private final SandboxService sandboxes;
    private final GitService git;

    AgentTools(SandboxService sandboxes, GitService git) {
        this.sandboxes = sandboxes;
        this.git = git;
    }

    enum End { NONE, SUBMIT, BLOCK, HANDBACK }

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
                case "file.read" -> "develop".equalsIgnoreCase(text(args, "version", ""))
                        ? readDevelop(ws, text(args, "path", ""), args.path("from").asInt(1), args.path("to").asInt(0))
                        : read(ws, text(args, "path", ""), args.path("from").asInt(1), args.path("to").asInt(0));
                case "file.write" -> checked(ws, text(args, "path", ""), previous(ws, text(args, "path", "")), write(ws, text(args, "path", ""), text(args, "content", null)));
                // "from": 0 is how some models say "from the start" (seen on T-28): line 1.
                case "file.edit" -> checked(ws, text(args, "path", ""), previous(ws, text(args, "path", "")), args.has("from") && !args.has("old")
                        ? editLines(ws, text(args, "path", ""), Math.max(1, args.path("from").asInt(1)),
                                args.path("to").asInt(Math.max(1, args.path("from").asInt(1))), text(args, "new", null))
                        : edit(ws, text(args, "path", ""), text(args, "old", null), text(args, "new", null)));
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

    /** The file as develop had it when the branch started: what the tests from develop pass with. */
    private Result readDevelop(Workspace ws, String path, int from, int to) {
        if (resolve(ws, path) == null) return Result.refused("file.read " + path, "Path outside the repository.");
        Optional<String> content;
        try {
            String base = git.mergeBase(ws.worktree(), git.integrationBranch(ws.worktree()), "HEAD");
            content = git.show(ws.worktree(), base, path);
        } catch (RuntimeException e) {
            content = Optional.empty();
        }
        if (content.isEmpty()) return Result.error("file.read " + path + " (develop)", "develop has no such file.");
        List<String> all = content.get().lines().toList();
        int start = Math.max(1, from), end = to <= 0 ? Math.min(all.size(), start + MAX_READ_LINES - 1) : Math.min(to, all.size());
        end = Math.min(end, start + MAX_READ_LINES - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i <= end; i++) sb.append(i).append(": ").append(all.get(i - 1)).append('\n');
        String note = end < all.size() ? "\n[" + (all.size() - end) + " more lines; read from " + (end + 1) + "]" : "";
        return Result.ok("file.read " + path + " (develop) lines " + start + "–" + end + " of " + all.size(),
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
        if (at < 0) {
            List<String> lines = content.lines().toList();
            int loose = LineEdits.looseMatch(lines, old);
            if (loose >= 0) {
                // Same lines but for their indentation: unambiguous, so applied as a line edit.
                return editLines(ws, path, loose + 1, loose + (int) old.strip().lines().count(), replacement);
            }
            int near = LineEdits.closestLine(lines, old);
            return Result.error("file.edit " + path, "old text not found" + (loose == -2 ? " exactly (it matches several places)" : "")
                    + ". Edit by line numbers instead: {\"path\": \"" + path + "\", \"from\": N, \"to\": M, \"new\": \"...\"}."
                    + (near >= 0 ? " Closest lines:\n" + LineEdits.numbered(lines, near, 4) : ""));
        }
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

    private Result editLines(Workspace ws, String path, int from, int to, String replacement) throws IOException {
        Path file = resolve(ws, path);
        if (file == null) return Result.refused("file.edit " + path, "Path outside the repository.");
        String sensitive = sensitive(path);
        if (sensitive != null) return Result.refused("file.edit " + path, sensitive);
        if (!Files.isRegularFile(file)) return Result.error("file.edit " + path, "No such file; use file.write to create it.");
        if (replacement == null) return Result.refused("file.edit " + path, "Give new (an empty string deletes the lines).");
        String content = Files.readString(file, StandardCharsets.UTF_8);
        String edited;
        try {
            edited = LineEdits.replaceLines(content, from, to, replacement);
        } catch (IllegalArgumentException e) {
            return Result.error("file.edit " + path, e.getMessage() + " Read the file again.");
        }
        Files.writeString(file, edited, StandardCharsets.UTF_8);
        List<String> before = content.lines().toList();
        String old = from <= to ? String.join("\n", before.subList(from - 1, Math.min(to, before.size()))) : "";
        long added = replacement.isEmpty() ? 0 : replacement.lines().count(), removed = Math.max(0, to - from + 1);
        List<String> after = edited.lines().toList();
        Map<String, Object> detail = new HashMap<>();
        detail.put("path", path);
        detail.put("line", from);
        detail.put("old", clip(old, 1_500));
        detail.put("new", clip(replacement, 1_500));
        // The new lines, numbered: the agent sees the result and the shifted numbers without another read.
        int shownTo = Math.min(after.size(), from + (int) added + 1);
        StringBuilder now = new StringBuilder();
        for (int i = Math.max(1, from - 1); i <= shownTo; i++) now.append(i).append(": ").append(after.get(i - 1)).append('\n');
        return Result.ok("file.edit " + path + " · +" + added + " −" + removed + " at line " + from,
                "Edited lines " + from + "–" + to + ". Now:\n" + clip(now.toString())).withDetail(detail);
    }

    /**
     * Syntax check of a JavaScript or JSON file just written or edited: an error is reported at once, with its line,
     * instead of surfacing three steps later as a failing test run (the loop seen on 2026-11-18).
     */
    /** The file's content before a write or an edit, or null when it does not exist yet. */
    private String previous(Workspace ws, String path) {
        try {
            Path file = resolve(ws, path);
            return file != null && Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Edits in a row that left a file with a syntax error, and the file's last version that parsed (per worktree). */
    private final Map<String, Integer> brokenStreak = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> lastValid = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 300;
        }
    });
    static final int BROKEN_EDITS = 3;
    /** Changes in a row refused because they would break a file that parses (per worktree and file). */
    private final Map<String, Integer> refusedStreak = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * After a write or an edit: a syntax error is never left behind in a file that parsed before (seen on 2026-11-19:
     * twelve edits in a row of tests/app.test.js, each one on top of the previous broken version). The change is
     * refused and the file stays as it was. In a file that was already broken, {@link #BROKEN_EDITS} edits in a row
     * that leave it broken bring back its last version that parsed.
     */
    private Result checked(Workspace ws, String path, String before, Result r) {
        if (!"OK".equals(r.outcome())) return r;
        try {
            Path file = resolve(ws, path);
            if (file == null || !Files.isRegularFile(file)) return r;
            String key = ws.worktree() + "\n" + path;
            Object[] err = syntaxProblem(ws, path);
            String proposed = Files.readString(file, StandardCharsets.UTF_8);
            if (err == null) {
                brokenStreak.remove(key);
                refusedStreak.remove(key);
                if (proposed.length() < 300_000) lastValid.put(key, proposed);
                return lintTest(ws, path, file, r);
            }
            int line = (Integer) err[0];
            String note = (String) err[1];
            List<String> lines = proposed.lines().toList();
            String around = line > 0 ? "\n" + LineEdits.numbered(lines, line - 1, 4) : "";
            String where = line > 0 ? " at line " + line : "";
            if (before != null) {
                Files.writeString(file, before, StandardCharsets.UTF_8);
                if (syntaxProblem(ws, path) == null) {
                    brokenStreak.remove(key);
                    int refused = refusedStreak.merge(key, 1, Integer::sum);
                    long size = before.lines().count();
                    // Line edits that keep breaking the structure (T-28: braces of nested handlers): the whole file at once
                    // lets the model see and close every block it opens.
                    String next = refused >= 2 && size <= 250
                            ? "\n" + refused + " changes in a row to " + path + " were refused. Stop editing it by lines: read it "
                              + "whole (file.read from 1), then send the complete corrected file in one file.write (all of it, "
                              + "every block closed)."
                            : "\nRead the lines again (file.read) and send one corrected change.";
                    return new Result("ERROR", "not applied · syntax error" + where,
                            "NOT APPLIED: this change would leave a SYNTAX ERROR" + where + ": " + note + ". " + path
                                    + " is unchanged and still valid. Your version around that line:" + around + next,
                            r.detail(), r.end());
                }
                Files.writeString(file, proposed, StandardCharsets.UTF_8); // it was broken already: keep the attempt
            }
            int streak = brokenStreak.merge(key, 1, Integer::sum);
            if (streak >= BROKEN_EDITS) {
                String from = restoreLastValid(ws, path, key);
                if (from != null) {
                    brokenStreak.remove(key);
                    return new Result("ERROR", r.summary() + " · restored " + from,
                            BROKEN_EDITS + " changes in a row left " + path + " with a SYNTAX ERROR" + where + ": " + note
                                    + ". The platform restored its last version that parsed (" + from + "). Read it "
                                    + "(file.read), then make one small change at a time; for a test file, rewrite it whole "
                                    + "with one file.write of complete, valid content.",
                            r.detail(), r.end());
                }
            }
            return new Result("ERROR", r.summary() + " · syntax error" + where,
                    r.output() + "\nSYNTAX ERROR" + where + ": " + note + around, r.detail(), r.end());
        } catch (Exception e) {
            return r; // the check is a help, never a reason to lose the edit
        }
    }

    /** {line (0 when unknown), message} of the file's syntax error, or null when it parses (or cannot be checked). */
    private Object[] syntaxProblem(Workspace ws, String path) throws IOException {
        Path file = resolve(ws, path);
        if (path.endsWith(".json")) {
            try {
                JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
                return null;
            } catch (tools.jackson.core.JacksonException e) {
                return new Object[] {e.getLocation() == null ? 0 : e.getLocation().getLineNr(), "Invalid JSON: " + e.getOriginalMessage()};
            }
        }
        if (path.matches(".*\\.(js|mjs|cjs)$") && ws.sandboxId() != null) {
            SandboxService.Command c = sandboxes.exec(ws.sandboxId(), "node --check " + path, java.time.Duration.ofSeconds(20));
            return c.ok() ? null : LineEdits.nodeSyntaxError(c.output());
        }
        return null;
    }

    /** Writes back the file's last version that parses: remembered, committed (HEAD), or from develop. Returns which. */
    private String restoreLastValid(Workspace ws, String path, String key) throws IOException {
        Path file = resolve(ws, path);
        String current = Files.readString(file, StandardCharsets.UTF_8);
        java.util.LinkedHashMap<String, String> candidates = new java.util.LinkedHashMap<>();
        String remembered = lastValid.get(key);
        if (remembered != null) candidates.put("the last valid version of this run", remembered);
        try {
            git.show(ws.worktree(), "HEAD", path).ifPresent(c -> candidates.putIfAbsent("the last commit", c));
            String base = git.mergeBase(ws.worktree(), git.integrationBranch(ws.worktree()), "HEAD");
            git.show(ws.worktree(), base, path).ifPresent(c -> candidates.putIfAbsent("develop", c));
        } catch (RuntimeException e) {
            // no commit yet: only the remembered version
        }
        for (var c : candidates.entrySet()) {
            if (c.getValue().equals(current)) continue;
            Files.writeString(file, c.getValue(), StandardCharsets.UTF_8);
            if (syntaxProblem(ws, path) == null) {
                lastValid.put(key, c.getValue());
                return c.getKey();
            }
        }
        Files.writeString(file, current, StandardCharsets.UTF_8);
        return null;
    }

    /**
     * Every test file of the repository that cannot run (TestFileLint), as lines for a failing test run: the culprit is
     * often a test file another agent wrote, not the code the current task changes. Empty when all are fine.
     */
    static String testFileProblems(Path worktree) {
        StringBuilder out = new StringBuilder();
        try (var walk = Files.walk(worktree, 6)) {
            for (Path f : walk.filter(Files::isRegularFile).limit(5_000).toList()) {
                String rel = worktree.relativize(f).toString().replace('\\', '/');
                if (rel.startsWith("node_modules/") || rel.contains("/node_modules/") || rel.startsWith(".git/") || !TestFileLint.isTestFile(rel)) continue;
                List<String> problems = lint(worktree, rel, Files.readString(f, StandardCharsets.UTF_8));
                if (!problems.isEmpty()) out.append("- ").append(rel).append(": ").append(String.join(" ", problems)).append("\n");
                if (out.length() > 2_500) break;
            }
        } catch (Exception e) {
            return "";
        }
        return out.isEmpty() ? "" : "Test files that cannot run under node --test (fix or rewrite them first):\n" + out;
    }

    private static final Pattern SAFE_PATH = Pattern.compile("[\\w./@+-]+");
    private static final Set<String> SKIPPED_DIRS = Set.of("node_modules", ".git", "dist", "build", "coverage", ".venv", "target");

    /**
     * Every JavaScript file of the repository that node cannot parse, checked in the sandbox (seen on T-28, 2026-11-19:
     * the tests failed with "Unexpected token '}'" at their own location; the broken file was app.js, line 149, which
     * they import, and neither the agent nor the Lead was ever shown it). Empty when all parse, or when it cannot check.
     */
    static String syntaxScan(SandboxService sandboxes, String sandboxId, Path worktree) {
        if (sandboxes == null || sandboxId == null || worktree == null) return "";
        List<String> files = new ArrayList<>();
        try (var walk = Files.walk(worktree, 6)) {
            walk.filter(Files::isRegularFile).map(f -> worktree.relativize(f).toString().replace('\\', '/'))
                    .filter(rel -> rel.matches(".*\\.(js|mjs|cjs)$") && SAFE_PATH.matcher(rel).matches())
                    .filter(rel -> java.util.Arrays.stream(rel.split("/")).noneMatch(SKIPPED_DIRS::contains))
                    .limit(150).forEach(files::add);
        } catch (IOException e) {
            return "";
        }
        if (files.isEmpty()) return "";
        String cmd = "for f in " + String.join(" ", files.stream().map(f -> "'" + f + "'").toList())
                + "; do node --check \"$f\" >/dev/null 2>/tmp/aiw-check || { echo \"@@ $f\"; head -8 /tmp/aiw-check; }; done; true";
        SandboxService.Command c;
        try {
            c = sandboxes.exec(sandboxId, cmd, java.time.Duration.ofSeconds(60));
        } catch (RuntimeException e) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String block : c.output().split("(?m)^@@ ")) {
            if (block.isBlank()) continue;
            String file = block.lines().findFirst().orElse("").strip();
            if (!files.contains(file)) continue;
            Object[] err = LineEdits.nodeSyntaxError(block);
            out.append("- ").append(file).append(err == null ? ": does not parse" : (((Integer) err[0]) > 0 ? " line " + err[0] : "")
                    + ": " + err[1]).append("\n");
        }
        return out.isEmpty() ? "" : "Files with a SYNTAX ERROR (fix them first: every test that imports one fails, "
                + "whatever it checks):\n" + out;
    }

    /** TestFileLint on one test file: runner, packages from package.json, and what it imports from the project's files. */
    static List<String> lint(Path worktree, String rel, String source) {
        java.util.Set<String> deps = new java.util.HashSet<>();
        boolean esm = false;
        try {
            Path pkg = worktree.resolve("package.json");
            if (Files.isRegularFile(pkg)) {
                JsonNode json = JSON.readTree(Files.readString(pkg, StandardCharsets.UTF_8));
                for (String section : List.of("dependencies", "devDependencies", "peerDependencies", "optionalDependencies")) {
                    json.path(section).propertyNames().forEach(deps::add);
                }
                esm = "module".equals(json.path("type").asString(""));
            }
        } catch (Exception e) {
            // unreadable package.json: dependencies unknown, packages are reported
        }
        List<String> out = new ArrayList<>(TestFileLint.problems(source, deps));
        out.addAll(TestFileLint.importProblems(rel, source, target -> {
            try {
                Path f = worktree.resolve(target).normalize();
                return f.startsWith(worktree) && Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
            } catch (IOException e) {
                return null;
            }
        }, esm));
        return out;
    }

    /** A test file must run under node --test with what the project installs (TestFileLint). */
    private Result lintTest(Workspace ws, String path, Path file, Result r) throws IOException {
        if (!TestFileLint.isTestFile(path)) return r;
        r = fromDevelop(ws, path, r);
        List<String> problems = lint(ws.worktree(), path, Files.readString(file, StandardCharsets.UTF_8));
        if (problems.isEmpty()) return r;
        return new Result("ERROR", r.summary() + " · test file problem",
                r.output() + "\nTEST FILE PROBLEM (tests run with node --test, node:assert and the installed packages only):\n- "
                        + String.join("\n- ", problems), r.detail(), r.end());
    }

    /** A test from develop is a requirement: changing it is said at once, and it is restored if it then fails (T-16). */
    private Result fromDevelop(Workspace ws, String path, Result r) {
        try {
            String base = git.mergeBase(ws.worktree(), git.integrationBranch(ws.worktree()), "HEAD");
            if (git.show(ws.worktree(), base, path).isEmpty()) return r;
        } catch (RuntimeException e) {
            return r;
        }
        return new Result(r.outcome(), r.summary(), r.output() + "\nNOTE: " + path + " comes from develop: it is a "
                + "requirement other work relies on. Change it only if your task is about this test; never to make your "
                + "code pass. If it fails, the platform restores the develop version.", r.detail(), r.end());
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
            String broken = Regressions.explain(Regressions.find(git, ws.worktree(), out), false)
                    + syntaxScan(sandboxes, ws.sandboxId(), ws.worktree()) + testFileProblems(ws.worktree());
            if (!broken.isEmpty()) shown = broken + "\n" + shown;
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
