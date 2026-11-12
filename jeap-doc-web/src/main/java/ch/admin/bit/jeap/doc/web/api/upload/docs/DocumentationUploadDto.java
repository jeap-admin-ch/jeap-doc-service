package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.domain.upload.DocumentationUploadDescriptor;
import lombok.Builder;

import java.time.OffsetDateTime;

/**
 * The parameters of one documentation upload as they arrive on the request.
 * <p>
 * The record binds what the doc workflow of a repository sends and converts it into the description the domain
 * works with; which values are required, and which of them may appear together, is decided by
 * {@link DocumentationUploadDescriptor}.
 */
@Builder
record DocumentationUploadDto(
        String site,
        DocumentationTypeDto type,
        String system,
        String component,
        String library,
        String template,
        SourceFormatDto sourceFormat,
        String location,
        String topic,
        String label,
        String sourceRepository,
        String sourceRevision,
        String sourceRef,
        OffsetDateTime sourceTimestamp,
        String version,
        String buildUrl,
        OffsetDateTime generatedAt,
        Double sidebarPosition,
        String sidebarAfter,
        String sidebarBefore) {

    DocumentationUploadDescriptor toDescriptor() {
        return DocumentationUploadDescriptor.builder()
                .site(site)
                .type(type == null ? null : type.toDomain())
                .system(system)
                .component(component)
                .library(library)
                .template(template)
                .sourceFormat(sourceFormat == null ? null : sourceFormat.toDomain())
                .location(location)
                .topic(topic)
                .label(label)
                .sourceRepository(sourceRepository)
                .sourceRevision(sourceRevision)
                .sourceRef(sourceRef)
                .sourceTimestamp(sourceTimestamp == null ? null : sourceTimestamp.toInstant())
                .version(version)
                .buildUrl(buildUrl)
                .generatedAt(generatedAt == null ? null : generatedAt.toInstant())
                .sidebarPosition(sidebarPosition)
                .sidebarAfter(sidebarAfter)
                .sidebarBefore(sidebarBefore)
                .build();
    }
}
