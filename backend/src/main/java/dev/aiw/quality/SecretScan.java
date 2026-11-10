package dev.aiw.quality;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Finds credentials in added lines of a diff. A finding is critical: the validation engine refuses the merge. */
public final class SecretScan {
    private SecretScan() {}

    public record Finding(String file, String kind, String excerpt) {}

    private static final Object[][] RULES = {
            {"Private key", Pattern.compile("-----BEGIN (RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----")},
            {"AWS access key", Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b")},
            {"GitHub token", Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{36,}\\b")},
            {"Anthropic key", Pattern.compile("\\bsk-ant-[A-Za-z0-9_-]{20,}")},
            {"OpenAI-style key", Pattern.compile("\\bsk-(proj-)?[A-Za-z0-9]{32,}")},
            {"Google API key", Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b")},
            {"Slack token", Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}")},
            {"Password assignment", Pattern.compile("(?i)\\b(password|passwd|secret|api_?key)\\s*[:=]\\s*['\"][^'\"\\s]{8,}['\"]")},
    };

    public static List<Finding> scan(String unifiedDiff) {
        List<Finding> out = new ArrayList<>();
        String file = null;
        for (String line : unifiedDiff.split("\n")) {
            if (line.startsWith("+++ ")) {
                file = line.startsWith("+++ b/") ? line.substring(6) : line.substring(4);
                continue;
            }
            if (!line.startsWith("+") || line.startsWith("+++")) continue;
            for (Object[] r : RULES) {
                if (((Pattern) r[1]).matcher(line).find()) {
                    String s = line.substring(1).strip();
                    out.add(new Finding(file, (String) r[0], s.length() > 12 ? s.substring(0, 12) + "…" : s));
                    break;
                }
            }
        }
        return out;
    }
}
