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
 * @param system       the system the documents belong to, and the system the write role must be granted for
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
    private static final String MARKDOWN_DOCUMENTATION = "markdown documentation";

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
        requireSlug(system, "system");
        requireSlug(template, "template");
        requirePresent(sourceFormat, "source-format", "to know how the documents are written");

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
        }

        if (sourceFormat == SourceFormat.HTML) {
            requireSlug(location, "location");
            requireSlug(topic, "topic");
        } else {
            requireAbsent(location, "location", MARKDOWN_DOCUMENTATION);
            requireAbsent(topic, "topic", MARKDOWN_DOCUMENTATION);
        }
    }

    /** What kind of thing this documents - what the template is asked about the names it generates. */
    public SubjectKind subject() {
        return SubjectKind.of(type);
    }

    /** The component or library the documents belong to, or null for system documentation. */
    public String subjectName() {
        return switch (type) {
            case SYSTEM_DOCS -> null;
            case COMPONENT_DOCS -> component;
            case LIBRARY_DOCS -> library;
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
