package dev.aiw.orchestration;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Edits small models get right (CTO requirement, 2026-11-18, docs/17). Copying a passage character for character is
 * where they fail ("old text not found", seen 20 times on one run): they edit by line numbers from file.read, a passage
 * that differs only by indentation still matches when it is unique, and a miss shows the closest lines, numbered.
 */
final class LineEdits {
    private LineEdits() {}

    /** Lines {@code from}..{@code to} (1-based, inclusive) replaced by {@code replacement}; to = from - 1 inserts. */
    static String replaceLines(String content, int from, int to, String replacement) {
        List<String> lines = new ArrayList<>(List.of(content.split("\n", -1)));
        boolean trailingNewline = content.endsWith("\n");
        if (trailingNewline) lines.removeLast();
        if (from < 1 || from > lines.size() + 1 || to < from - 1 || to > lines.size()) {
            throw new IllegalArgumentException("Lines " + from + "–" + to + " are outside the file (" + lines.size() + " lines).");
        }
        List<String> out = new ArrayList<>(lines.subList(0, from - 1));
        String body = replacement.endsWith("\n") ? replacement.substring(0, replacement.length() - 1) : replacement;
        if (!replacement.isEmpty()) out.addAll(List.of(body.split("\n", -1)));
        out.addAll(lines.subList(to, lines.size()));
        return String.join("\n", out) + (trailingNewline ? "\n" : "");
    }

    /**
     * Where {@code old} matches when each line is compared without its surrounding spaces: the 0-based first line of
     * the only match, -1 when none, -2 when several.
     */
    static int looseMatch(List<String> file, String old) {
        List<String> want = old.strip().lines().map(String::strip).toList();
        if (want.isEmpty()) return -1;
        int found = -1;
        for (int i = 0; i + want.size() <= file.size(); i++) {
            boolean same = true;
            for (int j = 0; j < want.size() && same; j++) same = file.get(i + j).strip().equals(want.get(j));
            if (same) {
                if (found >= 0) return -2;
                found = i;
            }
        }
        return found;
    }

    /** 0-based line of the file most like the first meaningful line of {@code old}, or -1. */
    static int closestLine(List<String> file, String old) {
        String probe = old.lines().map(String::strip).filter(l -> l.length() > 2).findFirst().orElse("");
        if (probe.isEmpty()) return -1;
        int best = -1;
        double bestScore = 0.3;
        for (int i = 0; i < file.size(); i++) {
            double score = similarity(probe, file.get(i).strip());
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /** Dice coefficient on character bigrams: 1 for equal strings, 0 for nothing in common. */
    static double similarity(String a, String b) {
        if (a.length() < 2 || b.length() < 2) return a.equals(b) ? 1 : 0;
        java.util.Map<String, Integer> grams = new java.util.HashMap<>();
        for (int i = 0; i < a.length() - 1; i++) grams.merge(a.substring(i, i + 2), 1, Integer::sum);
        int common = 0;
        for (int i = 0; i < b.length() - 1; i++) {
            String g = b.substring(i, i + 2);
            Integer n = grams.get(g);
            if (n != null && n > 0) {
                common++;
                grams.put(g, n - 1);
            }
        }
        return 2.0 * common / (a.length() - 1 + b.length() - 1);
    }

    /** Lines around {@code center} (0-based), numbered from 1 as file.read shows them. */
    static String numbered(List<String> file, int center, int radius) {
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, center - radius); i <= Math.min(file.size() - 1, center + radius); i++) {
            sb.append(i + 1).append(": ").append(file.get(i)).append('\n');
        }
        return sb.toString();
    }

    private static final Pattern NODE_LINE = Pattern.compile("^\\S+?:(\\d+)\\s*$", Pattern.MULTILINE);
    private static final Pattern NODE_ERROR = Pattern.compile("^(SyntaxError: .+)$", Pattern.MULTILINE);

    /** "node --check" output → {line, message}, or null when it found nothing. */
    static Object[] nodeSyntaxError(String output) {
        Matcher error = NODE_ERROR.matcher(output == null ? "" : output);
        if (!error.find()) return null;
        Matcher line = NODE_LINE.matcher(output);
        return new Object[] {line.find() ? Integer.parseInt(line.group(1)) : 0, error.group(1).strip()};
    }
}
