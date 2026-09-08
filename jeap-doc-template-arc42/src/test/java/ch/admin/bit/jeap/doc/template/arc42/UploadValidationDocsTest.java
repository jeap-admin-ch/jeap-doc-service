package ch.admin.bit.jeap.doc.template.arc42;

import ch.admin.bit.jeap.doc.domain.template.StructureChapter;
import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The documentation of the validation rules, checked against what arc42 actually declares.
 * <p>
 * <b>A page kept right only by review is wrong within two stories.</b> Four pages of this repository once
 * described an upload validation that did not exist, which is the failure this test is against: a chapter
 * added to {@link Arc42Chapters}, an extension added to the allowlist or a page added to a chapter has to
 * appear on the page a pipeline author reads, and here that is a test rather than a habit.
 * <p>
 * It asserts that the page <i>mentions</i> each declaration, not how it words them - the prose is the
 * author's, the lists are the template's.
 */
class UploadValidationDocsTest {

    private static String page;

    private final Arc42Template template = new Arc42Template();

    @BeforeAll
    static void readThePage() throws IOException {
        Path found = thePage();
        assertThat(found).describedAs("the page documenting the validation rules").isNotNull();
        page = Files.readString(found, StandardCharsets.UTF_8);
    }

    /**
     * The page, found by walking up from wherever the test was started: surefire runs in the module, an IDE
     * may run in the repository.
     */
    private static Path thePage() {
        for (Path directory = Path.of("").toAbsolutePath(); directory != null;
             directory = directory.getParent()) {
            Path candidate = directory.resolve("docs").resolve("upload-validation.md");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    @Test
    void thePageNamesEveryChapterFolder() {
        for (StructureChapter chapter : Arc42Chapters.ALL) {
            assertThat(page)
                    .describedAs("'%s' is a chapter of arc42 and has to be on the page an upload is written "
                                 + "against", chapter.folder())
                    .contains(chapter.folder());
        }
    }

    /**
     * As a token of its own, because {@code md} is a substring of {@code mdx} - and the page says that
     * {@code mdx} is refused, which would satisfy a plain {@code contains} on its own.
     */
    @Test
    void thePageNamesEveryAllowedExtension() {
        for (String extension : template.allowedFileExtensions()) {
            assertThat(page)
                    .describedAs("'%s' is an extension arc42 accepts and has to be on the page", extension)
                    .containsPattern("(?<![\\w.-])" + extension + "(?![\\w-])");
        }
    }

    /**
     * The reserved names, per chapter and per kind of subject. These are the ones an upload is refused for, so
     * a name the generator takes without the page saying so is a rejection nobody can explain.
     */
    @Test
    void thePageNamesEveryGeneratedPageAndGroup() {
        for (StructureChapter chapter : Arc42Chapters.ALL) {
            for (SubjectKind subject : SubjectKind.values()) {
                for (String generated : template.generatedNames(chapter, subject)) {
                    assertThat(page)
                            .describedAs("arc42 generates '%s' into %s for a %s, so the page has to reserve it",
                                    generated, chapter.folder(), subject)
                            .contains(generated);
                }
            }
        }
    }

    /**
     * <b>And that it says a library gets nothing.</b> It is the one entry of the table that is an absence, so
     * it is the one a reader would otherwise take for an oversight.
     */
    @Test
    void thePageSaysThatNothingIsGeneratedForALibrary() {
        assertThat(page).contains("Nothing is generated for a library");
        for (StructureChapter chapter : Arc42Chapters.ALL) {
            assertThat(template.generatedNames(chapter, SubjectKind.LIBRARY))
                    .describedAs("and that is still true of %s", chapter.folder())
                    .isEmpty();
        }
    }

    /**
     * The extension that must never be accepted, and the page has to be the place that says so - in a
     * sentence that refuses it rather than in a token that could be anywhere.
     */
    @Test
    void thePageSaysThatMdxIsRefused() {
        assertThat(template.allowedFileExtensions()).doesNotContain("mdx");
        assertThat(page).describedAs("a sentence naming mdx and saying it is not taken")
                .containsPattern("(?m)^.*`mdx`.*\\bnot\\b.*$");
    }
}
