package ch.admin.bit.jeap.doc.domain.template;

import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * A structure template: the chapters a documentation set is organised into, the rules an upload has to follow,
 * and the pages the doc service generates into it from the architecture model.
 * <p>
 * This interface is in the domain and its implementations are not. Two places read it and must not know about
 * each other: the site generator, which asks a template for its subtree, and the web layer, which will
 * validate an upload against the same rules. Neither names a template.
 * <p>
 * It is a plugin point, not a driven port. There are as many implementations as there are templates, so the
 * rule that every port has exactly one adapter does not apply.
 */
public interface StructureTemplate {

    /** The id an upload names in its {@code template} parameter. */
    String id();

    /** The path segment below a system. */
    String systemPathSegment();

    /** What the navigation calls this template below a system. */
    String systemLabel();

    /**
     * The path segment below a component.
     * <p>
     * A template is named for what it describes. The same structure reads as <i>System Architecture</i> under
     * a system and as <i>Component Architecture</i> under a component.
     */
    String componentPathSegment();

    /** What the navigation calls this template below a component. */
    String componentLabel();

    /**
     * The chapters of this template. Nothing else writes a chapter folder name.
     * <p>
     * The order they are declared in does not matter - {@link #orderedChapters()} decides the order the
     * navigation shows them in. A template either numbers every chapter or none of them; the mixture is
     * refused while the service starts.
     */
    List<StructureChapter> chapters();

    /** The chapter a folder belongs to, or empty when this template has no such chapter. */
    default Optional<StructureChapter> chapterOfFolder(String folder) {
        return chapters().stream().filter(chapter -> chapter.folder().equals(folder)).findFirst();
    }

    /**
     * The chapters in the order the navigation shows them: <b>by number where the template numbers them, and
     * alphabetically by title where it does not.</b>
     * <p>
     * The order is decided here rather than left to the site generator. Docusaurus does sort the items of a
     * folder it has no position for, but by what it sorts them is its business and not ours - so a template
     * without numbers would have the order of its chapters decided by a version of a dependency. Here it is one
     * rule, written down, and {@link #positionOf} is what puts it into the navigation.
     */
    default List<StructureChapter> orderedChapters() {
        return chapters().stream().sorted(StructureChapter.ORDER).toList();
    }

    /**
     * Where a chapter goes among its siblings, which is the {@code position} of its {@code _category_.json}.
     * <p>
     * For a numbered chapter it is the number, so that a gap in the numbering stays a gap - a reader of arc42
     * sees that chapter 7 has not been written. For an unnumbered one it is its place in the alphabet, counted
     * from 1.
     */
    default int positionOf(StructureChapter chapter) {
        if (chapter.isNumbered()) {
            return chapter.number();
        }
        int index = orderedChapters().indexOf(chapter);
        if (index < 0) {
            throw new IllegalArgumentException(("The chapter '%s' is not one of the template %s, so it has no "
                                                + "place among its chapters.").formatted(chapter.title(), id()));
        }
        return index + 1;
    }

    /**
     * Writes the pages of one system into the system's directory. The template creates its own segment below
     * it.
     * <p>
     * A template with nothing to generate writes nothing, so no empty folder appears.
     *
     * @param system          the system to document
     * @param context         the landscape it sits in, and what a page says about where it came from
     * @param systemDirectory {@code content/<environment>/systems/<slug>}
     */
    void writeSystem(DocumentedSystem system, GenerationContext context, Path systemDirectory) throws IOException;
}
