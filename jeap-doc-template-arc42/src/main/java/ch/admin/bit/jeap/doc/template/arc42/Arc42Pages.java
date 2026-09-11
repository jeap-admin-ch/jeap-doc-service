package ch.admin.bit.jeap.doc.template.arc42;

import ch.admin.bit.jeap.doc.domain.template.GenerationContext;
import ch.admin.bit.jeap.doc.domain.template.StructureChapter;
import ch.admin.bit.jeap.doc.domain.template.StructureTemplate;
import ch.admin.bit.jeap.doc.markdown.CategoryFile;
import ch.admin.bit.jeap.doc.markdown.FrontMatter;
import ch.admin.bit.jeap.doc.markdown.Markdown;
import ch.admin.bit.jeap.doc.markdown.MarkdownWriter;
import ch.admin.bit.jeap.doc.markdown.Md;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static ch.admin.bit.jeap.doc.markdown.FrontMatter.frontMatter;

/**
 * What every generated arc42 page has in common: its front matter, and how it is written.
 * <p>
 * <b>The provenance is not written into the page.</b> It is in the front matter every page carries, and the
 * site template renders it under the page - one renderer for a generated page and for one a team uploaded,
 * because an uploaded page's body is copied byte for byte and nothing may be appended to it.
 */
final class Arc42Pages {

    /** The file a folder's landing page is written to, which Docusaurus fixes. */
    static final String INDEX = "index.md";

    private Arc42Pages() {
    }

    /**
     * The front matter of a generated page.
     * <p>
     * {@code doc_status} is what makes <i>generated or custom, never both</i> checkable. Uploaded pages will
     * carry {@code custom}, so nothing has to guess which of the two a page is.
     */
    static FrontMatter generated(String title, int position, GenerationContext context) {
        return frontMatter()
                .put("title", title)
                .put("sidebar_label", title)
                .put("sidebar_position", position)
                .put("doc_status", "generated")
                .put("doc_source", "archrepo")
                .put("doc_source_url", context.archRepoUrl())
                .put("doc_environment", context.environment())
                .put("doc_model_imported_at", context.hasModelImportedAt()
                        ? context.modelImportedAt().toString() : null)
                .put("doc_generated_at", context.generatedAt().toString());
    }

    /**
     * The front matter of a page this service generated about something <b>no architecture model holds</b>:
     * a library, or a system or component that is documented and deployed nowhere.
     * <p>
     * <b>It names no architecture repository, because none was read for it.</b> The page is generated all the
     * same - it is the service's own writing, from what the upload said - and the site template renders the
     * provenance from {@code doc_source}, so a page saying {@code archrepo} tells its reader it came from a
     * model that has never heard of the thing it describes. The page most obviously wrong about that was the
     * one explaining that the model does not hold the subject.
     */
    static FrontMatter generatedWithoutTheModel(String title, int position, GenerationContext context) {
        return frontMatter()
                .put("title", title)
                .put("sidebar_label", title)
                .put("sidebar_position", position)
                .put("doc_status", "generated")
                .put("doc_source", "doc-service")
                .put("doc_environment", context.environment())
                .put("doc_generated_at", context.generatedAt().toString());
    }

    /**
     * The note under a diagram that left something out, choosing between the singular and the plural.
     * <p>
     * <b>Every one of these notes has to choose.</b> A bound of forty or a hundred is crossed one thing at a
     * time, so the first page that ever shows one of them is about exactly one - and "1 of the 2 tables are
     * left out" is what a reader sees where nothing chose. These notes say different things, so what is
     * shared here is the rule rather than the sentence.
     */
    static Markdown leftOut(int truncated, String whenOne, String whenSeveral) {
        return Md.text(truncated == 1 ? whenOne : whenSeveral);
    }

    /** Writes a page, creating the folders it needs. */
    static void write(Path directory, String fileName, MarkdownWriter page) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(fileName), page.text(), StandardCharsets.UTF_8);
    }

    /**
     * Writes the {@code _category_.json} that names a folder, places it in the navigation and says whether it
     * starts open.
     * <p>
     * <b>Open down to the pages of a chapter, and no further.</b> A reader lands on a system and has to be
     * able to see what is documented about it without clicking twelve times; below that, a system of thirty
     * components expanded to every page of each of them is a sidebar nobody can use. So the chapters and the
     * building block view's own subtree are open, and a component's arc42 tree inside it is not.
     */
    static void writeCategory(Path directory, String label, int position, boolean expanded)
            throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(CategoryFile.NAME),
                expanded ? CategoryFile.expanded(label, position) : CategoryFile.of(label, position),
                StandardCharsets.UTF_8);
    }

    /**
     * The folder of a chapter, with the {@code _category_.json} that gives it its label and its place in the
     * sidebar.
     * <p>
     * The place comes from the template rather than from the chapter, because it is the template that knows how
     * its chapters are ordered - by their numbers here, by their titles in a methodology that does not number
     * them. See {@link StructureTemplate#positionOf}.
     */
    static Path chapterDirectory(StructureTemplate template, Path structureDirectory, StructureChapter chapter)
            throws IOException {
        Path directory = structureDirectory.resolve(chapter.folder());
        writeCategory(directory, chapter.label(), template.positionOf(chapter), true);
        return directory;
    }
}
