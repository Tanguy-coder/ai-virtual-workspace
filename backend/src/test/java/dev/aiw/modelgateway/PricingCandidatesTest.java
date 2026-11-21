package dev.aiw.modelgateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Seen 2026-11-21: every gpt-4.1-mini-2025-04-14 call was unpriced, so a project's USD cap never applied. */
class PricingCandidatesTest {
    @Test
    void datedAndPrefixedIdsResolveToTheListedModel() {
        assertThat(PricingService.candidates("gpt-4.1-mini-2025-04-14")).contains("gpt-4.1-mini");
        assertThat(PricingService.candidates("openai/gpt-oss-120b")).contains("gpt-oss-120b");
        assertThat(PricingService.candidates("claude-haiku-5-5-20260101")).contains("claude-haiku-5-5");
        assertThat(PricingService.candidates("gpt-4.1")).containsExactly("gpt-4.1");
    }
}
