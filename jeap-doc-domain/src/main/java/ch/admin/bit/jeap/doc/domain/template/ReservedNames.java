package ch.admin.bit.jeap.doc.domain.template;

import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;

import java.util.Locale;
import java.util.Set;

/**
 * The document names a chapter's own generated pages already occupy.
 * <p>
 * <b>Two readers, one rule.</b> The upload validation refuses a set carrying such a name, and the site
 * generator drops one that got in before the rule existed - and the two must give the same answer, or the
 * backstop lets through exactly what it is there to catch and the build fails on a duplicate route twenty
 * minutes in, naming a route rather than an upload.
 * <p>
 * The validation asks the three questions one at a time, because each of them is a different sentence to the
 * team that uploaded the page; the generator asks {@link #isTaken} and drops the page with a line in the log.
 */
public final class ReservedNames {

    /**
     * The names the site generator reads as a chapter's landing page, beside the chapter folder's own name.
     * <p>
     * Every chapter has a generated landing page, so a document of one of these names is a second document at
     * that page's URL.
     */
    public static final Set<String> LANDING_PAGE_NAMES = Set.of(DocumentationPaths.INDEX_SEGMENT, "readme");

    private ReservedNames() {
    }

    /**
     * Whether the template writes a page of this file name into this chapter itself.
     *
     * @param fileName the file's own name, with its extension
     */
    public static boolean isTaken(StructureTemplate template, StructureChapter chapter, SubjectKind kind,
                                  String fileName) {
        String name = withoutItsExtension(fileName);
        String document = NumberPrefixes.stripped(name);
        return isLandingPageName(name, chapter)
               || isTheGeneratedIndex(document)
               || isGeneratedByTheTemplate(template, chapter, kind, document);
    }

    /**
     * Whether the site generator would read this document as the chapter's landing page: {@code index},
     * {@code readme} or the chapter folder's own name.
     * <p>
     * Folded, because that is how the generator decides it - so {@code README.md} and {@code INDEX.MD} resolve
     * to the same URL as the generated landing page and would fail the build as a duplicate route.
     * <p>
     * <b>Asked of the name as written, number prefix and all.</b> The generator's own rule compares the file
     * name and the folder name as they are - so {@code 1-intro.md} in {@code 1-intro/} is that chapter's
     * landing page, while {@code intro.md} in the same folder is an ordinary page at a URL of its own. It is
     * the one rule here that does not read a document's stripped name; a page whose stripped name collides is
     * caught by the two rules after it instead.
     *
     * @param name a file name with its extension already taken off
     */
    public static boolean isLandingPageName(String name, StructureChapter chapter) {
        String folded = name.toLowerCase(Locale.ROOT);
        return LANDING_PAGE_NAMES.contains(folded)
               || folded.equals(chapter.folder().toLowerCase(Locale.ROOT));
    }

    /**
     * Whether this document is identified as the landing page although it is not named like one.
     * <p>
     * {@code 01-index.md} is not read as the landing page - that is decided on the name as written - but it is
     * identified as {@code index} all the same, which is the document the generated landing page of the
     * chapter already is. Two documents of one id fail the build just as two at one URL do.
     *
     * @param document a file name with its extension and its number prefix already taken off
     */
    public static boolean isTheGeneratedIndex(String document) {
        return DocumentationPaths.INDEX_SEGMENT.equals(document);
    }

    /**
     * Whether the template generates a page of this document name into this chapter.
     *
     * @param document a file name with its extension and its number prefix already taken off
     */
    public static boolean isGeneratedByTheTemplate(StructureTemplate template, StructureChapter chapter,
                                                   SubjectKind kind, String document) {
        return template.generatedNames(chapter, kind).contains(document);
    }

    /**
     * The document name of a file: what is left when the extension comes off.
     * <p>
     * A leading dot is not an extension - {@code .gitignore} is a name - which is why the dot has to be past
     * the first character.
     */
    public static String withoutItsExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }
}
