package ch.admin.bit.jeap.doc.domain.upload;

import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.Slugs;
import lombok.Builder;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * What one upload documents, and where its documents came from.
 * <p>
 * The values mirror the configuration a repository gives to its doc workflow, and which of them are required
 * depends on the others: a component documentation names its component, a library documentation its library, and
 * HTML documents name the section they are embedded in. The record enforces those rules when it is built, so an
 * upload that cannot be placed is rejected before anything is read from it.
 * <p>
 * The descriptor is recorded with the upload and never changes afterwards: every attempt under one upload id has
 * to describe the same upload, see {@link DocumentationUpload}.
 *
 * @param site             the site the documents belong to - the default site when the upload names none
 * @param type             what the documents document
 * @param system           the system the documents belong to, and the system the write role must be granted for
 * @param component        the component the documents belong to, for component documentation
 * @param library          the library the documents belong to, for library documentation
 * @param template         the section catalog the documents follow, e.g. arc42
 * @param sourceFormat     the format the documents are written in
 * @param location         the section HTML documents are embedded in, e.g. 6-runtime-view
 * @param topic            the slug identifying HTML documents within their section
 * @param label            the menu label of HTML documents
 * @param sourceRepository the repository the documents came from
 * @param sourceRevision   the commit the documents were built from
 * @param sourceRef        the branch or tag that was built
 * @param sourceTimestamp  the timestamp of the commit the documents were built from, as an instant and at the
 *                         precision the database keeps, so that two attempts of one upload describe it identically
 * @param version          the version of the component or library the documents belong to
 * @param buildUrl         the build that uploaded the documents
 * @param generatedAt      when the documents were generated
 */
@Builder
public record DocumentationUploadDescriptor(
        String site,
        DocumentationType type,
        String system,
        String component,
        String library,
        String template,
        SourceFormat sourceFormat,
        String location,
        String topic,
        String label,
        String sourceRepository,
        String sourceRevision,
        String sourceRef,
        Instant sourceTimestamp,
        String version,
        String buildUrl,
        Instant generatedAt) {


    private static final String COMPONENT_PARAMETER = "component";
    private static final String LIBRARY_PARAMETER = "library";
    private static final String PROVENANCE_REQUIRED = "to record where the documents came from";
    private static final String MARKDOWN_DOCUMENTATION = "markdown documentation";

    public DocumentationUploadDescriptor {
        // Normalized, not defaulted on reading: an upload that names no site and one that names the default site
        // are the same upload, and a retry of the one has to be recognised as a retry of the other.
        site = StringUtils.hasText(site) ? site : Site.DEFAULT_SITE;
        // For the same reason: what is compared with a retry is what came back from the database, and PostgreSQL
        // keeps microseconds. A timestamp carrying more precision than that would come back as something else
        // than it went in, and every retry of that upload would be answered as a different upload.
        sourceTimestamp = toStoredPrecision(sourceTimestamp);
        generatedAt = toStoredPrecision(generatedAt);
        requireSlug(site, "site");
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
                requireText(version, "version", "for component documentation");
            }
            case LIBRARY_DOCS -> {
                requireSlug(library, LIBRARY_PARAMETER);
                requireAbsent(component, COMPONENT_PARAMETER, "library documentation");
                requireText(version, "version", "for library documentation");
            }
        }

        if (sourceFormat == SourceFormat.HTML) {
            requireSlug(location, "location");
            requireSlug(topic, "topic");
            requireText(label, "label", "for HTML documents");
        } else {
            requireAbsent(location, "location", MARKDOWN_DOCUMENTATION);
            requireAbsent(topic, "topic", MARKDOWN_DOCUMENTATION);
            requireAbsent(label, "label", MARKDOWN_DOCUMENTATION);
        }

        requireText(sourceRepository, "source-repository", PROVENANCE_REQUIRED);
        requireText(sourceRevision, "source-revision", PROVENANCE_REQUIRED);
        requireText(sourceRef, "source-ref", PROVENANCE_REQUIRED);
        requirePresent(sourceTimestamp, "source-timestamp", PROVENANCE_REQUIRED);
    }

    /**
     * The component or library the documents belong to, or null for system documentation.
     */
    public String subjectName() {
        return switch (type) {
            case SYSTEM_DOCS -> null;
            case COMPONENT_DOCS -> component;
            case LIBRARY_DOCS -> library;
        };
    }

    /**
     * The precision a timestamp survives a round trip through the database with.
     */
    private static Instant toStoredPrecision(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }

    private static void requirePresent(Object value, String parameter, String requiredBecause) {
        if (value == null) {
            throw InvalidUploadException.missing(parameter, requiredBecause);
        }
    }

    private static void requireText(String value, String parameter, String requiredBecause) {
        if (!StringUtils.hasText(value)) {
            throw InvalidUploadException.missing(parameter, requiredBecause);
        }
    }

    private static void requireSlug(String value, String parameter) {
        requireText(value, parameter, "to identify the documentation set");
        if (!Slugs.isSlug(value)) {
            throw InvalidUploadException.invalidValue(parameter, value, Slugs.DESCRIPTION);
        }
    }

    private static void requireAbsent(String value, String parameter, String documentationKind) {
        if (StringUtils.hasText(value)) {
            throw InvalidUploadException.invalidValue(parameter, value,
                    "no value, as %s names no %s".formatted(documentationKind, parameter));
        }
    }
}
