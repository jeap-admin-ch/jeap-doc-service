package ch.admin.bit.jeap.doc.template.arc42;

import ch.admin.bit.jeap.doc.domain.template.DocumentationPaths;
import ch.admin.bit.jeap.doc.domain.template.GenerationContext;
import ch.admin.bit.jeap.doc.domain.template.SystemDocumentation;
import ch.admin.bit.jeap.doc.domain.template.StructureChapter;
import ch.admin.bit.jeap.doc.markdown.Markdown;
import ch.admin.bit.jeap.doc.markdown.Md;
import ch.admin.bit.jeap.doc.markdown.MarkdownWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static ch.admin.bit.jeap.doc.template.arc42.Arc42Chapters.INTRODUCTION;

/**
 * The one page generated for a subject the architecture model does not hold.
 * <p>
 * A team can write documentation before anything is deployed, so a system or component can be documented and
 * be in no landscape. There is nothing to generate from - no context, no components, no messages - and four
 * chapters saying so would be worse than none. So it gets one page, in chapter 1, explaining what is missing
 * and what makes it appear, and the chapters the team wrote stand beside it as they would for any subject.
 */
final class Arc42UnknownSubjectPage {

    /**
     * Where the page is served, and a name reserved in chapter 1 for every system and component - whether or
     * not the model happens to hold that subject. {@code generatedNames} is asked about a chapter and a kind
     * and knows nothing about a landscape, so a name reserved only while the model is silent would let an
     * import decide whether one and the same upload is valid.
     */
    static final String PAGE = "not-in-the-architecture-model";

    private static final String LABEL = "Not in the Architecture Model";

    private Arc42UnknownSubjectPage() {
    }

    /**
     * The tree of a component only the uploaded documentation knows: its own landing page, the page saying
     * the model does not hold it, and the chapters the team wrote.
     */
    static void writeForComponent(Arc42Template template, SystemDocumentation system,
                                  SystemDocumentation.ComponentDocumentation component,
                                  GenerationContext context, Path componentDirectory) throws IOException {
        Arc42Pages.writeCategory(componentDirectory, component.name(), 0, true);
        Path structure = componentDirectory.resolve(template.componentPathSegment());
        Arc42Pages.writeCategory(structure, template.componentLabel(), 1, false);

        List<StructureChapter> uploaded = Arc42CustomChapters.of(template,
                system.customChaptersOfComponent(component.slug()));
        Path introduction = Arc42Pages.chapterDirectory(template, structure, INTRODUCTION);
        Arc42Pages.write(introduction, PAGE + "." + DocumentationPaths.MARKDOWN_EXTENSION,
                explanationFor(context, "component", component.name()));
        // The chapter's own landing page: this one is a page in the chapter, not the chapter's index, and a
        // landing page that lists a chapter with no index fails the build on the link.
        Arc42ChapterIndexPage.writeUnlessGenerated(INTRODUCTION, context, introduction);
        Arc42CustomChapters.write(template, system.pages(), system.componentSubject(component.slug()),
                context, structure, uploaded);

        writeComponentLandingPage(template, component, context, structure,
                Arc42CustomChapters.merged(template, List.of(INTRODUCTION), uploaded));
        writeComponentIndexPage(template, component, context, componentDirectory);
    }

    /** The page under the component, linking into its structure - what a known component's page also does. */
    private static void writeComponentIndexPage(Arc42Template template,
                                                SystemDocumentation.ComponentDocumentation component,
                                                GenerationContext context, Path componentDirectory)
            throws IOException {
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(component.name(), 0, context))
                .heading(1, component.name())
                .paragraph(Md.sentence("A component of this system that the architecture model does not hold. "
                                       + "What is documented about it was written by the team that owns it."))
                .heading(2, "Documentation")
                .bulletList(List.of(Md.link("./" + template.componentPathSegment() + "/",
                        template.componentLabel())));
        Arc42Pages.write(componentDirectory, Arc42Pages.INDEX, page);
    }

    /** The landing page of the component's structure: the chapters that exist and nothing else. */
    private static void writeComponentLandingPage(Arc42Template template,
                                                  SystemDocumentation.ComponentDocumentation component,
                                                  GenerationContext context, Path structure,
                                                  List<StructureChapter> chapters) throws IOException {
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(template.componentLabel(), 0, context))
                .heading(1, template.componentLabel() + " - " + component.name())
                .paragraph(Md.sentence("What the team that owns {} has written about it, according to {}.",
                        Md.code(component.name()), Md.link("https://arc42.org/overview/", Arc42Template.ID)));
        List<List<Markdown>> rows = new ArrayList<>();
        for (StructureChapter chapter : chapters) {
            rows.add(List.of(Md.link("./" + chapter.urlSegment() + "/", chapter.label()),
                    Md.text(Arc42Chapters.componentSummaryOf(chapter))));
        }
        page.table(List.of("Chapter", "What it answers"), rows);
        Arc42Pages.write(structure, Arc42Pages.INDEX, page);
    }

    /** The one page, worded for whatever kind of subject it is about. */
    private static MarkdownWriter explanationFor(GenerationContext context, String kind, String name) {
        return new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(LABEL, 0, context))
                .heading(1, LABEL)
                .paragraph(Md.sentence("The architecture model of the {} environment does not hold the {} {}. "
                                       + "Everything on these pages was written by the team that owns it.",
                        Md.bold(context.environment()), Md.text(kind), Md.code(name)))
                .paragraph("So the pages this service generates from the architecture model are missing: "
                           + "there is no context view, nothing about the data it keeps or the interfaces it "
                           + "offers, and no list of the messages it publishes or consumes.")
                .paragraph(Md.sentence("They appear on their own once it is in the model, which happens when "
                                       + "it is deployed and the importers of the architecture repository "
                                       + "see it. Nothing has to be uploaded again."));
    }

    static void writeForSystem(Arc42Template template, SystemDocumentation system, GenerationContext context,
                               Path structureDirectory) throws IOException {
        Path directory = Arc42Pages.chapterDirectory(template, structureDirectory, INTRODUCTION);
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(LABEL, 0, context))
                .heading(1, LABEL)
                .paragraph(Md.sentence("The architecture model of the {} environment does not hold the "
                                       + "system {}. Everything on these pages was written by the team that "
                                       + "owns it.",
                        Md.bold(context.environment()), Md.code(system.name())))
                .paragraph("So the pages this service generates from the architecture model are missing: "
                           + "there is no context view, no decomposition into components, and no list of "
                           + "the messages the system publishes or consumes.")
                .paragraph(Md.sentence("They appear on their own once the system is in the model, which "
                                       + "happens when something of it is deployed and the importers of the "
                                       + "architecture repository see it. Nothing has to be uploaded again."));
        Arc42Pages.write(directory, PAGE + "." + DocumentationPaths.MARKDOWN_EXTENSION, page);
        Arc42ChapterIndexPage.writeUnlessGenerated(INTRODUCTION, context, directory);
    }
}
