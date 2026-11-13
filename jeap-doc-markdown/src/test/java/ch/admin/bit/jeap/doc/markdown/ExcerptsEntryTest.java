package ch.admin.bit.jeap.doc.markdown;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExcerptsEntryTest {

    @Test
    void excerpts_thenAFenceOfJsonWithTheComponentsSortedByName() {
        String written = new MarkdownWriter().excerpts(new ExcerptsEntry(ExcerptsEntry.Page.SYSTEM, "Inspecziun",
                "prod", List.of("inspecziun-core-scs", "inspecziun-borderpanel-scs"))).text();

        assertThat(written).isEqualTo("""
                ```jeap-excerpts
                {"page": "system", "system": "Inspecziun", "environment": "prod", \
                "components": ["inspecziun-borderpanel-scs", "inspecziun-core-scs"]}
                ```
                """);
    }

    @Test
    void excerpts_whenANameCarriesWhatJsonOrAFenceMeans_thenItStaysText() {
        String written = new MarkdownWriter().excerpts(new ExcerptsEntry(ExcerptsEntry.Page.COMPONENT,
                "a \"quoted\" \\ name", "dev", List.of("```\nend"))).text();

        assertThat(written).contains("\"system\": \"a \\\"quoted\\\" \\\\ name\"")
                .contains("\"components\": [\"```\\nend\"]")
                .startsWith("````jeap-excerpts\n");
    }

    @Test
    void excerpts_whenANameCarriesALineOrParagraphSeparator_thenItIsEscaped() {
        String written = new MarkdownWriter().excerpts(new ExcerptsEntry(ExcerptsEntry.Page.SYSTEM,
                "a\u2028b\u2029c", "dev", List.of())).text();

        assertThat(written).contains("\"system\": \"a\\u2028b\\u2029c\"");
    }
}
