package dev.aiw.quality;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Reads the reports of the scanning tools into findings (docs/18): each with its proof, its severity and what to do.
 * Pure functions of the report (and of the snapshot, for the proof's line): tested on real reports.
 */
final class ScanParsers {
    private ScanParsers() {}

    /** A finding as a scan sees it; {@code fingerprint} recognises it from one scan to the next (not its line number). */
    record Draft(String kind, String tool, String rule, String severity, String title, String file, Integer line,
                 String location, String evidence, String reference, String recommendation, String fingerprint) {}

    static final List<String> SEVERITIES = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");

    // ------------------------------------------------------------------ Gitleaks

    static List<Draft> gitleaks(JsonNode report, Path snapshot, String mountedAt) {
        List<Draft> out = new ArrayList<>();
        if (report == null || !report.isArray()) return out;
        for (JsonNode f : report) {
            String file = relative(f.path("File").asString(""), mountedAt);
            int line = f.path("StartLine").asInt(0);
            String text = lineOf(snapshot, file, line);
            String masked = mask(text, f.path("StartColumn").asInt(0), f.path("EndColumn").asInt(0));
            String rule = f.path("RuleID").asString("secret");
            out.add(new Draft("SECRETS", "gitleaks", rule, "CRITICAL",
                    "Credential in the code: " + rule, file, line, null,
                    masked.isBlank() ? f.path("Description").asString("") : masked,
                    "CWE-798: Use of Hard-coded Credentials",
                    "Remove the credential from the code, revoke it and issue a new one at its provider, then read it from "
                            + "configuration (an environment variable), never from a committed file.",
                    hash("SECRETS", rule, file, text.strip())));
        }
        return out;
    }

    /** The line with the secret's columns replaced by asterisks: proof of where it is, without the value. */
    static String mask(String line, int startColumn, int endColumn) {
        if (line.isEmpty()) return "";
        // Gitleaks' columns can be one off: mask one character more on the left, and any long token left on the line.
        int from = Math.max(0, startColumn - 2);
        int to = Math.min(line.length(), Math.max(from, endColumn));
        String shown = to <= from ? line : line.substring(0, from) + "****" + line.substring(to);
        return clip(shown.strip().replaceAll("[A-Za-z0-9_\\-+/=]{16,}", "****"), 300);
    }

    // ------------------------------------------------------------------ Semgrep

    static List<Draft> semgrep(JsonNode report, Path snapshot, String mountedAt) {
        List<Draft> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode r : report.path("results")) {
            String file = relative(r.path("path").asString(""), mountedAt);
            int line = r.path("start").path("line").asInt(0);
            String rule = r.path("check_id").asString("rule");
            JsonNode extra = r.path("extra");
            JsonNode meta = extra.path("metadata");
            String text = lineOf(snapshot, file, line);
            String fp = hash("SAST", rule, file, text.strip());
            if (!seen.add(fp)) continue; // the same rule on the same code: one finding
            String cwe = first(meta.path("cwe"));
            String ref = first(meta.path("references"));
            String severity = switch (extra.path("severity").asString("INFO")) {
                case "ERROR" -> "HIGH";
                case "WARNING" -> "MEDIUM";
                case "INFO" -> "LOW";
                default -> "INFO";
            };
            String message = extra.path("message").asString("").strip();
            String shortRule = rule.substring(rule.lastIndexOf('.') + 1).replace('-', ' ');
            out.add(new Draft("SAST", "semgrep", rule, severity, cap(shortRule) + (cwe.isEmpty() ? "" : " (" + cwe.split(":")[0] + ")"),
                    file, line, null, clip(text.strip(), 300),
                    join(cwe, ref), clip(message, 1_500), fp));
        }
        return out;
    }

    // ------------------------------------------------------------------ OSV-Scanner

    /** One finding per vulnerable package version, with every advisory it is affected by and the version that fixes them all. */
    static List<Draft> osv(JsonNode report) {
        List<Draft> out = new ArrayList<>();
        for (JsonNode res : report.path("results")) {
            String source = res.path("source").path("path").asString("");
            for (JsonNode p : res.path("packages")) {
                JsonNode pkg = p.path("package");
                String name = pkg.path("name").asString("");
                String version = pkg.path("version").asString("");
                String ecosystem = pkg.path("ecosystem").asString("");
                double max = 0;
                for (JsonNode g : p.path("groups")) max = Math.max(max, number(g.path("max_severity").asString("")));
                List<String> ids = new ArrayList<>();
                List<String> lines = new ArrayList<>();
                String fixed = null;
                String fallbackSeverity = null;
                for (JsonNode v : p.path("vulnerabilities")) {
                    String id = v.path("id").asString("");
                    List<String> aliases = new ArrayList<>();
                    v.path("aliases").forEach(a -> aliases.add(a.asString("")));
                    String cve = aliases.stream().filter(a -> a.startsWith("CVE-")).findFirst().orElse(null);
                    ids.add(cve == null ? id : cve + " (" + id + ")");
                    lines.add((cve == null ? id : cve) + ": " + v.path("summary").asString("").strip());
                    String db = v.path("database_specific").path("severity").asString("");
                    if (!db.isEmpty() && (fallbackSeverity == null || rank(severityOfLabel(db)) < rank(fallbackSeverity))) {
                        fallbackSeverity = severityOfLabel(db);
                    }
                    for (JsonNode a : v.path("affected")) {
                        if (!name.equals(a.path("package").path("name").asString(name))) continue;
                        for (JsonNode range : a.path("ranges")) {
                            for (JsonNode e : range.path("events")) {
                                String f = e.path("fixed").asString("");
                                if (!f.isEmpty() && (fixed == null || compareVersions(f, fixed) > 0)) fixed = f;
                            }
                        }
                    }
                }
                if (ids.isEmpty()) continue;
                String severity = max > 0 ? severityOfScore(max) : fallbackSeverity == null ? "MEDIUM" : fallbackSeverity;
                String upgrade = fixed == null
                        ? "No fixed version is published: replace " + name + " or remove the code that uses it."
                        : "Upgrade " + name + " from " + version + " to " + fixed + " or later"
                                + ("npm".equalsIgnoreCase(ecosystem) ? " (npm install " + name + "@^" + fixed + "), commit package.json and the lockfile" : "")
                                + ", then run the tests.";
                out.add(new Draft("DEPENDENCIES", "osv-scanner", String.join(", ", ids.stream().limit(5).toList()), severity,
                        "Vulnerable dependency " + name + "@" + version + " (" + ids.size() + " advisor" + (ids.size() == 1 ? "y" : "ies") + ")",
                        relative(source, "/src/"), null, ecosystem + ":" + name + "@" + version,
                        clip(String.join("\n", lines.stream().limit(8).toList()), 1_500),
                        "https://osv.dev/vulnerability/" + ids.getFirst().replaceAll(" .*", ""), upgrade,
                        hash("DEPENDENCIES", ecosystem, name, version)));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ OWASP ZAP (baseline, passive)

    /** About ZAP itself, not the application ("ZAP is Out of Date"). */
    private static final Set<String> IGNORED_ZAP_RULES = Set.of("10116");

    static List<Draft> zap(JsonNode report) {
        List<Draft> out = new ArrayList<>();
        for (JsonNode site : report.path("site")) {
            for (JsonNode a : site.path("alerts")) {
                String plugin = a.path("pluginid").asString("");
                if (IGNORED_ZAP_RULES.contains(plugin)) continue;
                String severity = switch (a.path("riskcode").asString("0")) {
                    case "3" -> "HIGH";
                    case "2" -> "MEDIUM";
                    case "1" -> "LOW";
                    default -> "INFO";
                };
                List<String> proof = new ArrayList<>();
                String firstUri = null;
                for (JsonNode i : a.path("instances")) {
                    String uri = i.path("uri").asString("");
                    if (firstUri == null) firstUri = uri;
                    if (proof.size() < 3) {
                        String param = i.path("param").asString("");
                        String evidence = i.path("evidence").asString("");
                        proof.add(i.path("method").asString("GET") + " " + uri + (param.isEmpty() ? "" : " · " + param)
                                + (evidence.isEmpty() ? "" : " · observed: " + clip(evidence, 160)));
                    }
                }
                String cwe = a.path("cweid").asString("");
                out.add(new Draft("DAST", "zap", plugin, severity, a.path("alert").asString(a.path("name").asString("Alert")),
                        null, null, firstUri, clip(String.join("\n", proof), 1_200),
                        join(cwe.isEmpty() || "-1".equals(cwe) ? "" : "CWE-" + cwe, firstLine(text(a.path("reference").asString("")))),
                        clip(text(a.path("solution").asString("")), 1_200), hash("DAST", plugin)));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ k6

    /** p95 latency (ms), failed request ratio, requests per second, request count. Null when absent. */
    record Load(Double p95Ms, Double errorRate, Double rps, Double requests) {}

    static Load k6(JsonNode summary) {
        JsonNode m = summary.path("metrics");
        return new Load(num(m.path("http_req_duration").path("p(95)")),
                num(m.path("http_req_failed").has("value") ? m.path("http_req_failed").path("value") : m.path("http_req_failed").path("rate")),
                num(m.path("http_reqs").path("rate")), num(m.path("http_reqs").path("count")));
    }

    static final double P95_LIMIT_MS = 500;
    static final double ERROR_LIMIT = 0.01;

    static List<Draft> loadFindings(Load l, int vus, int seconds, String url) {
        List<Draft> out = new ArrayList<>();
        String measured = "Under " + vus + " virtual users for " + seconds + " s on " + url + ": p95 " + fmt(l.p95Ms()) + " ms, errors "
                + (l.errorRate() == null ? "?" : String.format(java.util.Locale.ROOT, "%.2f %%", l.errorRate() * 100))
                + ", " + fmt(l.rps()) + " requests/s.";
        if (l.p95Ms() != null && l.p95Ms() > P95_LIMIT_MS) {
            out.add(new Draft("LOAD", "k6", "p95-latency", l.p95Ms() > 4 * P95_LIMIT_MS ? "HIGH" : "MEDIUM",
                    "Slow under load: p95 " + fmt(l.p95Ms()) + " ms (limit " + (int) P95_LIMIT_MS + " ms)", null, null, url, measured,
                    "Performance budget: p95 < " + (int) P95_LIMIT_MS + " ms",
                    "Find the slow handler (synchronous work, missing index, unbounded query) and make it answer within "
                            + (int) P95_LIMIT_MS + " ms under " + vus + " concurrent users.", hash("LOAD", "p95-latency")));
        }
        if (l.errorRate() != null && l.errorRate() > ERROR_LIMIT) {
            out.add(new Draft("LOAD", "k6", "error-rate", l.errorRate() > 0.1 ? "HIGH" : "MEDIUM",
                    "Errors under load: " + String.format(java.util.Locale.ROOT, "%.1f %%", l.errorRate() * 100) + " of requests failed",
                    null, null, url, measured, "Reliability budget: errors < 1 %",
                    "Requests must not fail under " + vus + " concurrent users: look for crashes, exhausted connections or unhandled errors.",
                    hash("LOAD", "error-rate")));
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    static String severityOfScore(double cvss) {
        return cvss >= 9 ? "CRITICAL" : cvss >= 7 ? "HIGH" : cvss >= 4 ? "MEDIUM" : cvss > 0 ? "LOW" : "INFO";
    }

    static String severityOfLabel(String label) {
        return switch (label.toUpperCase(java.util.Locale.ROOT)) {
            case "CRITICAL" -> "CRITICAL";
            case "HIGH" -> "HIGH";
            case "MODERATE", "MEDIUM" -> "MEDIUM";
            case "LOW" -> "LOW";
            default -> "MEDIUM";
        };
    }

    static int rank(String severity) {
        int i = SEVERITIES.indexOf(severity);
        return i < 0 ? SEVERITIES.size() : i;
    }

    /** Compares dotted versions numerically (4.17.21 > 4.17.9); a suffix after the numbers is ignored. */
    static int compareVersions(String a, String b) {
        String[] x = a.replaceAll("[^0-9.].*$", "").split("\\.");
        String[] y = b.replaceAll("[^0-9.].*$", "").split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length && !x[i].isEmpty() ? Long.parseLong(x[i]) : 0;
            long q = i < y.length && !y[i].isEmpty() ? Long.parseLong(y[i]) : 0;
            if (p != q) return Long.compare(p, q);
        }
        return 0;
    }

    static String hash(String... parts) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(d.digest(String.join("\u0000", parts).getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String relative(String path, String mountedAt) {
        String p = path.startsWith(mountedAt) ? path.substring(mountedAt.length()) : path;
        return p.replaceFirst("^/+", "");
    }

    static String lineOf(Path snapshot, String file, int line) {
        if (snapshot == null || file == null || line <= 0) return "";
        try {
            Path f = snapshot.resolve(file).normalize();
            if (!f.startsWith(snapshot) || !Files.isRegularFile(f) || Files.size(f) > 2_000_000) return "";
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            return line <= lines.size() ? lines.get(line - 1) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static double number(String s) {
        try {
            return Double.parseDouble(s.strip());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static Double num(JsonNode n) {
        return n.isNumber() ? n.asDouble() : null;
    }

    private static String fmt(Double d) {
        return d == null ? "?" : String.format(java.util.Locale.ROOT, "%.0f", d);
    }

    private static String first(JsonNode n) {
        if (n.isArray()) return n.isEmpty() ? "" : n.get(0).asString("");
        return n.asString("");
    }

    private static String join(String a, String b) {
        if (a == null || a.isBlank()) return b == null || b.isBlank() ? null : b;
        return b == null || b.isBlank() ? a : a + " · " + b;
    }

    /** ZAP writes HTML paragraphs in its texts. */
    static String text(String html) {
        return html.replaceAll("(?i)</p>\\s*<p>", "\n").replaceAll("<[^>]+>", "").strip();
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    /** Counts per severity, CRITICAL first. */
    static Map<String, Integer> counts(List<Draft> drafts) {
        Map<String, Integer> c = new LinkedHashMap<>();
        SEVERITIES.forEach(s -> c.put(s, 0));
        drafts.forEach(d -> c.merge(d.severity(), 1, Integer::sum));
        return c;
    }
}
