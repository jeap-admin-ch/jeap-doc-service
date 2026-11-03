package ch.admin.bit.jeap.doc.domain.upload;

import ch.admin.bit.jeap.doc.domain.Slugs;
import org.springframework.util.StringUtils;

/**
 * Where a set of documents belongs: what it documents, of which system, following which template, written in
 * which format.
 * <p>
 * <b>Extracted from {@link DocumentationUploadDescriptor} because it has a second caller.</b> An upload is a
 * placement plus a version, a label and the provenance of the files; the structure validation is a placement
 * and nothing else - it answers a question about a path tree, which does not depend on which commit produced
 * it. The co-occurrence rules live here so that the two callers cannot disagree about what a placeable upload
 * is.
 *
 * @param type         what the documents document
 * @param system       the system the documents belong to, and the system the write role must be granted for;
 *                     null for a static site's documents, which belong to no system
 * @param component    the component the documents belong to, for component documentation
 * @param library      the library the documents belong to, for library documentation
 * @param template     the section catalog the documents follow, e.g. arc42. <b>Checked here as a slug only</b>
 *                     - that a template of that name exists is the validation's own question, because an
 *                     upload of an unknown template is accepted today and this record must not change that
 * @param sourceFormat the format the documents are written in
 * @param location     the section HTML documents are embedded in, e.g. 6-runtime-view
 * @param topic        the slug identifying HTML documents within their section
 */
public record DocumentationPlacement(
        DocumentationType type,
        String system,
        String component,
        String library,
        String template,
        SourceFormat sourceFormat,
        String location,
        String topic) {

    static final String COMPONENT_PARAMETER = "component";
    static final String LIBRARY_PARAMETER = "library";
    private static final String SYSTEM_PARAMETER = "system";
    private static final String TEMPLATE_PARAMETER = "template";
    private static final String LOCATION_PARAMETER = "location";
    private static final String TOPIC_PARAMETER = "topic";
    private static final String MARKDOWN_DOCUMENTATION = "markdown documentation";
    private static final String SITE_DOCUMENTATION = "the documentation of a static site";

    public DocumentationPlacement {
        check(type, system, component, library, template, sourceFormat, location, topic);
    }

    /**
     * The rules, as a call rather than as a constructor, so that
     * {@link DocumentationUploadDescriptor} applies them without building a value it would throw away.
     */
    static void check(DocumentationType type, String system, String component, String library, String template,
                      SourceFormat sourceFormat, String location, String topic) {
        requirePresent(type, "type", "to know what the documents document");
        requirePresent(sourceFormat, "source-format", "to know how the documents are written");
        if (type == DocumentationType.SITE_DOCS) {
            checkSiteDocumentation(system, component, library, template, sourceFormat, location, topic);
            return;
        }
        requireSlug(system, SYSTEM_PARAMETER);
        requireSlug(template, TEMPLATE_PARAMETER);

        checkSubject(type, component, library);

        if (sourceFormat == SourceFormat.HTML) {
            requireSlug(location, LOCATION_PARAMETER);
            requireSlug(topic, TOPIC_PARAMETER);
        } else {
            requireAbsent(location, LOCATION_PARAMETER, MARKDOWN_DOCUMENTATION);
            requireAbsent(topic, TOPIC_PARAMETER, MARKDOWN_DOCUMENTATION);
        }
    }

    /**
     * A static site's documents are the site's own: they name no system, component, library or template, and
     * they are Markdown - a tree published as it is.
     */
    private static void checkSiteDocumentation(String system, String component, String library, String template,
                                               SourceFormat sourceFormat, String location, String topic) {
        requireAbsent(system, SYSTEM_PARAMETER, SITE_DOCUMENTATION);
        requireAbsent(component, COMPONENT_PARAMETER, SITE_DOCUMENTATION);
        requireAbsent(library, LIBRARY_PARAMETER, SITE_DOCUMENTATION);
        requireAbsent(template, TEMPLATE_PARAMETER, SITE_DOCUMENTATION);
        if (sourceFormat != SourceFormat.MARKDOWN) {
            throw InvalidUploadException.invalidValue("source-format", sourceFormat.name(),
                    "MARKDOWN, as a static site is a tree of Markdown");
        }
        requireAbsent(location, LOCATION_PARAMETER, SITE_DOCUMENTATION);
        requireAbsent(topic, TOPIC_PARAMETER, SITE_DOCUMENTATION);
    }

    /** Which of {@code component} and {@code library} goes with which type, and neither with a system. */
    private static void checkSubject(DocumentationType type, String component, String library) {
        switch (type) {
            case SYSTEM_DOCS -> {
                requireAbsent(component, COMPONENT_PARAMETER, "system documentation");
                requireAbsent(library, LIBRARY_PARAMETER, "system documentation");
            }
            case COMPONENT_DOCS -> {
                requireSlug(component, COMPONENT_PARAMETER);
                requireAbsent(library, LIBRARY_PARAMETER, "component documentation");
            }
            case LIBRARY_DOCS -> {
                requireSlug(library, LIBRARY_PARAMETER);
                requireAbsent(component, COMPONENT_PARAMETER, "library documentation");
            }
            case SITE_DOCS -> {
                requireAbsent(component, COMPONENT_PARAMETER, SITE_DOCUMENTATION);
                requireAbsent(library, LIBRARY_PARAMETER, SITE_DOCUMENTATION);
            }
        }
    }

    /**
     * What is documented, for a caller that names a subject and not a set.
     * <p>
     * <b>Here because the rules are here.</b> Removing everything documented for a component names no
     * template and no source format - it removes them all - and building a placement with invented ones only
     * to throw them away would put a template's name in a caller that must not know one.
     *
     * @throws InvalidUploadException if the three parameters do not describe a subject
     */
    public static Subject subjectOf(DocumentationType type, String system, String component, String library) {
        requirePresent(type, "type", "to know what the documents document");
        if (type == DocumentationType.SITE_DOCS) {
            requireAbsent(system, SYSTEM_PARAMETER, SITE_DOCUMENTATION);
        } else {
            requireSlug(system, SYSTEM_PARAMETER);
        }
        checkSubject(type, component, library);
        return new Subject(SubjectKind.of(type), system, subjectNameOf(type, component, library));
    }

    /**
     * What a set of documents documents, without saying how it is written.
     *
     * @param kind   whether it is a system, a component, a library or a static site
     * @param system the system it belongs to, also for a component and for a library; null for a static site
     * @param name   the component or the library, null for a system
     */
    public record Subject(SubjectKind kind, String system, String name) {
    }

    /** What kind of thing this documents - what the template is asked about the names it generates. */
    public SubjectKind subject() {
        return SubjectKind.of(type);
    }

    /** The component or library the documents belong to, or null for system documentation. */
    public String subjectName() {
        return subjectNameOf(type, component, library);
    }

    private static String subjectNameOf(DocumentationType type, String component, String library) {
        return switch (type) {
            case SYSTEM_DOCS -> null;
            case COMPONENT_DOCS -> component;
            case LIBRARY_DOCS -> library;
            case SITE_DOCS -> null;
        };
    }

    static void requirePresent(Object value, String parameter, String requiredBecause) {
        if (value == null) {
            throw InvalidUploadException.missing(parameter, requiredBecause);
        }
    }

    static void requireText(String value, String parameter, String requiredBecause) {
        if (!StringUtils.hasText(value)) {
            throw InvalidUploadException.missing(parameter, requiredBecause);
        }
    }

    static void requireSlug(String value, String parameter) {
        requireText(value, parameter, "to identify the documentation set");
        if (!Slugs.isSlug(value)) {
            throw InvalidUploadException.invalidValue(parameter, value, Slugs.DESCRIPTION);
        }
    }

    static void requireAbsent(String value, String parameter, String documentationKind) {
        if (StringUtils.hasText(value)) {
            throw InvalidUploadException.invalidValue(parameter, value,
                    "no value, as %s names no %s".formatted(documentationKind, parameter));
        }
    }
}
