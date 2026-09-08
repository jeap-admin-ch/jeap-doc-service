package ch.admin.bit.jeap.doc.domain.upload;

import ch.admin.bit.jeap.doc.domain.Site;
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
        // Where the documents belong, and the rules over it - shared with the structure validation, which is
        // a placement and nothing else. See DocumentationPlacement.
        DocumentationPlacement.check(type, system, component, library, template, sourceFormat, location, topic);

        // And what only an upload needs: a version for anything that has one, and a label for a page that
        // appears in a menu. The validation endpoint accepts neither - a path tree does not depend on them.
        switch (type) {
            case SYSTEM_DOCS -> {
                // A system documents itself; there is no version of a system to name.
            }
            case COMPONENT_DOCS -> requireText(version, "version", "for component documentation");
            case LIBRARY_DOCS -> requireText(version, "version", "for library documentation");
        }
        if (sourceFormat == SourceFormat.HTML) {
            requireText(label, "label", "for HTML documents");
        } else {
            requireAbsent(label, "label", MARKDOWN_DOCUMENTATION);
        }

        requireText(sourceRepository, "source-repository", PROVENANCE_REQUIRED);
        requireText(sourceRevision, "source-revision", PROVENANCE_REQUIRED);
        requireText(sourceRef, "source-ref", PROVENANCE_REQUIRED);
        requirePresent(sourceTimestamp, "source-timestamp", PROVENANCE_REQUIRED);
    }

    /** Where these documents belong, without the provenance of the files. */
    public DocumentationPlacement placement() {
        return new DocumentationPlacement(type, system, component, library, template, sourceFormat, location,
                topic);
    }

    /**
     * The component or library the documents belong to, or null for system documentation.
     */
    public String subjectName() {
        return placement().subjectName();
    }

    /**
     * The precision a timestamp survives a round trip through the database with.
     */
    private static Instant toStoredPrecision(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }

    // The rules over the placement, and their helpers, are DocumentationPlacement's - these three are what
    // this record still checks itself.

    private static void requireText(String value, String parameter, String requiredBecause) {
        DocumentationPlacement.requireText(value, parameter, requiredBecause);
    }

    private static void requireSlug(String value, String parameter) {
        DocumentationPlacement.requireSlug(value, parameter);
    }

    private static void requireAbsent(String value, String parameter, String documentationKind) {
        DocumentationPlacement.requireAbsent(value, parameter, documentationKind);
    }

    private static void requirePresent(Object value, String parameter, String requiredBecause) {
        DocumentationPlacement.requirePresent(value, parameter, requiredBecause);
    }
}
