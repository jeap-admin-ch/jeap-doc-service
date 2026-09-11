package ch.admin.bit.jeap.doc.template.arc42;

import ch.admin.bit.jeap.doc.domain.template.GenerationContext;
import ch.admin.bit.jeap.doc.domain.template.StructureChapter;
import ch.admin.bit.jeap.doc.domain.template.StructureTemplate;
import ch.admin.bit.jeap.doc.markdown.Md;
import ch.admin.bit.jeap.doc.markdown.MarkdownWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The landing page of a chapter that has pages but no index page of its own.
 * <p>
 * <b>Every chapter that exists needs one.</b> A reader who opens a chapter has to land on something, and a
 * landing page that lists a chapter with no index links to a page nothing wrote - which fails the whole site
 * build, since the site is generated with {@code onBrokenLinks: 'throw'}.
 * <p>
 * Two kinds of chapter need it: one a team filled and the service generates nothing into, and one whose only
 * generated page is not its index - chapter 1 of a subject the architecture model does not hold, or of a
 * library.
 */
final class Arc42ChapterIndexPage {

    private Arc42ChapterIndexPage() {
    }

    /**
     * Writes the chapter's index page, unless the chapter already has one from the generated pages.
     * <p>
     * A chapter this template generates into wrote its own, and that one says more.
     */
    static void writeUnlessGenerated(StructureChapter chapter, GenerationContext context,
                                     Path chapterDirectory) throws IOException {
        if (Files.exists(chapterDirectory.resolve(Arc42Pages.INDEX))) {
            return;
        }
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(chapter.label(), 0, context))
                .heading(1, chapter.label())
                .paragraph(Md.text(Arc42Chapters.summaryOf(chapter)))
                .paragraph("The pages of this chapter are in the navigation beside this one.");
        Arc42Pages.write(chapterDirectory, Arc42Pages.INDEX, page);
    }
}
