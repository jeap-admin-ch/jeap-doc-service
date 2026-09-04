package ch.admin.bit.jeap.doc.domain.template;

import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructureTemplatesTest {

    @Test
    void find_answersForWhatIsOnTheClasspathAndForWhatIsNot() {
        StructureTemplates templates = new StructureTemplates(List.of(template("arc42")));

        assertThat(templates.find("arc42")).isPresent();
        assertThat(templates.find("arc24")).describedAs("a typo in a workflow configuration").isEmpty();
        assertThat(templates.ids()).containsExactly("arc42");
        assertThat(templates.all()).hasSize(1);
    }

    /**
     * An upload names a template by its id, so two templates under one id would mean the documentation lands
     * wherever the bean order happened to put it.
     */
    @Test
    void twoTemplatesUnderOneId_areRefusedWhileTheServiceStarts() {
        assertThatThrownBy(() -> new StructureTemplates(List.of(template("arc42"), template("arc42"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("arc42");
    }

    /**
     * An instance without a template still starts: it serves whatever was uploaded and generates nothing.
     */
    @Test
    void noTemplateAtAll_isAServiceThatStarts() {
        StructureTemplates templates = new StructureTemplates(List.of());

        assertThat(templates.all()).isEmpty();
        assertThat(templates.find("arc42")).isEmpty();
    }

    /**
     * A template numbers every chapter or none: half a numbering is no order at all, and it is a mistake in a
     * module on the classpath rather than something to work around at the first build.
     */
    @Test
    void aTemplateThatNumbersSomeChaptersAndNotOthers_isRefusedWhileTheServiceStarts() {
        StructureTemplate mixed = template("mixed",
                StructureChapter.numbered(1, "1-intro", "Introduction"),
                StructureChapter.unnumbered("decisions", "Decisions"));

        assertThatThrownBy(() -> new StructureTemplates(List.of(mixed)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mixed")
                .hasMessageContaining("1-intro")
                .hasMessageContaining("decisions");
    }

    @Test
    void aTemplateWithTwoChaptersInOneFolder_isRefusedWhileTheServiceStarts() {
        StructureTemplate template = template("twice",
                StructureChapter.unnumbered("decisions", "Decisions"),
                StructureChapter.unnumbered("decisions", "Architecture Decisions"));

        assertThatThrownBy(() -> new StructureTemplates(List.of(template)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("folder")
                .hasMessageContaining("decisions");
    }

    /**
     * Two chapters at one URL are one page, and the second would be written over the first while the navigation
     * still named both - which nothing downstream notices.
     */
    @Test
    void aTemplateWithTwoChaptersAtOneUrl_isRefusedWhileTheServiceStarts() {
        StructureTemplate template = template("colliding",
                StructureChapter.numbered(5, "5-glossary", "Glossary"),
                StructureChapter.numbered(6, "6-glossary", "Glossary Again"));

        assertThatThrownBy(() -> new StructureTemplates(List.of(template)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("URL segment")
                .hasMessageContaining("glossary");
    }

    /**
     * Two chapters with one number pass every other check - different folders, different URLs - and then carry
     * the same position into their category files, which leaves their order to whichever version of the site
     * generator is installed. That is the one thing the ordering exists to prevent.
     */
    @Test
    void aTemplateWithTwoChaptersOfOneNumber_isRefusedWhileTheServiceStarts() {
        StructureTemplate template = template("renumbered",
                StructureChapter.numbered(5, "5-building-block-view", "Building Block View"),
                StructureChapter.numbered(5, "5-runtime-view", "Runtime View"));

        assertThatThrownBy(() -> new StructureTemplates(List.of(template)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("number")
                .hasMessageContaining("renumbered");
    }

    /**
     * The order the navigation shows the chapters in, and the position each one carries into its category file:
     * by number where the template numbers them, gaps kept, so a reader of arc42 sees that a chapter has not
     * been written.
     */
    @Test
    void orderedChapters_whenTheyAreNumbered_thenByTheirNumbersWhateverOrderTheyWereDeclaredIn() {
        StructureTemplate template = template("numbered",
                StructureChapter.numbered(9, "9-adr", "Architecture Decisions"),
                StructureChapter.numbered(1, "1-intro", "Introduction"),
                StructureChapter.numbered(5, "5-building-block-view", "Building Block View"));

        assertThat(template.orderedChapters()).extracting(StructureChapter::folder)
                .containsExactly("1-intro", "5-building-block-view", "9-adr");
        assertThat(template.orderedChapters()).allSatisfy(chapter ->
                assertThat(template.positionOf(chapter)).isEqualTo(chapter.number()));
    }

    /**
     * And alphabetically by title where it does not number them, with the positions counted from 1 - so the
     * order is this service's rule rather than whatever the site generator sorts by.
     */
    @Test
    void orderedChapters_whenTheyAreNotNumbered_thenAlphabeticallyByTitleWithPositionsFromOne() {
        StructureTemplate template = template("unnumbered",
                StructureChapter.unnumbered("quality-goals", "Quality Goals"),
                StructureChapter.unnumbered("decisions", "Decisions"),
                StructureChapter.unnumbered("glossary", "Glossary"));

        assertThat(template.orderedChapters()).extracting(StructureChapter::title)
                .containsExactly("Decisions", "Glossary", "Quality Goals");
        assertThat(template.positionOf(StructureChapter.unnumbered("decisions", "Decisions"))).isEqualTo(1);
        assertThat(template.positionOf(StructureChapter.unnumbered("glossary", "Glossary"))).isEqualTo(2);
        assertThat(template.positionOf(StructureChapter.unnumbered("quality-goals", "Quality Goals")))
                .isEqualTo(3);
    }

    @Test
    void positionOf_whenTheChapterIsNotOneOfTheTemplates_thenItSaysSo() {
        StructureTemplate template = template("unnumbered",
                StructureChapter.unnumbered("decisions", "Decisions"));

        assertThatThrownBy(() -> template.positionOf(StructureChapter.unnumbered("glossary", "Glossary")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Glossary");
    }

    private static StructureTemplate template(String id) {
        return template(id, StructureChapter.numbered(1, "1-intro", "Introduction and Goals"));
    }

    private static StructureTemplate template(String id, StructureChapter... chapters) {
        return new StructureTemplate() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String systemPathSegment() {
                return "system-architecture";
            }

            @Override
            public String systemLabel() {
                return "System Architecture";
            }

            @Override
            public String componentPathSegment() {
                return "component-architecture";
            }

            @Override
            public String componentLabel() {
                return "Component Architecture";
            }

            @Override
            public List<StructureChapter> chapters() {
                return List.of(chapters);
            }

            @Override
            public void writeSystem(DocumentedSystem system, GenerationContext context, Path directory) {
                // nothing: the registry is what is under test
            }
        };
    }
}
