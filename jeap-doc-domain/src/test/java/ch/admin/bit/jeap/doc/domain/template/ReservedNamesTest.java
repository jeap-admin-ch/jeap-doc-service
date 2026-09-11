package ch.admin.bit.jeap.doc.domain.template;

import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The names a chapter's generated pages occupy.
 * <p>
 * <b>Its own test because it has two readers.</b> The upload validation refuses such a name and the site
 * generator drops one that got in before the rule existed, and the two giving different answers is what lets
 * a page through the backstop and fails the whole part on a duplicate route.
 */
class ReservedNamesTest {

    private static final StructureChapter INTRO = new StructureChapter(1, "1-intro", "Introduction");
    private static final StructureTemplate TEMPLATE = new OnePageTemplate();

    @Test
    void isTaken_thenTheLandingPageNamesAreOccupiedWhateverTheirCase() {
        assertThat(taken("index.md")).isTrue();
        assertThat(taken("INDEX.MD")).isTrue();
        assertThat(taken("readme.md")).isTrue();
        assertThat(taken("README.md")).isTrue();
        assertThat(taken("ReadMe.md"))
                .describedAs("the site generator folds the landing-page names, so this rule folds them too")
                .isTrue();
    }

    @Test
    void isTaken_thenTheChapterFoldersOwnNameIsOccupied() {
        assertThat(taken("1-intro.md"))
                .describedAs("the generator reads a document named after its folder as that chapter's "
                             + "landing page")
                .isTrue();
        assertThat(taken("intro.md"))
                .describedAs("and only as written: the stripped name is an ordinary page at a URL of its own")
                .isFalse();
    }

    @Test
    void isTaken_thenANumberPrefixDoesNotHideAReservedName() {
        assertThat(taken("01-index.md"))
                .describedAs("a leading number is not part of a document's id, so this is 'index'")
                .isTrue();
        assertThat(taken("02-whitebox.md")).isTrue();
        assertThat(taken("2021-11-whitebox.md"))
                .describedAs("what looks like a date is not a prefix, so this document is not 'whitebox'")
                .isFalse();
    }

    @Test
    void isTaken_thenWhatTheTemplateGeneratesIsOccupied() {
        assertThat(taken("whitebox.md")).isTrue();
        assertThat(taken("whitebox-view.md")).isFalse();
        assertThat(ReservedNames.isTaken(TEMPLATE, INTRO, SubjectKind.LIBRARY, "whitebox.md"))
                .describedAs("a template generates different pages for different kinds of subject")
                .isFalse();
    }

    @Test
    void isTaken_thenANameOfItsOwnIsNotOccupied() {
        assertThat(taken("goals.md")).isFalse();
        assertThat(taken("01-goals.md")).isFalse();
    }

    @Test
    void withoutItsExtension_thenALeadingDotIsPartOfTheName() {
        assertThat(ReservedNames.withoutItsExtension("goals.md")).isEqualTo("goals");
        assertThat(ReservedNames.withoutItsExtension("goals.draft.md")).isEqualTo("goals.draft");
        assertThat(ReservedNames.withoutItsExtension(".gitignore")).isEqualTo(".gitignore");
        assertThat(ReservedNames.withoutItsExtension("goals")).isEqualTo("goals");
    }

    private static boolean taken(String fileName) {
        return ReservedNames.isTaken(TEMPLATE, INTRO, SubjectKind.SYSTEM, fileName);
    }

    /** A template that generates one page into the introduction of a system, and nothing else. */
    private static final class OnePageTemplate implements StructureTemplate {

        @Override
        public String id() {
            return "test-template";
        }

        @Override
        public String systemPathSegment() {
            return "test";
        }

        @Override
        public String systemLabel() {
            return "Test";
        }

        @Override
        public String componentPathSegment() {
            return "test-component";
        }

        @Override
        public String componentLabel() {
            return "Test Component";
        }

        @Override
        public String libraryPathSegment() {
            return "test-library";
        }

        @Override
        public String libraryLabel() {
            return "Test Library";
        }

        @Override
        public List<StructureChapter> chapters() {
            return List.of(INTRO);
        }

        @Override
        public Set<String> allowedFileExtensions() {
            return Set.of("md");
        }

        @Override
        public Set<String> generatedNames(StructureChapter chapter, SubjectKind subject) {
            return subject == SubjectKind.SYSTEM ? Set.of("whitebox") : Set.of();
        }

        @Override
        public void writeSystem(SystemDocumentation system, GenerationContext context, Path systemDirectory) {
            // Not a test about a template's own pages.
        }
    }
}
