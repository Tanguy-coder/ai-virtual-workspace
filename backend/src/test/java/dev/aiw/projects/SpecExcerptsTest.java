package dev.aiw.projects;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpecExcerptsTest {
    private static ProjectDocument doc(String content) {
        return new ProjectDocument("d1", "p1", "SPECIFICATION", "spec.md", "text/markdown", content.length(), "u",
                Instant.EPOCH, content);
    }

    private static final String SPEC = """
            # Banking Core
            A core banking platform for retail customers.

            ## Accounts
            %s
            Customers open current and savings accounts.

            ## Transfers
            %s
            Transfers between accounts are idempotent and audited.

            ## Reporting
            %s
            Monthly statements are generated as PDF.
            """.formatted("Filler about accounts. ".repeat(40), "Filler about money movement. ".repeat(40),
            "Filler about documents. ".repeat(40));

    @Test
    void sendsAShortSpecificationWhole() {
        String out = SpecExcerpts.select(List.of(doc("# Tiny\nOne line.")), "anything", 10_000);
        assertThat(out).contains("One line.").doesNotContain("Excerpts of");
    }

    @Test
    void keepsTheOpeningAndTheSectionsThatMatchTheQuery() {
        String out = SpecExcerpts.select(List.of(doc(SPEC)), "idempotent transfers audit", 2_400);
        assertThat(out).startsWith("Excerpts of the specification")
                .contains("A core banking platform")
                .contains("Transfers between accounts are idempotent")
                .doesNotContain("Monthly statements");
        assertThat(out.length()).isLessThan(2_600);
        assertThat(out.indexOf("Banking Core")).isLessThan(out.indexOf("## Transfers"));
    }

    @Test
    void isDeterministicSoThePromptPrefixStaysCacheable() {
        assertThat(SpecExcerpts.select(List.of(doc(SPEC)), "accounts", 2_000))
                .isEqualTo(SpecExcerpts.select(List.of(doc(SPEC)), "accounts", 2_000));
    }

    @Test
    void matchesAcrossLanguagesAndAccents() {
        assertThat(SpecExcerpts.words("Sécurité architecture")).containsExactly("securi", "archit");
        assertThat(SpecExcerpts.words("architect")).containsExactly("archit");
    }
}
