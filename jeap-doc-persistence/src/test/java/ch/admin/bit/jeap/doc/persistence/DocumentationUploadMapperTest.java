package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.DocumentationType;
import ch.admin.bit.jeap.doc.domain.DocumentationUpload;
import ch.admin.bit.jeap.doc.domain.DocumentationUploadDescriptor;
import ch.admin.bit.jeap.doc.domain.SourceFormat;
import ch.admin.bit.jeap.doc.domain.SubjectKind;
import ch.admin.bit.jeap.doc.domain.UploadState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an upload documents is stored on its subject and read back into the descriptor from there. The mapping
 * has to be exact in both directions: a descriptor that comes back different from what was written would make
 * every retry look like a reused upload id.
 */
class DocumentationUploadMapperTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-08-24T09:12:00Z");

    @ParameterizedTest
    @CsvSource({
            "SYSTEM,    SYSTEM_DOCS,    ,             ",
            "COMPONENT, COMPONENT_DOCS, foo-bar-scs,  ",
            "LIBRARY,   LIBRARY_DOCS,   ,            wvs-common-lib"
    })
    void toDomain_thenTheDescriptorNamesWhatTheSubjectHolds(SubjectKind kind, DocumentationType type,
                                                            String component, String library) {
        String name = component != null ? component : library;
        DocumentationUpload upload = DocumentationUploadMapper.toDomain(entity(kind, name));

        DocumentationUploadDescriptor descriptor = upload.descriptor();
        assertThat(descriptor.type()).isEqualTo(type);
        assertThat(descriptor.site()).isEqualTo("default");
        assertThat(descriptor.system()).isEqualTo("wvs");
        assertThat(descriptor.component()).isEqualTo(component);
        assertThat(descriptor.library()).isEqualTo(library);
        assertThat(upload.subject().kind()).isEqualTo(kind);
        assertThat(upload.bundleSha256()).isEqualTo("6b86b273ff34fce19d6b804eff5a3f5747ada4eaa22f1d49c01e52ddb7875b4b");
    }

    @Test
    void applyDescriptor_thenTheEntityCarriesEverythingThatIsNotOnTheSubject() {
        DocumentationUploadEntity entity = new DocumentationUploadEntity();

        DocumentationUploadMapper.applyDescriptor(entity, descriptor());

        assertThat(entity.getTemplate()).isEqualTo("arc42");
        assertThat(entity.getSourceFormat()).isEqualTo(SourceFormat.HTML);
        assertThat(entity.getLocation()).isEqualTo("6-runtime-view");
        assertThat(entity.getTopic()).isEqualTo("spring-rest-docs");
        assertThat(entity.getLabel()).isEqualTo("Spring REST Docs");
        assertThat(entity.getVersion()).isEqualTo("1.4.0");
        assertThat(entity.getSourceRepository()).isEqualTo("ssh://git@bitbucket.example.ch/wvs/foo-bar-scs.git");
        assertThat(entity.getSourceRevision()).isEqualTo("9a1c2f8");
        assertThat(entity.getSourceRef()).isEqualTo("main");
        assertThat(entity.getSourceTimestamp()).isEqualTo(Instant.parse("2026-08-21T07:12:00Z"));
        assertThat(entity.getBuildUrl()).isEqualTo("https://github.com/wvs/foo-bar-scs/actions/runs/1234567890");
        assertThat(entity.getGeneratedAt()).isEqualTo(Instant.parse("2026-08-21T07:15:00Z"));
    }

    /**
     * The round trip is what a retry is compared against, so what goes in has to come back out unchanged.
     */
    @Test
    void toDomain_whenTheDescriptorWasApplied_thenItComesBackUnchanged() {
        DocumentationUploadEntity entity = entity(SubjectKind.COMPONENT, "foo-bar-scs");
        DocumentationUploadMapper.applyDescriptor(entity, descriptor());

        assertThat(DocumentationUploadMapper.toDomain(entity).descriptor()).isEqualTo(descriptor());
    }

    private static DocumentationUploadDescriptor descriptor() {
        return DocumentationUploadDescriptor.builder()
                .type(DocumentationType.COMPONENT_DOCS)
                .system("wvs")
                .component("foo-bar-scs")
                .version("1.4.0")
                .template("arc42")
                .sourceFormat(SourceFormat.HTML)
                .location("6-runtime-view")
                .topic("spring-rest-docs")
                .label("Spring REST Docs")
                .sourceRepository("ssh://git@bitbucket.example.ch/wvs/foo-bar-scs.git")
                .sourceRevision("9a1c2f8")
                .sourceRef("main")
                .sourceTimestamp(Instant.parse("2026-08-21T07:12:00Z"))
                .buildUrl("https://github.com/wvs/foo-bar-scs/actions/runs/1234567890")
                .generatedAt(Instant.parse("2026-08-21T07:15:00Z"))
                .build();
    }

    private static DocumentationUploadEntity entity(SubjectKind kind, String name) {
        DocumentationSubjectEntity subject = new DocumentationSubjectEntity();
        subject.setId(1L);
        subject.setSite("default");
        subject.setKind(kind);
        subject.setSystem("wvs");
        subject.setName(name);
        subject.setCreatedAt(RECEIVED_AT);

        DocumentationUploadEntity entity = new DocumentationUploadEntity();
        entity.setId(42L);
        entity.setUploadId(java.util.UUID.fromString("8f1c9a2e-6a1a-4a5f-9a5e-2b0f9a3c1d77"));
        entity.setSubject(subject);
        entity.setState(UploadState.UPLOADING);
        entity.setBundleSha256("6b86b273ff34fce19d6b804eff5a3f5747ada4eaa22f1d49c01e52ddb7875b4b");
        entity.setAttempt(1);
        entity.setReceivedAt(RECEIVED_AT);
        entity.setTemplate("arc42");
        entity.setSourceFormat(SourceFormat.MARKDOWN);
        entity.setSourceRepository("ssh://git@bitbucket.example.ch/wvs/foo-bar-scs.git");
        entity.setSourceRevision("9a1c2f8");
        entity.setSourceRef("main");
        entity.setSourceTimestamp(Instant.parse("2026-08-21T07:12:00Z"));
        if (kind != SubjectKind.SYSTEM) {
            entity.setVersion("1.4.0");
        }
        return entity;
    }
}
