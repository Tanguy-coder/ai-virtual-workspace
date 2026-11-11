package dev.aiw.modelgateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Reading of real provider limit answers (Groq, OpenRouter), without network or waits. */
class ModelGatewayRateLimitTest {
    private final ModelGateway gateway = new ModelGateway(null, null, null, null, null, null) {
        @Override void sleep(long ms) {}
    };
    private final Provider groq = new Provider("p1", "w1", ProviderKind.OPENAI_COMPATIBLE, "Groq", "https://api.groq.com/openai/v1",
            true, null, null, null, true, "groq");

    private static ModelRequest request(long maxTokens) {
        return new ModelRequest("w1", "pr1", "a1", "p1", "openai/gpt-oss-120b", null, "system",
                List.of(new ChatTurn(ChatTurn.Role.USER, "hi")), maxTokens, "medium", ModelRequest.Tier.STANDARD, ModelRequest.Cache.NONE);
    }

    private static ModelCallException error(int status, String message) {
        return new ModelCallException("Provider error " + status + ": {\"error\":{\"message\":\"" + message + "\"}}", status, null);
    }

    @Test
    void aPromptAboveTheTokensPerMinuteIsReportedOnceItStopsShrinking() {
        String tpm = "Request too large for model `openai/gpt-oss-120b` in organization `org_1` service tier `on_demand` on tokens "
                + "per minute (TPM): Limit 8000, Requested 9388, please reduce your message size and try again.";
        long[] last = {-1};
        ModelRequest smaller = gateway.afterRateLimit(groq, request(8_000), error(413, tpm), 0, last);
        assertThat(smaller.maxTokens()).isEqualTo(8_000 - 1_388 - 64);
        assertThat(gateway.tokensPerMinute("p1", "openai/gpt-oss-120b")).contains(8_000L);
        // Groq answers the same "Requested": the prompt itself is too big; no more pointless retries.
        assertThatThrownBy(() -> gateway.afterRateLimit(groq, smaller, error(413, tpm), 1, last))
                .isInstanceOf(ModelCallException.class).hasMessageContaining("The prompt (about 9388 tokens) is larger");
    }

    @Test
    void outputLimitsAndWaitsAreRead() {
        long[] last = {-1};
        ModelRequest capped = gateway.afterRateLimit(groq, request(4_000), error(429, "Request too large for model `m` on output "
                + "tokens per minute (OTPM): Limit 1000, Requested 4000. The request's expected output tokens exceed the enforced limit"), 0, last);
        assertThat(capped.maxTokens()).isEqualTo(1_000);
        assertThat(gateway.outputCap("p1", "openai/gpt-oss-120b")).contains(1_000L);
        ModelRequest same = request(1_000);
        assertThat(gateway.afterRateLimit(groq, same, error(429, "Rate limit reached ... (TPM): Limit 8000, Used 4477, "
                + "Requested 4577. Please try again in 7.905s."), 0, last)).isSameAs(same);
        assertThat(gateway.afterRateLimit(groq, same, error(401, "Invalid API key"), 0, last)).isNull();
    }
}
