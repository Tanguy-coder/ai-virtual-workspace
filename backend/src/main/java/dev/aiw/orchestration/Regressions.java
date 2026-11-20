package dev.aiw.orchestration;

import dev.aiw.scm.GitService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tests that fail on a branch although they come unchanged from develop, where every test passes (a task merges only
 * with its tests green): the branch's own changes broke them. Seen on T-29 (2026-11-20): the agent rewrote src/app.js
 * and lost exports that develop's tests import; the agent, then the Lead in its takeover, spent 30 steps hunting for
 * exports that develop's version of the file still had. Found by code from git alone, no model call.
 */
final class Regressions {
    private static final Pattern TEST_PATH = Pattern.compile("((?:[\\w.@-]+/)*[\\w.@-]+\\.(?:test|spec)\\.[cm]?js|(?:tests?|__tests__)/(?:[\\w.@-]+/)*[\\w.@-]+\\.[cm]?js)");
    private static final int SHOWN_LINES = 150;

    private Regressions() {}

    /** A code file the branch changed that a regressed test depends on. */
    record Culprit(String path, Set<String> developExports, List<String> lostExports, String developContent) {}

    /** A test from develop, unchanged, that fails on the branch; {@code culprits}: what it imports that the branch changed. */
    record Regression(String test, List<Culprit> culprits) {}

    static List<Regression> find(GitService git, Path worktree, String failure) {
        if (failure == null || failure.isBlank() || worktree == null || !Files.isDirectory(worktree)) return List.of();
        try {
            String base = git.mergeBase(worktree, git.integrationBranch(worktree), "HEAD");
            Set<String> tests = new LinkedHashSet<>();
            Matcher m = TEST_PATH.matcher(failure.replace("/work/", " ").replace("file://", " "));
            while (m.find() && tests.size() < 20) tests.add(m.group(1).replaceFirst("^\\./", ""));
            // Code files the branch changed (committed or not): the only possible culprits.
            Set<String> changed = new LinkedHashSet<>();
            for (GitService.FileChange c : git.changes(worktree, base, "HEAD")) changed.add(c.path());
            List<Regression> out = new ArrayList<>();
            Map<String, Culprit> culprits = new LinkedHashMap<>();
            for (String test : tests) {
                Path file = worktree.resolve(test).normalize();
                if (!file.startsWith(worktree) || !Files.isRegularFile(file) || !TestFileLint.isTestFile(test)) continue;
                String now = Files.readString(file);
                Optional<String> original = git.show(worktree, base, test);
                if (original.isEmpty() || !original.get().equals(now)) continue; // new or changed on the branch: not develop's
                List<Culprit> own = new ArrayList<>();
                for (String dep : TestFileLint.relativeImports(test, now)) {
                    Culprit c = culprits.computeIfAbsent(dep, d -> culprit(git, worktree, base, d));
                    if (c != null) own.add(c);
                }
                if (own.isEmpty()) {
                    // It reads files another way (fs, jsdom of index.html): every code file the branch changed is a suspect.
                    for (String path : changed) {
                        if (TestFileLint.isTestFile(path) || path.contains("lock")) continue;
                        Culprit c = culprits.computeIfAbsent(path, d -> culprit(git, worktree, base, d));
                        if (c != null) own.add(c);
                    }
                }
                if (!own.isEmpty()) out.add(new Regression(test, own));
            }
            return out;
        } catch (RuntimeException | java.io.IOException e) {
            return List.of();
        }
    }

    /** The file as develop has it, when the branch changed it (null when unchanged or new on the branch). */
    private static Culprit culprit(GitService git, Path worktree, String base, String path) {
        try {
            Path file = worktree.resolve(path).normalize();
            if (!file.startsWith(worktree)) return null;
            Optional<String> develop = git.show(worktree, base, path);
            if (develop.isEmpty()) return null;
            String now = Files.isRegularFile(file) ? Files.readString(file) : "";
            if (develop.get().equals(now)) return null;
            Set<String> before = TestFileLint.exportsOf(develop.get());
            Set<String> after = TestFileLint.exportsOf(now);
            List<String> lost = before.stream().filter(n -> !after.contains(n)).toList();
            return new Culprit(path, before, lost, develop.get());
        } catch (RuntimeException | java.io.IOException e) {
            return null;
        }
    }

    /** What the agent (or the Lead) reads first in a failing test run; {@code withDevelop}: develop's files, numbered. */
    static String explain(List<Regression> regressions, boolean withDevelop) {
        if (regressions.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("REGRESSION (fix this first): these tests come unchanged from develop, where every "
                + "test passes, so changes on this branch broke them:\n");
        Map<String, Culprit> files = new LinkedHashMap<>();
        for (Regression r : regressions) {
            sb.append("- ").append(r.test()).append(", through ");
            List<String> parts = new ArrayList<>();
            for (Culprit c : r.culprits()) {
                files.putIfAbsent(c.path(), c);
                parts.add(c.path() + (c.lostExports().isEmpty() ? "" : " (it no longer exports " + String.join(", ", c.lostExports()) + ")"));
            }
            sb.append(String.join(", ", parts)).append("\n");
        }
        for (Culprit c : files.values()) {
            if (!c.developExports().isEmpty()) {
                sb.append("develop's ").append(c.path()).append(" exports: ").append(String.join(", ", c.developExports())).append(".\n");
            }
        }
        sb.append("Keep everything develop's version does (every export, its name and its behaviour) and add the task's change "
                + "on top of it, instead of rewriting the file. ");
        if (!withDevelop) {
            sb.append("Read develop's version: file.read {\"path\": \"").append(files.keySet().iterator().next())
                    .append("\", \"version\": \"develop\"}.\n");
            return sb.toString();
        }
        sb.append("\n");
        for (Culprit c : files.values()) {
            List<String> lines = c.developContent().lines().toList();
            sb.append("<file path=\"").append(c.path()).append("\" version=\"develop\" lines=\"").append(lines.size()).append("\">\n");
            for (int i = 0; i < Math.min(lines.size(), SHOWN_LINES); i++) sb.append(i + 1).append(": ").append(lines.get(i)).append('\n');
            if (lines.size() > SHOWN_LINES) sb.append("[").append(lines.size() - SHOWN_LINES).append(" more lines]\n");
            sb.append("</file>\n");
        }
        return sb.toString();
    }

    /** Puts develop's version of every culprit back, committed by the platform. Returns the paths restored. */
    static List<String> restore(GitService git, Path worktree, List<Regression> regressions) {
        Map<String, Culprit> files = new LinkedHashMap<>();
        regressions.forEach(r -> r.culprits().forEach(c -> files.putIfAbsent(c.path(), c)));
        List<String> restored = new ArrayList<>();
        try {
            for (Culprit c : files.values()) {
                Path file = worktree.resolve(c.path()).normalize();
                if (!file.startsWith(worktree)) continue;
                Files.createDirectories(file.getParent());
                Files.writeString(file, c.developContent());
                restored.add(c.path());
            }
        } catch (java.io.IOException e) {
            return restored;
        }
        if (!restored.isEmpty()) {
            git.commitAll(worktree, "Restore " + String.join(", ", restored) + " from develop: the branch broke tests that pass there",
                    GitService.Author.PLATFORM);
        }
        return restored;
    }
}
