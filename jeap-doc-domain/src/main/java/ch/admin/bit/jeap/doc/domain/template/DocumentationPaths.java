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

    /**
     * The extension of a file that becomes a document.
     * <p>
     * The site is built with {@code format: 'md'} and the site generator routes nothing else, so this is the
     * domain's fact rather than a template's preference - it is what decides whether a name can collide with
     * a generated page at all.
     */
    public static final String MARKDOWN_EXTENSION = "md";

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

    /**
     * The paths of the structure below one component.
     *
     * @param componentStructureSegment the template's segment below a component, which is not the one it uses
     *                                  below a system - see {@link StructureTemplate#componentPathSegment()}
     */
    public static ComponentPaths componentPaths(String systemSlug, String structureSegment,
                                                StructureChapter chapter, String componentSlug,
                                                String componentStructureSegment) {
        return new ComponentPaths(component(systemSlug, structureSegment, chapter, componentSlug),
                componentStructureSegment);
    }

    /**
     * Where the pages of one component are served.
     * <p>
     * Addressing one takes six values, four of which are the same for every page of the component. They are
     * fixed here so that a caller passes only the chapter and the page.
     *
     * @param componentRoot    the component's own page, which the subtree hangs below
     * @param structureSegment the segment of the structure below the component
     */
    public record ComponentPaths(String componentRoot, String structureSegment) {

        /** The landing page of the structure, {@code …/components/orders-intake/component-architecture/}. */
        public String structure() {
            return componentRoot + structureSegment + "/";
        }

        /** A chapter of it. The URL segment, so without the number prefix. */
        public String chapter(StructureChapter chapter) {
            return structure() + chapter.urlSegment() + "/";
        }

        /** A page inside a chapter, {@code …/component-architecture/building-block-view/rest-api/}. */
        public String page(StructureChapter chapter, String page) {
            return chapter(chapter) + page + "/";
        }
    }
}
