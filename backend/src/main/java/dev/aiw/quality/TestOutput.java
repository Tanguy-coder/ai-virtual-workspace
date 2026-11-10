package dev.aiw.quality;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure helpers around test commands: which command runs a project's tests, how many passed and failed, and the
 * few lines an agent needs to fix a failure (docs/10, T7: the agent reads failures, not the whole log).
 */
public final class TestOutput {
    private TestOutput() {}

    public record Plan(List<String> setup, String command) {}

    /** Test command for the project at {@code root}, with the installs it needs first; null when none is set up. */
    public static Plan plan(Path root) {
        try {
            Path pkg = root.resolve("package.json");
            if (Files.exists(pkg)) {
                String json = Files.readString(pkg);
                Matcher m = Pattern.compile("\"test\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
                if (!m.find() || m.group(1).contains("no test specified")) return null;
                boolean deps = json.matches("(?s).*\"(dev)?[dD]ependencies\"\\s*:\\s*\\{\\s*\".*");
                List<String> setup = deps && !Files.isDirectory(root.resolve("node_modules"))
                        ? List.of(Files.exists(root.resolve("package-lock.json")) ? "npm ci --no-audit --no-fund" : "npm install --no-audit --no-fund")
                        : List.of();
                return new Plan(setup, "npm test");
            }
            if (Files.exists(root.resolve("pom.xml"))) return new Plan(List.of(), "mvn -q -B test");
            if (Files.exists(root.resolve("requirements.txt")) || Files.exists(root.resolve("pyproject.toml"))) {
                List<String> setup = Files.exists(root.resolve("requirements.txt"))
                        ? List.of("pip install --quiet --user -r requirements.txt") : List.of();
                return new Plan(setup, "python -m pytest -q");
            }
        } catch (java.io.IOException e) {
            return null;
        }
        return null;
    }

    public record Counts(Integer passed, Integer failed) {}

    private static final Pattern[][] COUNTERS = {
            // node:test (TAP and spec reporters)
            {Pattern.compile("(?m)^(?:#|ℹ)\\s*pass\\s+(\\d+)"), Pattern.compile("(?m)^(?:#|ℹ)\\s*fail\\s+(\\d+)")},
            // jest / vitest
            {Pattern.compile("Tests:?\\s+.*?(\\d+) passed"), Pattern.compile("Tests:?\\s+.*?(\\d+) failed")},
            // pytest
            {Pattern.compile("(\\d+) passed"), Pattern.compile("(\\d+) failed")},
            // mocha
            {Pattern.compile("(\\d+) passing"), Pattern.compile("(\\d+) failing")},
    };
    private static final Pattern SUREFIRE = Pattern.compile("Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+)");

    public static Counts counts(String output) {
        Matcher s = SUREFIRE.matcher(output);
        Integer run = null, bad = null;
        while (s.find()) {
            run = Integer.parseInt(s.group(1));
            bad = Integer.parseInt(s.group(2)) + Integer.parseInt(s.group(3));
        }
        if (run != null) return new Counts(run - bad, bad);
        for (Pattern[] c : COUNTERS) {
            Integer p = last(c[0], output), f = last(c[1], output);
            if (p != null || f != null) return new Counts(p == null ? 0 : p, f == null ? 0 : f);
        }
        return new Counts(null, null);
    }

    private static Integer last(Pattern p, String s) {
        Matcher m = p.matcher(s);
        Integer v = null;
        while (m.find()) v = Integer.parseInt(m.group(1));
        return v;
    }

    private static final Pattern FAILURE = Pattern.compile(
            "(?i)(✖|✗|×|\\bnot ok\\b|\\bfail(ed|ing|ure)?\\b|assert|expected|error|exception|traceback)");
    private static final Pattern NOISE = Pattern.compile("(?i)^\\s*(#|ℹ)?\\s*(fail\\s+0|failures: 0|0 failed)");

    /** The lines around failures, at most {@code maxLines}: what an agent reads to fix a failing test. */
    public static String failingExcerpt(String output, int maxLines) {
        String[] lines = output.split("\n");
        List<String> out = new ArrayList<>();
        int keepUntil = -1;
        for (int i = 0; i < lines.length && out.size() < maxLines; i++) {
            String l = lines[i];
            if (FAILURE.matcher(l).find() && !NOISE.matcher(l).find()) keepUntil = i + 4;
            if (i <= keepUntil) out.add(l.length() > 300 ? l.substring(0, 300) + "…" : l);
        }
        if (out.isEmpty()) {
            for (int i = Math.max(0, lines.length - maxLines); i < lines.length; i++) out.add(lines[i]);
        }
        return String.join("\n", out).strip();
    }
}
