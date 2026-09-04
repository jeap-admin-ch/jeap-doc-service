package ch.admin.bit.jeap.doc.template.arc42;

import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.template.GenerationContext;
import ch.admin.bit.jeap.doc.domain.template.StructureChapter;
import ch.admin.bit.jeap.doc.domain.template.StructureTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

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
     * The four chapters this template generates into. A gap in the numbering is how a reader sees that a
     * chapter has not been written.
     */
    static final List<StructureChapter> GENERATED_CHAPTERS =
            List.of(INTRODUCTION, CONTEXT_AND_SCOPE, BUILDING_BLOCK_VIEW, RUNTIME_VIEW);

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
    public List<StructureChapter> chapters() {
        return Arc42Chapters.ALL;
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
    public void writeSystem(DocumentedSystem system, GenerationContext context, Path systemDirectory)
            throws IOException {
        Arc42SystemPages.write(this, system, context, systemDirectory);
    }
}
