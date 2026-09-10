package ch.admin.bit.jeap.doc.domain.template;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReactionIdsTest {

    /** Most message types have no variant, and their page draws one diagram whose ids carry no prefix. */
    @Test
    void prefixOf_whenThereIsNoVariant_thenThereIsNoPrefix() {
        assertThat(ReactionIds.prefixOf(null)).isEmpty();
        assertThat(ReactionIds.prefixOf("")).isEmpty();
    }

    /** A variant is an upstream string that may carry anything, and this ends up in the page's DOM. */
    @Test
    void prefixOf_thenTheVariantIsReducedToWhatADomIdCarries() {
        assertThat(ReactionIds.prefixOf("camiuns_Assessment")).isEqualTo("camiuns-assessment-");
        assertThat(ReactionIds.prefixOf("NES_RiskAnalysisRequest")).isEqualTo("nes-riskanalysisrequest-");
        assertThat(ReactionIds.prefixOf("  spaces and #!? ")).isEqualTo("spaces-and-");
        assertThat(ReactionIds.prefixOf("Ünïcödé")).isEqualTo("n-c-d-");
    }

    /**
     * The same variant always gives the same prefix, whichever pass asks - which is the whole point: the page
     * that writes a link into a message page holds one system's graphs and knows nothing of that page.
     */
    @Test
    void prefixOf_thenItIsAFunctionOfTheVariantAlone() {
        assertThat(ReactionIds.prefixOf("legacy")).isEqualTo(ReactionIds.prefixOf("legacy"));
    }

    /** One page's diagrams in the order they are drawn, and a variant's own prefix for each. */
    @Test
    void prefixesOf_thenEachVariantKeepsItsOwn() {
        assertThat(ReactionIds.prefixesOf(List.of("", "legacy", "NES_Risk")))
                .containsExactly("", "legacy-", "nes-risk-");
    }

    /**
     * Two variants that slug alike are a corner case, and one a reader could not tell apart either. The
     * first claim keeps the prefix and the next counts up, so a link addressing the bare slug reaches the
     * first and the page still carries no id twice.
     */
    @Test
    void prefixesOf_whenTwoVariantsSlugAlike_thenTheFirstKeepsItAndTheRestCountUp() {
        assertThat(ReactionIds.prefixesOf(List.of("a_b", "a-b", "a.b")))
                .containsExactly("a-b-", "a-b-2-", "a-b-3-");
    }

    /** And a variant that slugs to nothing queues behind the diagram that has no variant at all. */
    @Test
    void prefixesOf_whenAVariantSlugsToNothing_thenItQueuesBehindTheUnvariantOne() {
        assertThat(ReactionIds.prefixesOf(List.of("", "???"))).containsExactly("", "2-");
    }

    /** The ids themselves: what a fence writes, and what a link addresses. */
    @Test
    void nodeIds_thenTheyCarryTheirKindAndTheirPrefix() {
        assertThat(ReactionIds.messageId("", 44421)).isEqualTo("MESSAGE-44421");
        assertThat(ReactionIds.messageId("camiuns-assessment-", 44421))
                .isEqualTo("camiuns-assessment-MESSAGE-44421");
        assertThat(ReactionIds.reactionId(null, 98989)).isEqualTo("REACTION-98989");
    }
}
