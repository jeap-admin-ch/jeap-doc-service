package ch.admin.bit.jeap.doc.markdown;

import org.junit.jupiter.api.Test;

import static ch.admin.bit.jeap.doc.markdown.FrontMatter.frontMatter;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FrontMatterTest {

    /**
     * The values that would be a YAML syntax error if they were written as they are. Each of them fails the
     * site build minutes into a run, with a message naming a line of a generated file rather than the system
     * the value came from.
     */
    @Test
    void everyStringIsADoubleQuotedScalar() {
        String block = frontMatter()
                .put("title", "jEAP: Documentation")
                .put("sidebar_label", "# not a heading")
                .put("description", "a \"quoted\" word, and a \\ backslash")
                .render();

        assertThat(block).isEqualTo("""
                ---
                title: "jEAP: Documentation"
                sidebar_label: "# not a heading"
                description: "a \\"quoted\\" word, and a \\\\ backslash"
                ---
                """);
    }

    @Test
    void aNewlineInAValueDoesNotEndTheLine() {
        assertThat(frontMatter().put("title", "first\nsecond").render())
                .isEqualTo("---\ntitle: \"first\\nsecond\"\n---\n");
    }

    @Test
    void numbersAndBooleansAreNotQuoted() {
        assertThat(frontMatter().put("sidebar_position", 3).put("draft", false).render())
                .isEqualTo("---\nsidebar_position: 3\ndraft: false\n---\n");
    }

    /**
     * An absent key is what Docusaurus treats as "not set"; an empty one is a value, and an empty title puts an
     * empty entry in the sidebar.
     */
    @Test
    void aValueThatIsNotThereIsNotWritten() {
        assertThat(frontMatter().put("title", "Kept").put("tagline", (String) null).put("blank", "  ").render())
                .isEqualTo("---\ntitle: \"Kept\"\n---\n");
    }

    @Test
    void nothingAtAllRendersNothingAtAll() {
        assertThat(frontMatter().render()).isEmpty();
        assertThat(frontMatter().isEmpty()).isTrue();
    }

    @Test
    void keysAreLowerCaseWithUnderscores() {
        FrontMatter frontMatter = frontMatter();
        assertThatThrownBy(() -> frontMatter.put("sidebarLabel", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void categoryFile_quotesItsLabelToo() {
        assertThat(CategoryFile.of("9. Architecture Decisions", 9)).isEqualTo("""
                {
                  "label": "9. Architecture Decisions",
                  "position": 9
                }
                """);
    }

    /** A category that starts open, which is what a reader sees of a chapter without clicking. */
    @Test
    void categoryFile_expanded_writesTheCollapsedFlag() {
        assertThat(CategoryFile.expanded("5. Building Block View", 5)).isEqualTo("""
                {
                  "label": "5. Building Block View",
                  "position": 5,
                  "collapsed": false
                }
                """);
    }

    /**
     * The custom property is what the site template matches a category on, so its exact spelling is a
     * contract between this module and {@code docusaurus.config.js}. Pinned here rather than in a browser.
     */
    @Test
    void categoryFile_marked_writesTheCustomProperty() {
        assertThat(CategoryFile.marked("Systems", 1, "systemsIndex")).isEqualTo("""
                {
                  "label": "Systems",
                  "position": 1,
                  "customProps": {
                    "systemsIndex": true
                  }
                }
                """);
    }
}
