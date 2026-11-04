package ch.admin.bit.jeap.doc.markdown;

import org.junit.jupiter.api.Test;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

class ReactionEntryTest {
    @Test
    void componentLinksRoundTripWithoutBecomingFenceSyntax() {
        String name = "a&b=\"<script>\n```\nÄ";
        String href = "/site/docs/dev/systems/a/components/b/runtime";
        String encoded = new ReactionEntry(ReactionEntry.Kind.SYSTEM, "s", null, null, null)
                .withComponentLinks(java.util.List.of(new ReactionEntry.ComponentLink("s", name, href))).encoded();
        String nested = URLDecoder.decode(encoded.substring(encoded.indexOf("&componentLink=") + 15), StandardCharsets.UTF_8);
        var values = java.util.Arrays.stream(nested.split("&")).map(part -> part.split("=", 2))
                .collect(java.util.stream.Collectors.toMap(parts -> parts[0],
                        parts -> URLDecoder.decode(parts[1], StandardCharsets.UTF_8)));
        assertThat(values).containsExactlyInAnyOrderEntriesOf(java.util.Map.of("system", "s", "component", name, "href", href));
        assertThat(encoded).doesNotContain("\n", "```", "<script>");
    }

    @Test
    void hostileNamesRemainOneInertFenceAndRoundTrip() {
        String name = "a&b=\"<script>\n```\nÄ";
        String rendered = new MarkdownWriter().reactionViewer(
                new ReactionEntry(ReactionEntry.Kind.MESSAGE, null, null, name, "")).text();
        assertThat(rendered).isEqualTo("```reaction-view\nkind=MESSAGE&system=&component=&message=a%26b%3D%22%3Cscript%3E%0A%60%60%60%0A%C3%84&variant=\n```\n");
        assertThat(URLDecoder.decode("a%26b%3D%22%3Cscript%3E%0A%60%60%60%0A%C3%84", StandardCharsets.UTF_8)).isEqualTo(name);
    }
}
