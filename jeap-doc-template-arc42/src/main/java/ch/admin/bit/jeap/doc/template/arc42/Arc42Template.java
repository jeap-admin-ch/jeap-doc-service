package ch.admin.bit.jeap.doc.template.arc42;

import ch.admin.bit.jeap.doc.domain.template.SystemDocumentation;
import ch.admin.bit.jeap.doc.domain.template.GenerationContext;
import ch.admin.bit.jeap.doc.domain.template.StructureChapter;
import ch.admin.bit.jeap.doc.domain.template.DocumentationPaths;
import ch.admin.bit.jeap.doc.domain.template.StructureTemplate;
import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static ch.admin.bit.jeap.doc.template.arc42.Arc42Chapters.BUILDING_BLOCK_VIEW;
import static ch.admin.bit.jeap.doc.template.arc42.Arc42Chapters.CONTEXT_AND_SCOPE;
import static ch.admin.bit.jeap.doc.template.arc42.Arc42Chapters.INTRODUCTION;
import static ch.admin.bit.jeap.doc.template.arc42.Arc42Chapters.RUNTIME_VIEW;

/**
 * arc42, as the doc service generates it from the architecture model.
 * <p>
 * <b>What arc42 is, and nothing about how a page is written.</b> The twelve chapters, the segments a page is
 * served under and which chapters this template generates into are what an upload and the site generator are
 * checked against, and they belong here; turning a system into Markdown is {@link Arc42SystemPages}.
 * <p>
 * Four of the twelve chapters are generated. The other eight are not created at all, because an empty
 * chapter claims there is content when there is none. What a team writes by hand arrives beside those pages.
 * <p>
 * <b>arc42</b> (<a href="https://arc42.org">arc42.org</a>) is by Gernot Starke and Peter Hruschka, licensed
 * under <a href="https://creativecommons.org/licenses/by-sa/4.0/">CC BY-SA 4.0</a>. The credit is in
 * {@code NOTICE}, in this module's {@code README.md}, and once in the generated site at the foot of chapter 1.
 */
@Component
public class Arc42Template implements StructureTemplate {

    /** The path segment below a system. */
    public static final String SYSTEM_SEGMENT = "system-architecture";

    /** The same structure below a component, named for the thing it documents there. */
    public static final String COMPONENT_SEGMENT = "component-architecture";

    static final String ID = "arc42";

    /** Where the whitebox view is served, inside the building block view. */
    static final String WHITEBOX_PAGE = "whitebox-view";

    /** Where the system context view is served, inside context and scope. */
    static final String CONTEXT_VIEW_PAGE = "system-context-view";

    /** The page the imported reactions will fill, once they are imported. */
    static final String SYSTEM_REACTIONS_PAGE = "system-reactions";

    /**
     * Where a component's context view is served, inside its own context and scope.
     * <p>
     * Without the {@code component-} prefix the system's page carries: the path already holds the component
     * and {@code component-architecture}, so the prefix would say the word a third time. The heading and the
     * navigation label are <i>Component Context View</i> all the same.
     */
    static final String COMPONENT_CONTEXT_VIEW_PAGE = "context-view";

    /** Where a component's entity relationship diagram is served, inside its building block view. */
    static final String DATABASE_SCHEMA_PAGE = "database-schema";

    /** And the overview of its REST API, beside it. */
    static final String REST_API_PAGE = "rest-api";

    /** And the messages it produces and consumes. */
    static final String MESSAGES_PAGE = "messages";

    /** The component's counterpart of {@link #SYSTEM_REACTIONS_PAGE}, empty for the same reason. */
    static final String COMPONENT_REACTIONS_PAGE = "component-reactions";

    /** The path segment below a library, named for what it describes there. */
    public static final String LIBRARY_SEGMENT = "library-architecture";

    /** Where what the doc service knows about a library is served, inside its introduction. */
    static final String LIBRARY_OVERVIEW_PAGE = "library-overview";

    /**
     * The four chapters this template generates into. A gap in the numbering is how a reader sees that a
     * chapter has not been written.
     */
    static final List<StructureChapter> GENERATED_CHAPTERS =
            List.of(INTRODUCTION, CONTEXT_AND_SCOPE, BUILDING_BLOCK_VIEW, RUNTIME_VIEW);

    /**
     * Markdown, and the pictures that have no source.
     * <p>
     * A page that cannot show a screenshot is a page a team keeps in Confluence, which is what this enabler
     * is against. {@code mdx} is not here and must not be: MDX is a programming language, and documentation
     * the doc service did not write itself is not trusted with one - decision 10.
     * <p>
     * <b>A diagram is still better as a fenced block</b>: PlantUML, Mermaid and GraphViz are rendered from
     * their source in the reader's browser, so they stay diffable, searchable and legible in both themes. An
     * uploaded picture of a diagram is none of those. This list is for the screenshots and the scans.
     */
    static final Set<String> ALLOWED_FILE_EXTENSIONS =
            Set.of("md", "png", "jpg", "jpeg", "gif", "webp", "avif", "svg");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String systemPathSegment() {
        return SYSTEM_SEGMENT;
    }

    @Override
    public String systemLabel() {
        return "System Architecture";
    }

    @Override
    public String componentPathSegment() {
        return COMPONENT_SEGMENT;
    }

    @Override
    public String componentLabel() {
        return "Component Architecture";
    }

    @Override
    public String libraryPathSegment() {
        return LIBRARY_SEGMENT;
    }

    @Override
    public String libraryLabel() {
        return "Library Architecture";
    }

    @Override
    public List<StructureChapter> chapters() {
        return Arc42Chapters.ALL;
    }

    @Override
    public Set<String> allowedFileExtensions() {
        return ALLOWED_FILE_EXTENSIONS;
    }

    /**
     * What this template writes into a chapter for a system, a component or a library.
     * <p>
     * <b>Every name is the constant the writer uses</b>, so this is a declaration rather than a second list:
     * a page renamed in {@code Arc42SystemPages} or {@code Arc42ComponentPages} is renamed here with it. That
     * the two agree is not left to discipline - {@code Arc42SystemTreeTest} and
     * {@code Arc42ComponentTreeTest} walk the generated tree and fail if a file appears that nothing here
     * reserves.
     * <p>
     * <b>The introduction reserves a name for every kind of subject</b>, and it does so whether or not the
     * architecture model happens to hold that subject. This is a declaration read by the upload validation,
     * which knows nothing about a landscape - a name reserved only while the model is silent would let an
     * import decide whether one and the same upload is valid.
     */
    @Override
    public Set<String> generatedNames(StructureChapter chapter, SubjectKind subject) {
        if (chapter == null || subject == null) {
            return Set.of();
        }
        return switch (subject) {
            case SYSTEM -> generatedForSystem(chapter);
            case COMPONENT -> generatedForComponent(chapter);
            case LIBRARY -> generatedForLibrary(chapter);
        };
    }

    private static Set<String> generatedForSystem(StructureChapter chapter) {
        if (INTRODUCTION.equals(chapter)) {
            return Set.of(Arc42UnknownSubjectPage.PAGE);
        }
        if (CONTEXT_AND_SCOPE.equals(chapter)) {
            return Set.of(CONTEXT_VIEW_PAGE);
        }
        if (BUILDING_BLOCK_VIEW.equals(chapter)) {
            // The pages, and the folders beside them: a group and a page of the same name are one URL.
            return Set.of(WHITEBOX_PAGE, DocumentationPaths.COMPONENTS_SEGMENT,
                    DocumentationPaths.LIBRARIES_SEGMENT, Arc42MessagePages.EVENTS,
                    Arc42MessagePages.COMMANDS);
        }
        if (RUNTIME_VIEW.equals(chapter)) {
            return Set.of(SYSTEM_REACTIONS_PAGE);
        }
        return Set.of();
    }

    private static Set<String> generatedForComponent(StructureChapter chapter) {
        if (INTRODUCTION.equals(chapter)) {
            return Set.of(Arc42UnknownSubjectPage.PAGE);
        }
        if (CONTEXT_AND_SCOPE.equals(chapter)) {
            return Set.of(COMPONENT_CONTEXT_VIEW_PAGE);
        }
        if (BUILDING_BLOCK_VIEW.equals(chapter)) {
            return Set.of(DATABASE_SCHEMA_PAGE, REST_API_PAGE, MESSAGES_PAGE);
        }
        if (RUNTIME_VIEW.equals(chapter)) {
            return Set.of(COMPONENT_REACTIONS_PAGE);
        }
        return Set.of();
    }

    /**
     * What is generated for a library: the page written from what the upload said about it.
     * <p>
     * Reserved so that an upload carrying that name is refused at the API rather than colliding into a
     * duplicate route twenty minutes into a build.
     */
    private static Set<String> generatedForLibrary(StructureChapter chapter) {
        return INTRODUCTION.equals(chapter) ? Set.of(LIBRARY_OVERVIEW_PAGE) : Set.of();
    }

    /**
     * Writes the arc42 subtree of one system, by handing it to {@link Arc42SystemPages}.
     * <p>
     * <b>The structure is this class, the Markdown is that one.</b> What arc42 is - the twelve chapters, the
     * segments a page is served under, which chapters this template generates into - is what the rest of this
     * class says, and it is what the site generator and an upload are validated against. Turning a system into
     * pages is a different job, and it is the larger of the two by an order of magnitude.
     */
    @Override
    public void writeSystem(SystemDocumentation system, GenerationContext context, Path systemDirectory)
            throws IOException {
        Arc42SystemPages.write(this, system, context, systemDirectory);
    }
}
