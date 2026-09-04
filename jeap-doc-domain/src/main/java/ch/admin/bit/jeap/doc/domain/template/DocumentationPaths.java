package ch.admin.bit.jeap.doc.domain.template;

/**
 * Where a page of the documentation is served, as a link written on another page.
 * <p>
 * Every path here is root-relative and carries neither the environment nor the site. A remark plugin adds the
 * environment prefix and Docusaurus adds the base URL, so nothing that writes a page has to know either.
 * Adding one here would produce {@code /dev/dev/…}.
 * <p>
 * A chapter appears by its URL segment, without the number prefix: the site generator strips it, so a link
 * carrying it would point at a page that does not exist.
 */
public final class DocumentationPaths {

    /** Where every system's documentation hangs, below the root page of the environment. */
    public static final String SYSTEMS_SEGMENT = "systems";

    /** The folder a system's components are grouped under, inside the building block view. */
    public static final String COMPONENTS_SEGMENT = "components";

    /**
     * The one segment no derived slug may be: {@code index.md} is the listing of every directory the generator
     * writes, so a page named after it would be written over that listing. The importer refuses a name that
     * yields it, and a template writes its listings under it - the two have to agree, which is why it is here.
     */
    public static final String INDEX_SEGMENT = "index";

    private DocumentationPaths() {
    }

    public static String systems() {
        return "/" + SYSTEMS_SEGMENT + "/";
    }

    public static String system(String systemSlug) {
        return systems() + systemSlug + "/";
    }

    /** A structure template below a system, {@code /systems/orders/system-architecture/}. */
    public static String structure(String systemSlug, String structureSegment) {
        return system(systemSlug) + structureSegment + "/";
    }

    /** A chapter of a structure, {@code …/system-architecture/building-block-view/}. */
    public static String chapter(String systemSlug, String structureSegment, StructureChapter chapter) {
        return structure(systemSlug, structureSegment) + chapter.urlSegment() + "/";
    }

    /** A page inside a chapter, {@code …/building-block-view/whitebox-view/}. */
    public static String page(String systemSlug, String structureSegment, StructureChapter chapter,
                              String page) {
        return chapter(systemSlug, structureSegment, chapter) + page + "/";
    }

    /** A group of pages inside a chapter, {@code …/building-block-view/events/}. */
    public static String group(String systemSlug, String structureSegment, StructureChapter chapter,
                               String group) {
        return chapter(systemSlug, structureSegment, chapter) + group + "/";
    }

    /** A page in a group, {@code …/building-block-view/events/orders-payment-accepted/}. */
    public static String page(String systemSlug, String structureSegment, StructureChapter chapter,
                              String group, String page) {
        return group(systemSlug, structureSegment, chapter, group) + page + "/";
    }

    /**
     * A component. It lives inside the chapter that describes the decomposition, because a component is one of
     * the building blocks.
     */
    public static String component(String systemSlug, String structureSegment, StructureChapter chapter,
                                   String componentName) {
        return page(systemSlug, structureSegment, chapter, COMPONENTS_SEGMENT, componentName);
    }
}
