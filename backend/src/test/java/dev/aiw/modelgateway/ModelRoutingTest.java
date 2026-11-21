package dev.aiw.modelgateway;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aiw.modelgateway.ModelProfiles.Need;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The router picks models from their ids, observations and load, without a model call (docs/15). */
class ModelRoutingTest {
    private final ModelRouter router = new ModelRouter(null, null, null);
    private final Provider groq = provider("p-groq", "Groq", true);
    private final Provider openrouter = provider("p-or", "OpenRouter", false);

    private static Provider provider(String id, String name, boolean isDefault) {
        return new Provider(id, "w", ProviderKind.OPENAI_COMPATIBLE, name, "https://x", true, Instant.now(), null, null,
                isDefault, null);
    }

    private static ModelRouter.Candidate c(Provider p, String model, Long window) {
        return new ModelRouter.Candidate(p, new ModelInfo(model, window), ModelProfiles.profile(model));
    }

    /** What Groq reports: speech, moderation and agentic routers are never chosen. */
    private final List<ModelRouter.Candidate> groqModels = List.of(
            c(groq, "openai/gpt-oss-120b", 131_072L), c(groq, "llama-3.3-70b-versatile", 131_072L),
            c(groq, "llama-3.1-8b-instant", 131_072L), c(groq, "qwen/qwen3-32b", 131_072L),
            c(groq, "moonshotai/kimi-k2-instruct-0905", 262_144L), c(groq, "whisper-large-v3", 448L),
            c(groq, "meta-llama/llama-guard-4-12b", 131_072L), c(groq, "groq/compound", 131_072L),
            c(groq, "allam-2-7b", 4_096L));

    @Test
    void profilesComeFromFamilySizeAndHints() {
        assertThat(ModelProfiles.profile("whisper-large-v3").chat()).isFalse();
        assertThat(ModelProfiles.profile("playai-tts").chat()).isFalse();
        assertThat(ModelProfiles.profile("text-embedding-3-small").chat()).isFalse();
        assertThat(ModelProfiles.profile("openai/o3").reasoning()).isGreaterThan(9);
        assertThat(ModelProfiles.profile("llama-3.3-70b-versatile").basis()).contains("llama-3.3-70b");
        // Unknown ids: parameter count, then hints.
        assertThat(ModelProfiles.profile("acme/foo-72b-chat").coding()).isEqualTo(6);
        assertThat(ModelProfiles.profile("acme/foo-72b-coder").coding()).isEqualTo(7.5);
        assertThat(ModelProfiles.profile("acme/foo-mini").coding()).isLessThan(ModelProfiles.profile("acme/foo").coding());
        // Newer cut-down versions of a known family lose what the variant takes off; batch variants are not live models.
        assertThat(ModelProfiles.profile("openai/gpt-5.4-nano").coding()).isEqualTo(5.5);
        assertThat(ModelProfiles.profile("google/gemini-3.1-flash-lite").coding()).isEqualTo(6);
        assertThat(ModelProfiles.profile("google/gemini-3.5-flash-lite:batch").chat()).isFalse();
        assertThat(ModelProfiles.profile("openrouter/auto").chat()).isFalse();
        assertThat(ModelProfiles.profile("llama-3.1-8b-instant").coding()).isEqualTo(2.5); // its size already says it
        // Longest family wins: the mini is not scored as the full model.
        assertThat(ModelProfiles.profile("gpt-5-mini").coding()).isLessThan(ModelProfiles.profile("gpt-5").coding());
    }

    @Test
    void eachNeedGetsTheModelThatFitsIt() {
        var coding = router.rank(groqModels, Map.of(), Need.CODING, null, 16_000, Map.of(), 0);
        assertThat(coding.getFirst().model()).isEqualTo("moonshotai/kimi-k2-instruct-0905");
        assertThat(coding).extracting(ModelRouter.Choice::model)
                .doesNotContain("whisper-large-v3", "meta-llama/llama-guard-4-12b", "groq/compound")
                .doesNotContain("allam-2-7b"); // 4k context: below what the work needs
        var reasoning = router.rank(groqModels, Map.of(), Need.REASONING, null, 16_000, Map.of(), 0);
        assertThat(reasoning.getFirst().model()).isEqualTo("openai/gpt-oss-120b");
        var light = router.rank(groqModels, Map.of(), Need.LIGHT, null, 0, Map.of(), 0);
        assertThat(light.getFirst().model()).isIn("llama-3.3-70b-versatile", "qwen/qwen3-32b");
    }

    @Test
    void aTeamSpreadsOverSeveralQuotas() {
        Map<String, Long> load = Map.of("p-groq|moonshotai/kimi-k2-instruct-0905", 1L);
        var second = router.rank(groqModels, Map.of(), Need.CODING, null, 16_000, load, 0);
        assertThat(second.getFirst().model()).isEqualTo("openai/gpt-oss-120b");
        assertThat(second.getFirst().reason()).contains("coding 7.5/10");
    }

    @Test
    void modelsThatBreakTheirRepliesLoseTheirRank() {
        Map<String, long[]> rep = Map.of("p-groq|moonshotai/kimi-k2-instruct-0905", new long[] {4, 6});
        var ranked = router.rank(groqModels, rep, Need.CODING, null, 16_000, Map.of(), 0);
        assertThat(ranked.getFirst().model()).isEqualTo("openai/gpt-oss-120b");
        assertThat(ranked).filteredOn(ch -> ch.model().startsWith("moonshotai")).first()
                .extracting(ModelRouter.Choice::reason).asString().contains("60% unusable replies");
    }

    @Test
    void anExhaustedModelOrProviderIsSkippedUntilItResets() {
        router.markExhausted("p-groq", "openai/gpt-oss-120b", Duration.ofMinutes(27), "daily quota reached");
        var ranked = router.rank(groqModels, Map.of(), Need.REASONING, null, 16_000, Map.of(), ModelRouter.FLOOR);
        assertThat(ranked).extracting(ModelRouter.Choice::model).doesNotContain("openai/gpt-oss-120b")
                .doesNotContain("llama-3.1-8b-instant"); // under the floor: waiting beats a weak model
        assertThat(router.secondsUntilAvailable("p-groq", "openai/gpt-oss-120b")).isBetween(1_500L, 1_620L);

        List<ModelRouter.Candidate> both = new java.util.ArrayList<>(groqModels);
        both.add(c(openrouter, "anthropic/claude-sonnet-4.5", 200_000L));
        router.markExhausted("p-or", null, Duration.ofHours(1), "credits exhausted");
        assertThat(router.rank(both, Map.of(), Need.CODING, null, 0, Map.of(), 0))
                .extracting(ModelRouter.Choice::providerId).doesNotContain("p-or");
    }

    @Test
    void failuresSayHowLongTheModelIsOut() {
        assertThat(ModelGateway.unavailableFor(new ModelCallException("Groq daily limit reached", 429, null, 1_620)))
                .isEqualTo(Duration.ofSeconds(1_620));
        // OpenAI with no credit left says 429: the whole provider is out for an hour, no rate-limit wait (2026-11-21).
        ModelCallException noCredit = new ModelCallException("Provider error 429: {\"error\": {\"type\": \"insufficient_quota\", \"code\": \"credit_balance_exhausted\"}}", 429, null);
        assertThat(ModelGateway.outOfCredits(noCredit)).isTrue();
        assertThat(ModelGateway.unavailableFor(noCredit)).isEqualTo(java.time.Duration.ofHours(1));
        assertThat(ModelGateway.outOfCredits(new ModelCallException("Rate limit reached, try again", 429, null))).isFalse();
        assertThat(ModelGateway.unavailableFor(new ModelCallException("Provider error 402: credits", 402, null)))
                .isEqualTo(Duration.ofHours(1));
        assertThat(ModelGateway.unavailableFor(new ModelCallException("larger than what Groq allows per minute", 413, null)))
                .isZero();
        assertThat(ModelGateway.unavailableFor(new ModelCallException("Provider error 400: bad request", 400, null))).isNull();
        assertThat(ModelGateway.unavailableFor(new ModelCallException("Could not reach the provider", 0,
                new java.net.SocketTimeoutException()))).isNull();
    }

    /** Token sobriety: a much pricier model must be much better to win (OpenRouter lists prices per token). */
    @Test
    void priceWeighsOnTheChoice() throws Exception {
        var listed = OpenAiCompatibleAdapter.parseModels(tools.jackson.databind.json.JsonMapper.builder().build().readTree("""
                {"data":[
                  {"id":"anthropic/claude-opus-4.1","context_length":200000,"pricing":{"prompt":"0.000015","completion":"0.000075"}},
                  {"id":"openai/gpt-oss-120b","context_length":131072,"pricing":{"prompt":"0.00000005","completion":"0.00000025"}},
                  {"id":"openrouter/auto","context_length":2000000,"pricing":{"prompt":"-1","completion":"-1"}},
                  {"id":"old/model","active":false}]}
                """));
        assertThat(listed).extracting(ModelInfo::id).containsExactly("anthropic/claude-opus-4.1", "openai/gpt-oss-120b", "openrouter/auto");
        assertThat(listed.getFirst().outputUsdPerMtok()).isCloseTo(75.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(listed.get(2).blendedUsdPerMtok()).isNull();

        var candidates = listed.stream().map(m -> new ModelRouter.Candidate(openrouter, m, ModelProfiles.profile(m.id()))).toList();
        var ranked = router.rank(candidates, Map.of(), Need.CODING, null, 16_000, Map.of(), 0);
        assertThat(ranked.getFirst().model()).isEqualTo("openai/gpt-oss-120b");
        assertThat(ranked.get(1).reason()).contains("$60.00 per million tokens");
    }
}
