package dev.aiw.projects;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Picks the passages of the specification that matter for a call, instead of sending the whole document
 * (docs/09-sobriete-tokens.md). Pure and deterministic, so the same query gives the same excerpts and the
 * prompt prefix stays cacheable.
 *
 * <p>Documents are cut into sections at Markdown headings, long sections into paragraph groups. Sections are scored
 * by the query's words (rarer words weigh more, headings count double); the opening section always comes first
 * because it says what the project is. Selected sections are returned in document order, within {@code maxChars}.
 */
public final class SpecExcerpts {
    static final int CHUNK_CHARS = 1_400;

    private static final Set<String> STOP = Set.of(
            "the", "and", "for", "with", "that", "this", "from", "are", "was", "will", "must", "should", "can", "all",
            "les", "des", "une", "est", "pour", "dans", "sur", "par", "qui", "que", "avec", "aux", "son", "ses", "pas",
            "plus", "doit", "être", "sont", "leur", "elle", "ils", "mais", "comme", "tout", "tous", "cette", "ces");

    private SpecExcerpts() {}

    public record Chunk(String document, String heading, String text, int order) {}

    /** Excerpts for {@code query}, or "" when there is no specification. */
    public static String select(List<ProjectDocument> docs, String query, int maxChars) {
        List<Chunk> chunks = chunks(docs);
        if (chunks.isEmpty()) return "";
        int total = docs.stream().mapToInt(d -> d.content() == null ? 0 : d.content().length()).sum();
        if (total <= maxChars) return render(chunks, total, total);

        Set<String> terms = new HashSet<>(words(query));
        Map<String, Integer> df = new HashMap<>();
        List<Set<String>> chunkWords = new ArrayList<>();
        for (Chunk c : chunks) {
            Set<String> w = new HashSet<>(words(c.heading() + " " + c.text()));
            chunkWords.add(w);
            for (String t : w) df.merge(t, 1, Integer::sum);
        }
        double[] score = new double[chunks.size()];
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            List<String> body = words(c.text());
            Set<String> head = new HashSet<>(words(c.heading()));
            for (String t : terms) {
                if (!chunkWords.get(i).contains(t)) continue;
                double idf = Math.log(1 + (double) chunks.size() / df.get(t));
                long tf = body.stream().filter(t::equals).count();
                score[i] += idf * (Math.log(1 + tf) + (head.contains(t) ? 2 : 0));
            }
        }
        List<Integer> order = new ArrayList<>();
        order.add(0);
        List<Integer> rest = new ArrayList<>();
        for (int i = 1; i < chunks.size(); i++) rest.add(i);
        rest.sort(Comparator.comparingDouble((Integer i) -> -score[i]).thenComparingInt(i -> i));
        order.addAll(rest);

        List<Chunk> picked = new ArrayList<>();
        int used = 0;
        for (int i : order) {
            Chunk c = chunks.get(i);
            int size = c.text().length() + c.heading().length() + 60;
            if (used + size > maxChars) continue;
            picked.add(c);
            used += size;
        }
        picked.sort(Comparator.comparingInt(Chunk::order));
        return render(picked, used, total);
    }

    static List<Chunk> chunks(List<ProjectDocument> docs) {
        List<Chunk> out = new ArrayList<>();
        for (ProjectDocument d : docs) {
            if (d.content() == null || d.content().isBlank()) continue;
            String heading = "";
            StringBuilder section = new StringBuilder();
            for (String line : d.content().split("\n", -1)) {
                if (line.startsWith("#")) {
                    flush(out, d.title(), heading, section);
                    heading = line.replaceFirst("^#+\\s*", "").strip();
                } else {
                    section.append(line).append('\n');
                }
            }
            flush(out, d.title(), heading, section);
        }
        return out;
    }

    private static void flush(List<Chunk> out, String doc, String heading, StringBuilder section) {
        String text = section.toString().strip();
        section.setLength(0);
        if (text.isEmpty() && heading.isEmpty()) return;
        if (text.length() <= CHUNK_CHARS) {
            out.add(new Chunk(doc, heading, text, out.size()));
            return;
        }
        StringBuilder part = new StringBuilder();
        for (String para : text.split("\n\\s*\n")) {
            if (!part.isEmpty() && part.length() + para.length() > CHUNK_CHARS) {
                out.add(new Chunk(doc, heading, part.toString().strip(), out.size()));
                part.setLength(0);
            }
            part.append(para).append("\n\n");
        }
        if (!part.toString().isBlank()) out.add(new Chunk(doc, heading, part.toString().strip(), out.size()));
    }

    private static String render(List<Chunk> chunks, int used, int total) {
        StringBuilder sb = new StringBuilder();
        if (used < total) {
            sb.append("Excerpts of the specification relevant to this step (")
                    .append(Math.round(100.0 * Math.min(used, total) / total)).append("% of ").append(total)
                    .append(" characters). Shared memory holds the full analysis.\n");
        }
        String doc = null;
        for (Chunk c : chunks) {
            if (!c.document().equals(doc)) {
                if (doc != null) sb.append("</document>\n");
                doc = c.document();
                sb.append("<document title=\"").append(doc.replace("\"", "'")).append("\">\n");
            }
            if (!c.heading().isEmpty()) sb.append("## ").append(c.heading()).append('\n');
            if (!c.text().isEmpty()) sb.append(c.text()).append("\n\n");
        }
        if (doc != null) sb.append("</document>\n");
        return sb.toString();
    }

    static List<String> words(String s) {
        if (s == null) return List.of();
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        List<String> out = new ArrayList<>();
        for (String w : n.split("[^a-z0-9]+")) {
            if (w.length() >= 3 && !STOP.contains(w)) out.add(w.length() > 6 ? w.substring(0, 6) : w);
        }
        return out;
    }
}
