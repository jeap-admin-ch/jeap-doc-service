package ch.admin.bit.jeap.doc.domain.template;

import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationContextTest {

    /**
     * A Markdown link is rewritten twice on its way to the reader - a remark plugin adds the environment
     * prefix, Docusaurus adds the base URL - and neither looks inside a fence. A diagram link has to carry
     * both already, or every box on every diagram leads nowhere.
     */
    @Test
    void diagramLink_carriesTheBaseUrlAndTheEnvironmentTheRewritingWouldHaveAdded() {
        GenerationContext context = contextWithLinkPrefix("/docs/dev/");

        assertThat(context.diagramLink("/systems/orders/")).isEqualTo("/docs/dev/systems/orders/");
    }

    /**
     * The prefix ends with a slash and a documentation path starts with one, so exactly one of them survives.
     */
    @Test
    void diagramLink_doesNotDoubleTheSlashBetweenThem() {
        assertThat(contextWithLinkPrefix("/docs/dev/").diagramLink("/systems/orders/"))
                .doesNotContain("//");
        assertThat(contextWithLinkPrefix("/docs/dev/").diagramLink("systems/orders/"))
                .isEqualTo("/docs/dev/systems/orders/");
    }

    /**
     * The main environment has no prefix of its own, and a site at the server root has no base URL to add.
     */
    @Test
    void diagramLink_whenThereIsNothingToAdd_thenThePathIsUnchanged() {
        assertThat(contextWithLinkPrefix("/").diagramLink("/systems/orders/"))
                .isEqualTo("/systems/orders/");
    }

    /** And it names its time zone: a bare local time is unreadable for anybody who does not know the TZ. */
    @Test
    void generatedAtDisplay_isReadableRatherThanAnInstant() {
        GenerationContext context = contextWithLinkPrefix("/");

        assertThat(context.generatedAtDisplay())
                .matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} \\S+");
    }

    private static GenerationContext contextWithLinkPrefix(String linkPrefix) {
        return new GenerationContext(ArchitectureModel.of(List.of()), "dev", "https://archrepo",
                Instant.parse("2026-08-28T05:50:00Z"), Instant.parse("2026-08-28T06:05:02Z"), 100, 4,
                linkPrefix);
    }
}
