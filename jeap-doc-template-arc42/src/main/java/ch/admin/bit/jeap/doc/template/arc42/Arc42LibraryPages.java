package ch.admin.bit.jeap.doc.template.arc42;

import ch.admin.bit.jeap.doc.domain.DisplayTime;
import ch.admin.bit.jeap.doc.domain.custom.CustomProvenance;
import ch.admin.bit.jeap.doc.domain.custom.CustomSubject;
import ch.admin.bit.jeap.doc.domain.template.DocumentationPaths;
import ch.admin.bit.jeap.doc.domain.template.GenerationContext;
import ch.admin.bit.jeap.doc.domain.template.StructureChapter;
import ch.admin.bit.jeap.doc.domain.template.SystemDocumentation;
import ch.admin.bit.jeap.doc.markdown.Markdown;
import ch.admin.bit.jeap.doc.markdown.Md;
import ch.admin.bit.jeap.doc.markdown.MarkdownWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static ch.admin.bit.jeap.doc.template.arc42.Arc42Chapters.BUILDING_BLOCK_VIEW;
import static ch.admin.bit.jeap.doc.template.arc42.Arc42Chapters.INTRODUCTION;

/**
 * The libraries of a system, in its building block view beside its components.
 * <p>
 * <b>All twelve chapters of a library are written by hand.</b> No architecture model holds a library - it
 * publishes no artifact and is deployed nowhere, so no importer ever sees it - which makes a library the
 * purest case of what this service is for. What is generated is the frame: the group, the landing pages, and
 * the one page saying what the doc service knows about the library from the upload itself.
 */
final class Arc42LibraryPages {

    static final String LIBRARIES_LABEL = "Libraries";

    /**
     * Inside chapter 5, after the components: the two groups of building blocks stand together, and the
     * events and the commands follow at four and five. Every group of the chapter has a position of its own -
     * Docusaurus orders two equal ones by their folder names.
     */
    static final int LIBRARIES_POSITION = 3;

    private Arc42LibraryPages() {
    }

    /**
     * Writes the libraries of one system, or nothing where it has none.
     *
     * @param buildingBlock the chapter directory the group is written into
     */
    static void write(Arc42Template template, SystemDocumentation system, GenerationContext context,
                      Path buildingBlock) throws IOException {
        List<CustomSubject> libraries = system.libraries();
        if (libraries.isEmpty()) {
            return;
        }
        Path group = buildingBlock.resolve(DocumentationPaths.LIBRARIES_SEGMENT);
        Arc42Pages.writeCategory(group, LIBRARIES_LABEL, LIBRARIES_POSITION);
        writeIndex(system, libraries, context, group);
        for (CustomSubject library : libraries) {
            writeLibrary(template, system, library, context, group.resolve(library.name()));
        }
    }

    /** The rows a system's structure landing page shows for its libraries. */
    static List<List<Markdown>> rowsFor(SystemDocumentation system) {
        List<List<Markdown>> rows = new ArrayList<>();
        for (CustomSubject library : system.libraries()) {
            rows.add(List.of(
                    Md.link(DocumentationPaths.library(system.slug(), Arc42Template.SYSTEM_SEGMENT,
                            BUILDING_BLOCK_VIEW, library.name()), library.name()),
                    Md.text(versionOf(system, library).orElse(""))));
        }
        return rows;
    }

    /** The landing page of the group: every library of the system, each linked to its own tree. */
    private static void writeIndex(SystemDocumentation system, List<CustomSubject> libraries,
                                   GenerationContext context, Path group) throws IOException {
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(Arc42Pages.generatedWithoutTheModel(LIBRARIES_LABEL, 0, context))
                .heading(1, LIBRARIES_LABEL)
                .paragraph(Md.sentence("The libraries of {}. A library publishes no artifact this service "
                                       + "can see and is deployed nowhere, so everything documented about "
                                       + "one was written by the team that owns it.", Md.code(system.name())));
        List<List<Markdown>> rows = new ArrayList<>();
        for (CustomSubject library : libraries) {
            rows.add(List.of(
                    Md.link("./" + library.name() + "/", library.name()),
                    Md.text(versionOf(system, library).orElse(""))));
        }
        page.table(List.of("Library", "Version"), rows);
        Arc42Pages.write(group, Arc42Pages.INDEX, page);
    }

    /** One library: its own page, the structure below it, and the chapters the team wrote. */
    private static void writeLibrary(Arc42Template template, SystemDocumentation system,
                                     CustomSubject library, GenerationContext context, Path directory)
            throws IOException {
        Arc42Pages.writeCategory(directory, library.name(), 0);
        Path structure = directory.resolve(template.libraryPathSegment());
        Arc42Pages.writeCategory(structure, template.libraryLabel(), 1);

        List<StructureChapter> uploaded = Arc42CustomChapters.of(template,
                system.custom().chapterFoldersOf(library));
        writeOverview(template, system, library, context, structure);
        Arc42CustomChapters.write(template, system.pages(), library, context, structure, uploaded,
                false);

        writeStructureLandingPage(template, library, context, structure,
                Arc42CustomChapters.merged(template, List.of(INTRODUCTION), uploaded));
        writeLandingPage(template, library, context, directory);
    }

    /** What the doc service knows about a library, which is what its upload said. */
    private static void writeOverview(Arc42Template template, SystemDocumentation system,
                                      CustomSubject library, GenerationContext context, Path structure)
            throws IOException {
        Path introduction = Arc42Pages.chapterDirectory(template, structure, INTRODUCTION);
        Optional<CustomProvenance> uploaded = system.custom().provenanceOf(library);
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(Arc42Pages.generatedWithoutTheModel("Library Overview", 0, context))
                .heading(1, "Library Overview")
                .paragraph(Md.sentence("What the doc service knows about {}, from the upload that published "
                                       + "this documentation. Everything else on these pages was written by "
                                       + "the team that owns it.", Md.code(library.name())));
        List<List<Markdown>> rows = new ArrayList<>();
        rows.add(List.of(Md.text("Library"), Md.code(library.name())));
        rows.add(List.of(Md.text("System"),
                Md.link(DocumentationPaths.system(system.slug()), system.name())));
        uploaded.ifPresent(provenance -> {
            rows.add(List.of(Md.text("Version"), Md.textOr(provenance.version(), "not stated")));
            rows.add(List.of(Md.text("Repository"), Md.code(provenance.sourceRepository())));
            rows.add(List.of(Md.text("Branch or tag"), Md.code(provenance.sourceRef())));
            rows.add(List.of(Md.text("Built from"), Md.code(provenance.sourceRevision())));
            rows.add(List.of(Md.text("Uploaded"), Md.text(DisplayTime.of(provenance.uploadedAt()))));
        });
        page.table(List.of("", ""), rows);
        Arc42Pages.write(introduction, Arc42Template.LIBRARY_OVERVIEW_PAGE + "."
                                       + DocumentationPaths.MARKDOWN_EXTENSION, page);
        // The chapter's own landing page. This one is a page in chapter 1, not its index, and the structure
        // landing page links to the chapter.
        Arc42ChapterIndexPage.writeUnlessGenerated(INTRODUCTION, context, introduction, false);
    }

    /** The landing page of the library's structure: the chapters that exist. */
    private static void writeStructureLandingPage(Arc42Template template, CustomSubject library,
                                                  GenerationContext context, Path structure,
                                                  List<StructureChapter> chapters) throws IOException {
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(Arc42Pages.generatedWithoutTheModel(template.libraryLabel(), 0, context))
                .heading(1, template.libraryLabel() + " - " + library.name())
                .paragraph(Md.sentence("What the team that owns {} has written about it, according to {}.",
                        Md.code(library.name()), Md.link("https://arc42.org/overview/", Arc42Template.ID)))
                .paragraph("Chapters with nothing in them do not appear. A gap in the numbering means the "
                           + "chapter has not been written, not that it is empty.");
        List<List<Markdown>> rows = new ArrayList<>();
        for (StructureChapter chapter : chapters) {
            rows.add(List.of(Md.link("./" + chapter.urlSegment() + "/", chapter.label()),
                    Md.text(Arc42Chapters.summaryOf(chapter))));
        }
        page.table(List.of("Chapter", "What it answers"), rows);
        Arc42Pages.write(structure, Arc42Pages.INDEX, page);
    }

    /** The library's own page, which the structure hangs below - the shape a component's page has. */
    private static void writeLandingPage(Arc42Template template, CustomSubject library,
                                         GenerationContext context, Path directory)
            throws IOException {
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(Arc42Pages.generatedWithoutTheModel(library.name(), 0, context))
                .heading(1, library.name())
                .paragraph(Md.sentence("A library of this system: it publishes no artifact this service can "
                                       + "see and is deployed nowhere, so its documentation is written by "
                                       + "hand."))
                .heading(2, "Documentation")
                .bulletList(List.of(Md.link("./" + template.libraryPathSegment() + "/",
                        template.libraryLabel())));
        Arc42Pages.write(directory, Arc42Pages.INDEX, page);
    }

    private static Optional<String> versionOf(SystemDocumentation system, CustomSubject library) {
        return system.custom().provenanceOf(library).map(CustomProvenance::version);
    }
}
