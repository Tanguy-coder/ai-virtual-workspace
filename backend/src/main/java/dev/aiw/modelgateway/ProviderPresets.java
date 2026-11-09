package dev.aiw.modelgateway;

import java.util.List;
import java.util.Optional;

/**
 * Ready-made provider settings. Anthropic has its own adapter; every other provider here speaks the OpenAI
 * Chat Completions API, so one adapter serves them all. Model ids are not hard-coded for them: the UI lists the
 * models the provider itself reports ({@code GET /models}) once the key is saved.
 */
public final class ProviderPresets {
    private ProviderPresets() {}

    /**
     * @param keyRequired false for local servers (Ollama, LM Studio)
     * @param defaultModel / economyModel suggested models, null when the provider's own list should be used
     */
    public record Preset(String id, String label, ProviderKind kind, String baseUrl, boolean keyRequired, boolean local,
                         String defaultModel, String economyModel, String keyUrl) {}

    public static final List<Preset> ALL = List.of(
            new Preset("anthropic", "Anthropic (Claude)", ProviderKind.ANTHROPIC, null, true, false,
                    ProviderService.DEFAULT_ANTHROPIC_MODEL, ProviderService.DEFAULT_ANTHROPIC_ECONOMY_MODEL,
                    "https://console.anthropic.com/settings/keys"),
            new Preset("openai", "OpenAI", ProviderKind.OPENAI_COMPATIBLE, "https://api.openai.com/v1", true, false,
                    null, null, "https://platform.openai.com/api-keys"),
            new Preset("gemini", "Google Gemini", ProviderKind.OPENAI_COMPATIBLE,
                    "https://generativelanguage.googleapis.com/v1beta/openai", true, false, null, null,
                    "https://aistudio.google.com/apikey"),
            new Preset("mistral", "Mistral AI", ProviderKind.OPENAI_COMPATIBLE, "https://api.mistral.ai/v1", true, false,
                    null, null, "https://console.mistral.ai/api-keys"),
            new Preset("deepseek", "DeepSeek", ProviderKind.OPENAI_COMPATIBLE, "https://api.deepseek.com/v1", true, false,
                    null, null, "https://platform.deepseek.com/api_keys"),
            new Preset("xai", "xAI (Grok)", ProviderKind.OPENAI_COMPATIBLE, "https://api.x.ai/v1", true, false,
                    null, null, "https://console.x.ai"),
            new Preset("groq", "Groq", ProviderKind.OPENAI_COMPATIBLE, "https://api.groq.com/openai/v1", true, false,
                    null, null, "https://console.groq.com/keys"),
            new Preset("together", "Together AI", ProviderKind.OPENAI_COMPATIBLE, "https://api.together.xyz/v1", true, false,
                    null, null, "https://api.together.ai/settings/api-keys"),
            new Preset("openrouter", "OpenRouter (many providers)", ProviderKind.OPENAI_COMPATIBLE,
                    "https://openrouter.ai/api/v1", true, false, null, null, "https://openrouter.ai/settings/keys"),
            new Preset("ollama", "Ollama (local)", ProviderKind.OPENAI_COMPATIBLE, "http://localhost:11434/v1", false, true,
                    null, null, null),
            new Preset("lmstudio", "LM Studio (local)", ProviderKind.OPENAI_COMPATIBLE, "http://localhost:1234/v1", false, true,
                    null, null, null),
            new Preset("custom", "Other OpenAI-compatible API", ProviderKind.OPENAI_COMPATIBLE, null, false, false,
                    null, null, null));

    public static Optional<Preset> find(String id) {
        return ALL.stream().filter(p -> p.id().equals(id)).findFirst();
    }
}
