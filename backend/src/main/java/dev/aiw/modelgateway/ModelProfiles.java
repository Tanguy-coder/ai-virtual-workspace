package dev.aiw.modelgateway;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a model is likely good at, from its id alone (docs/15). Pure and deterministic: the code decides which model
 * serves which work, no model call is spent on it. Scores are priors on a 0–10 scale; {@link ModelRouter} adjusts them
 * with what the platform has observed (unusable answers) and with the provider's limits.
 *
 * <p>Order of evidence: a known family (longest match wins), else the parameter count in the id ("70b"), else a
 * neutral guess. Name hints adjust unknown models: "coder" helps coding, "mini/nano/lite" lowers both.
 */
public final class ModelProfiles {
    private ModelProfiles() {}

    /** The kind of work a call does: the router ranks models per need. */
    public enum Need { CODING, REASONING, LIGHT }

    /** {@code chat}: false for audio, embedding, moderation, image models, which never serve agents. */
    public record Profile(boolean chat, double coding, double reasoning, String basis) {
        public double score(Need need) {
            return switch (need) {
                case CODING -> coding;
                case REASONING -> reasoning;
                // Light work (summaries, extraction): reliable enough, and the smaller the better beyond that.
                case LIGHT -> Math.min(reasoning, 6.5) - Math.max(0, reasoning - 6.5) * 0.3;
            };
        }
    }

    private record Family(String pattern, double coding, double reasoning) {}

    /**
     * Known families, matched as substrings of the lower-cased id; "^" anchors at the start of the name without its
     * vendor prefix ("openai/o3" → "o3"). Rough public standing, not a claim of the models. */
    private static final List<Family> FAMILIES = List.of(
            new Family("claude-opus", 10, 10), new Family("claude-sonnet", 9, 9), new Family("claude-3-5-sonnet", 8, 8),
            new Family("claude-haiku", 6.5, 6.5), new Family("claude-3-haiku", 4.5, 4.5),
            new Family("gpt-5", 9.5, 9.5), new Family("gpt-5-mini", 7.5, 7.5), new Family("gpt-5-nano", 5, 5),
            new Family("gpt-4.1", 8, 8), new Family("gpt-4.1-mini", 6.5, 6.5), new Family("gpt-4.1-nano", 4, 4),
            new Family("gpt-4o", 7, 7), new Family("gpt-4o-mini", 5, 5),
            new Family("^o3", 9, 9.5), new Family("^o4-mini", 8, 8.5), new Family("^o3-mini", 7.5, 8),
            new Family("gpt-oss-120b", 7.5, 8), new Family("gpt-oss-20b", 5.5, 6),
            new Family("kimi-k2", 8, 7.5),
            new Family("deepseek-v3", 8, 8), new Family("deepseek-chat", 8, 8), new Family("deepseek-r1", 7, 8.5),
            new Family("deepseek-reasoner", 7, 8.5), new Family("r1-distill-llama-70b", 5.5, 6.5),
            new Family("qwen3-coder", 8.5, 7), new Family("qwen3-235b", 8, 8), new Family("qwen3-32b", 6.5, 7),
            new Family("qwq", 6, 7), new Family("qwen-2.5-coder-32b", 6.5, 5.5), new Family("qwen2.5-coder-32b", 6.5, 5.5),
            new Family("llama-3.3-70b", 6, 6.5), new Family("llama-3.1-405b", 7, 7), new Family("llama-3.1-70b", 5.5, 6),
            new Family("llama-3.1-8b", 2.5, 3), new Family("llama3-8b", 2, 2.5), new Family("llama-3.2-3b", 1.5, 2),
            new Family("llama-4-maverick", 6.5, 6.5), new Family("llama-4-scout", 5.5, 5.5),
            new Family("gemini-2.5-pro", 9, 9), new Family("gemini-2.5-flash", 7.5, 7.5),
            new Family("gemini-2.5-flash-lite", 5.5, 5.5), new Family("gemini-2.0-flash", 6, 6),
            new Family("gemini-2.0-flash-lite", 5, 5), new Family("gemini-3", 9.5, 9.5),
            new Family("mistral-large", 7, 7), new Family("mistral-medium", 6.5, 6.5), new Family("mistral-small", 5, 5),
            new Family("codestral", 7, 5), new Family("devstral", 7.5, 6), new Family("magistral", 6, 7),
            new Family("grok-4", 9, 9), new Family("grok-3", 8, 8), new Family("grok-3-mini", 6, 6.5),
            new Family("grok-code-fast", 7.5, 6),
            new Family("glm-4.5", 8, 7.5), new Family("glm-4.6", 8.5, 8), new Family("minimax-m2", 7.5, 7),
            new Family("gemma2-9b", 2.5, 3), new Family("gemma-3-27b", 4.5, 5), new Family("allam-2-7b", 1.5, 2));

    /** Not chat models: speech, embeddings, moderation, images, routers that run their own tools. */
    private static final Pattern NOT_CHAT = Pattern.compile(
            "whisper|tts|playai|audio|transcri|speech|embed|guard|moderation|rerank|dall-e|image|imagen|vision-only"
                    + "|realtime|search-preview|compound|-ocr|ocr-|sora|veo|lyria"
                    // Batch variants answer hours later; routers pick a model (and a price) themselves.
                    + "|:batch|^openrouter/auto");
    private static final Pattern SIZE = Pattern.compile("(?<![\\d.])(\\d+(?:\\.\\d+)?)b(?![a-z])");
    /**
     * Cut-down variants of a family, and what they take off its scores: "gpt-5.4-nano" is not a "gpt-5". Not applied
     * when the matched family already is that variant ("gpt-5-mini", "gemini-2.5-flash").
     */
    private static final List<Map.Entry<String, Double>> VARIANTS = List.of(Map.entry("nano", 4.0), Map.entry("tiny", 4.0),
            Map.entry("lite", 2.5), Map.entry("mini", 2.0), Map.entry("small", 2.0), Map.entry("instant", 2.0),
            Map.entry("flash", 1.0), Map.entry("air", 1.0));
    private static final Pattern CODE_HINT = Pattern.compile("coder|codestral|devstral|code");
    private static final Pattern THINK_HINT = Pattern.compile("(?<![a-z])r1(?![a-z0-9])|reason|thinking|qwq");

    public static Profile profile(String modelId) {
        String id = modelId == null ? "" : modelId.toLowerCase(Locale.ROOT);
        if (id.isBlank() || NOT_CHAT.matcher(id).find()) return new Profile(false, 0, 0, "not a chat model");
        String name = id.substring(id.lastIndexOf('/') + 1);
        Family best = null;
        for (Family f : FAMILIES) {
            boolean hit = f.pattern().startsWith("^") ? name.startsWith(f.pattern().substring(1)) : id.contains(f.pattern());
            if (hit && (best == null || f.pattern().length() > best.pattern().length())) best = f;
        }
        double coding, reasoning;
        String basis;
        if (best != null) {
            coding = best.coding();
            reasoning = best.reasoning();
            basis = "known family " + best.pattern();
        } else {
            double base = 3.5;
            basis = "unknown model";
            Matcher size = SIZE.matcher(id);
            if (size.find()) {
                double b = Double.parseDouble(size.group(1));
                base = b >= 300 ? 7.5 : b >= 100 ? 7 : b >= 60 ? 6 : b >= 25 ? 5 : b >= 13 ? 4 : b >= 6 ? 2.5 : 1.5;
                basis = "size " + size.group(1) + "B";
            }
            coding = base + (CODE_HINT.matcher(id).find() ? 1.5 : 0);
            reasoning = base + (THINK_HINT.matcher(id).find() ? 1 : 0);
        }
        // A size in the id already says how small it is; otherwise the variant word does.
        boolean sized = best != null ? SIZE.matcher(best.pattern()).find() : basis.startsWith("size");
        if (!sized) {
            String family = best == null ? "" : best.pattern();
            for (var v : VARIANTS) {
                if (Pattern.compile("(?<![a-z])" + v.getKey() + "(?![a-z])").matcher(name).find() && !family.contains(v.getKey())) {
                    coding -= v.getValue();
                    reasoning -= v.getValue();
                    basis += ", " + v.getKey() + " variant";
                }
            }
        }
        return new Profile(true, clamp(coding), clamp(reasoning), basis);
    }

    private static double clamp(double v) {
        return Math.max(1, Math.min(10, v));
    }
}
