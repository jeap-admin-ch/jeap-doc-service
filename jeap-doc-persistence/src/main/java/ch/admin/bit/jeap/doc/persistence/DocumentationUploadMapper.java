package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.DocumentationSubject;
import ch.admin.bit.jeap.doc.domain.DocumentationType;
import ch.admin.bit.jeap.doc.domain.DocumentationUpload;
import ch.admin.bit.jeap.doc.domain.DocumentationUploadDescriptor;
import ch.admin.bit.jeap.doc.domain.SubjectKind;

/**
 * Between the model of the domain and the rows of the database.
 * <p>
 * What an upload documents is stored once, on its subject, and read back into the descriptor from there - which
 * is why the descriptor of a stored upload compares equal to the one a retry sends.
 */
final class DocumentationUploadMapper {

    private DocumentationUploadMapper() {
    }

    static DocumentationSubject toDomain(DocumentationSubjectEntity entity) {
        return new DocumentationSubject(entity.getId(), entity.getSite(), entity.getKind(), entity.getSystem(),
                entity.getName(), entity.getCreatedAt());
    }

    static DocumentationUpload toDomain(DocumentationUploadEntity entity) {
        DocumentationSubject subject = toDomain(entity.getSubject());
        return new DocumentationUpload(entity.getId(), entity.getUploadId(), subject, toDescriptor(entity),
                entity.getState(), entity.getObjectKey(), entity.getBundleSha256(), entity.getSizeInBytes(),
                entity.getAttempt(), entity.getReceivedAt(), entity.getCompletedAt(), entity.getFailureReason());
    }

    /**
     * The row of a newly received upload, as the domain defines it - so what a fresh upload looks like is decided
     * in one place and not once more here.
     */
    static DocumentationUploadEntity toEntity(DocumentationUpload upload, DocumentationSubjectEntity subject) {
        DocumentationUploadEntity entity = new DocumentationUploadEntity();
        entity.setUploadId(upload.uploadId());
        entity.setSubject(subject);
        applyDescriptor(entity, upload.descriptor());
        entity.setState(upload.state());
        entity.setAttempt(upload.attempt());
        entity.setReceivedAt(upload.receivedAt());
        entity.setObjectKey(upload.objectKey());
        entity.setBundleSha256(upload.bundleSha256());
        entity.setSizeInBytes(upload.sizeInBytes());
        return entity;
    }

    static void applyDescriptor(DocumentationUploadEntity entity, DocumentationUploadDescriptor descriptor) {
        entity.setTemplate(descriptor.template());
        entity.setSourceFormat(descriptor.sourceFormat());
        entity.setLocation(descriptor.location());
        entity.setTopic(descriptor.topic());
        entity.setLabel(descriptor.label());
        entity.setVersion(descriptor.version());
        entity.setSourceRepository(descriptor.sourceRepository());
        entity.setSourceRevision(descriptor.sourceRevision());
        entity.setSourceRef(descriptor.sourceRef());
        entity.setSourceTimestamp(descriptor.sourceTimestamp());
        entity.setBuildUrl(descriptor.buildUrl());
        entity.setGeneratedAt(descriptor.generatedAt());
    }

    private static DocumentationUploadDescriptor toDescriptor(DocumentationUploadEntity entity) {
        DocumentationSubjectEntity subject = entity.getSubject();
        return DocumentationUploadDescriptor.builder()
                .site(subject.getSite())
                .type(typeOf(subject.getKind()))
                .system(subject.getSystem())
                .component(subject.getKind() == SubjectKind.COMPONENT ? subject.getName() : null)
                .library(subject.getKind() == SubjectKind.LIBRARY ? subject.getName() : null)
                .template(entity.getTemplate())
                .sourceFormat(entity.getSourceFormat())
                .location(entity.getLocation())
                .topic(entity.getTopic())
                .label(entity.getLabel())
                .sourceRepository(entity.getSourceRepository())
                .sourceRevision(entity.getSourceRevision())
                .sourceRef(entity.getSourceRef())
                .sourceTimestamp(entity.getSourceTimestamp())
                .version(entity.getVersion())
                .buildUrl(entity.getBuildUrl())
                .generatedAt(entity.getGeneratedAt())
                .build();
    }

    private static DocumentationType typeOf(SubjectKind kind) {
        return switch (kind) {
            case SYSTEM -> DocumentationType.SYSTEM_DOCS;
            case COMPONENT -> DocumentationType.COMPONENT_DOCS;
            case LIBRARY -> DocumentationType.LIBRARY_DOCS;
        };
    }
}
