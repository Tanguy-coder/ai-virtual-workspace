package dev.aiw.modelgateway;

/**
 * A model a provider reports for its key. {@code contextWindow} in tokens, prices in USD per million tokens: each is
 * null when the provider does not say it (Groq gives the window, OpenRouter both).
 */
public record ModelInfo(String id, Long contextWindow, Double inputUsdPerMtok, Double outputUsdPerMtok) {
    public ModelInfo(String id, Long contextWindow) {
        this(id, contextWindow, null, null);
    }

    ModelInfo withPrice(double input, double output) {
        return new ModelInfo(id, contextWindow, input, output);
    }

    /** Blended price for agent work (output weighs 3x: replies are patches and reasoning), or null when unknown. */
    Double blendedUsdPerMtok() {
        return inputUsdPerMtok == null || outputUsdPerMtok == null ? null : (inputUsdPerMtok + 3 * outputUsdPerMtok) / 4;
    }
}
