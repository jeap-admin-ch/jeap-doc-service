package ch.admin.bit.jeap.doc.domain.template;

import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
     * The path segment below a library.
     * <p>
     * A library is a building block of the system that owns it, and the same structure reads as <i>Library
     * Architecture</i> there. No architecture model holds a library, so every chapter of one is written by
     * the team - which is what makes it the purest case of what this service is for.
     */
    String libraryPathSegment();

    /** What the navigation calls this template below a library. */
    String libraryLabel();

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
     * <p>
     * <b>One entry point, and the subject says which case it is.</b> What is handed in carries the
     * architecture model's system where there is one and what has been uploaded either way, so a system that
     * is documented and deployed nowhere is written by this same call - and a template branches once rather
     * than checking for a missing model on every page. The uploaded pages are written by the writer on it,
     * into the chapter folders this template names: only a template may name a chapter, and the front matter
     * and the ordering of an uploaded page are one implementation for every methodology.
     *
     * @param system          the system to document, as both models know it
     * @param context         the landscape it sits in, and what a page says about where it came from
     * @param systemDirectory {@code content/<environment>/systems/<slug>}
     */
    void writeSystem(SystemDocumentation system, GenerationContext context, Path systemDirectory)
            throws IOException;

    /**
     * The file extensions an upload to this template may carry, lower case and without the dot.
     * <p>
     * <b>An answer, not a check.</b> A template never sees a path - what walks a tree, decides and words a
     * finding is the validation in the domain, so that the finding codes are one set whatever methodology is
     * named. See {@code docs/upload-validation.md}.
     * <p>
     * No default: an empty set would silently forbid everything and a generous one would silently allow it,
     * and either is a template that got validation wrong by saying nothing.
     */
    Set<String> allowedFileExtensions();

    /**
     * The page and group names this template generates into a chapter, for that kind of subject, in the order
     * it writes them - without the extension, because what collides is a document and not a file.
     * <p>
     * An upload carrying one of them would produce two documents at one URL, and Docusaurus is configured
     * with {@code onDuplicateRoutes: 'throw'} - so the build of that subject's part fails, twenty minutes
     * later, naming a route rather than an upload.
     * <p>
     * Defaults to nothing: a template that generates no page occupies no name beyond the two the domain
     * reserves everywhere, {@code index} and {@code _category_.json}.
     * <p>
     * <b>The order is the one declaration.</b> It is what {@link #siblingPosition} numbers a generated page
     * by, and what an uploaded item may place itself before or after - the same list answers both, so the two
     * can never disagree about which sibling comes first.
     */
    default List<String> generatedSiblings(StructureChapter chapter, SubjectKind subject) {
        return List.of();
    }

    /**
     * The page and group names this template generates into a chapter, for that kind of subject - without the
     * extension, because what collides is a document and not a file.
     * <p>
     * Derived from {@link #generatedSiblings}, which is the one declaration: this is what the upload
     * validation and {@link ReservedNames} ask, and neither needs the order.
     */
    default Set<String> generatedNames(StructureChapter chapter, SubjectKind subject) {
        return Set.copyOf(generatedSiblings(chapter, subject));
    }

    /**
     * The gap between two generated siblings' positions.
     * <p>
     * Large enough that the uploaded items placed between two of them, or after the last one, can never reach
     * the next: a chapter holds at most {@code jeap.doc.upload.validation.max-paths}, ceilinged at 100 000,
     * items in total.
     */
    int SIBLING_STRIDE = 1_000_000;

    /**
     * The sidebar position a generated sibling of a chapter takes.
     * <p>
     * <b>Uploaded items are placed around these numbers.</b> An item with no placement of its own still has
     * to stand after every generated page, and one placed next to a named sibling has to land next to the
     * real position that sibling's own page carries - so this is the one formula both the writer of the
     * generated page and {@link ch.admin.bit.jeap.doc.domain.custom.ChapterOrder} have to agree on, and it is
     * written once, here.
     *
     * @param name one of {@link #generatedSiblings}, for the same chapter and subject
     * @throws IllegalArgumentException if it is not
     */
    default int siblingPosition(StructureChapter chapter, SubjectKind subject, String name) {
        List<String> siblings = generatedSiblings(chapter, subject);
        int index = siblings.indexOf(name);
        if (index < 0) {
            throw new IllegalArgumentException(("'%s' is not a sibling this template generates into %s for "
                                                + "%s.").formatted(name, chapter, subject));
        }
        return (index + 1) * SIBLING_STRIDE;
    }
}
