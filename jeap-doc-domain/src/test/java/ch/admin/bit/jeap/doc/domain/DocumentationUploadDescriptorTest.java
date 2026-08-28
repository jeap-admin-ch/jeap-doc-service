package ch.admin.bit.jeap.doc.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentationUploadDescriptorTest {

    @Test
    void build_whenSystemDocumentationInMarkdown_thenAccepted() {
        assertThatCode(() -> systemDocs().build()).doesNotThrowAnyException();
    }

    @Test
    void build_whenComponentDocumentationInHtml_thenAccepted() {
        DocumentationUploadDescriptor upload = componentDocs()
                .sourceFormat(SourceFormat.HTML)
                .location("6-runtime-view")
                .topic("spring-rest-docs")
                .label("Spring REST Docs")
                .build();

        assertThat(upload.type()).isEqualTo(DocumentationType.COMPONENT_DOCS);
        assertThat(upload.component()).isEqualTo("foo-bar-scs");
    }

    @Test
    void build_whenComponentDocumentationWithoutComponent_thenRejected() {
        assertThatThrownBy(() -> componentDocs().component(null).build())
                .isInstanceOfSatisfying(InvalidUploadException.class,
                        e -> assertThat(e.getCode()).isEqualTo(InvalidUploadException.Code.MISSING_PARAMETER))
                .hasMessageContaining("component");
    }

    @Test
    void build_whenLibraryDocumentationWithoutLibrary_thenRejected() {
        assertThatThrownBy(() -> libraryDocs().library(null).build())
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("library");
    }

    @Test
    void build_whenComponentDocumentationWithoutVersion_thenRejected() {
        assertThatThrownBy(() -> componentDocs().version(null).build())
                .isInstanceOfSatisfying(InvalidUploadException.class,
                        e -> assertThat(e.getCode()).isEqualTo(InvalidUploadException.Code.MISSING_PARAMETER))
                .hasMessageContaining("version");
    }

    @Test
    void build_whenLibraryDocumentationWithoutVersion_thenRejected() {
        assertThatThrownBy(() -> libraryDocs().version(null).build())
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("version");
    }

    @Test
    void build_whenSystemDocumentationWithoutVersion_thenAccepted() {
        assertThatCode(() -> systemDocs().version(null).build()).doesNotThrowAnyException();
    }

    @Test
    void build_whenSystemDocumentationNamesAComponent_thenRejected() {
        assertThatThrownBy(() -> systemDocs().component("foo-bar-scs").build())
                .isInstanceOfSatisfying(InvalidUploadException.class,
                        e -> assertThat(e.getCode()).isEqualTo(InvalidUploadException.Code.INVALID_PARAMETER_VALUE))
                .hasMessageContaining("component");
    }

    @Test
    void build_whenComponentDocumentationNamesALibrary_thenRejected() {
        assertThatThrownBy(() -> componentDocs().library("foo-bar-lib").build())
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("library");
    }

    @Test
    void build_whenHtmlWithoutLocation_thenRejected() {
        assertThatThrownBy(() -> htmlComponentDocs().location(null).build())
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("location");
    }

    @Test
    void build_whenHtmlWithoutTopic_thenRejected() {
        assertThatThrownBy(() -> htmlComponentDocs().topic(null).build())
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("topic");
    }

    @Test
    void build_whenHtmlWithoutLabel_thenRejected() {
        assertThatThrownBy(() -> htmlComponentDocs().label(null).build())
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("label");
    }

    @Test
    void build_whenMarkdownWithoutLocationTopicAndLabel_thenAccepted() {
        assertThatCode(() -> componentDocs().build()).doesNotThrowAnyException();
    }

    @Test
    void build_whenSiteIsGiven_thenAccepted() {
        assertThat(systemDocs().site("dazit").build().site()).isEqualTo("dazit");
    }

    /**
     * The site is normalized rather than defaulted on reading, so an upload that names no site and one that names
     * the default site are the same upload - which is what a retry has to be recognised as.
     */
    @Test
    void build_whenSiteIsMissing_thenTheDefaultSite() {
        assertThat(systemDocs().build().site()).isEqualTo(Site.DEFAULT_SITE);
        assertThat(systemDocs().build()).isEqualTo(systemDocs().site("default").build());
    }

    /**
     * What a retry is compared with is what came back from the database, and it keeps microseconds. A timestamp
     * that carries more than that has to be cut down when it arrives, or the upload it was sent with could never
     * be retried.
     */
    @Test
    void build_whenATimestampIsMorePreciseThanTheDatabase_thenItIsCutDownToWhatSurvives() {
        DocumentationUploadDescriptor descriptor = systemDocs()
                .sourceTimestamp(Instant.parse("2026-08-21T07:12:00.123456789Z"))
                .generatedAt(Instant.parse("2026-08-21T07:15:00.987654321Z"))
                .build();

        assertThat(descriptor.sourceTimestamp()).isEqualTo(Instant.parse("2026-08-21T07:12:00.123456Z"));
        assertThat(descriptor.generatedAt()).isEqualTo(Instant.parse("2026-08-21T07:15:00.987654Z"));
        assertThat(descriptor).isEqualTo(systemDocs()
                .sourceTimestamp(Instant.parse("2026-08-21T07:12:00.123456Z"))
                .generatedAt(Instant.parse("2026-08-21T07:15:00.987654Z"))
                .build());
    }

    @Test
    void build_whenSiteIsNoSlug_thenRejected() {
        assertThatThrownBy(() -> systemDocs().site("DaziT").build())
                .isInstanceOfSatisfying(InvalidUploadException.class,
                        e -> assertThat(e.getCode()).isEqualTo(InvalidUploadException.Code.INVALID_PARAMETER_VALUE))
                .hasMessageContaining("site");
    }

    @Test
    void build_whenMarkdownCarriesTheParametersOfHtmlDocuments_thenRejected() {
        assertThatThrownBy(() -> componentDocs().location("6-runtime-view").build())
                .isInstanceOfSatisfying(InvalidUploadException.class,
                        e -> assertThat(e.getCode()).isEqualTo(InvalidUploadException.Code.INVALID_PARAMETER_VALUE))
                .hasMessageContaining("location");
        assertThatThrownBy(() -> componentDocs().topic("spring-rest-docs").build())
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("topic");
        assertThatThrownBy(() -> componentDocs().label("Spring REST Docs").build())
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("label");
    }

    @Test
    void build_whenIdentifierIsNoSlug_thenRejected() {
        assertThatThrownBy(() -> systemDocs().system("WVS").build())
                .isInstanceOfSatisfying(InvalidUploadException.class,
                        e -> assertThat(e.getCode()).isEqualTo(InvalidUploadException.Code.INVALID_PARAMETER_VALUE))
                .hasMessageContaining("system");
    }

    @Test
    void build_whenProvenanceIsIncomplete_thenRejected() {
        assertThatThrownBy(() -> systemDocs().sourceRepository(null).build())
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("source-repository");
        assertThatThrownBy(() -> systemDocs().sourceRevision(null).build())
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("source-revision");
        assertThatThrownBy(() -> systemDocs().sourceRef(null).build())
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("source-ref");
        assertThatThrownBy(() -> systemDocs().sourceTimestamp(null).build())
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("source-timestamp");
    }

    @Test
    void build_whenBuildUrlAndGeneratedAtAreMissing_thenAccepted() {
        assertThatCode(() -> systemDocs().buildUrl(null).generatedAt(null).build()).doesNotThrowAnyException();
    }

    private static DocumentationUploadDescriptor.DocumentationUploadDescriptorBuilder systemDocs() {
        return provenance()
                .type(DocumentationType.SYSTEM_DOCS)
                .system("wvs")
                .template("arc42")
                .sourceFormat(SourceFormat.MARKDOWN);
    }

    private static DocumentationUploadDescriptor.DocumentationUploadDescriptorBuilder componentDocs() {
        return systemDocs()
                .type(DocumentationType.COMPONENT_DOCS)
                .component("foo-bar-scs")
                .version("1.4.0");
    }

    private static DocumentationUploadDescriptor.DocumentationUploadDescriptorBuilder libraryDocs() {
        return systemDocs()
                .type(DocumentationType.LIBRARY_DOCS)
                .library("wvs-common-lib")
                .version("1.4.0");
    }

    private static DocumentationUploadDescriptor.DocumentationUploadDescriptorBuilder htmlComponentDocs() {
        return componentDocs()
                .sourceFormat(SourceFormat.HTML)
                .location("6-runtime-view")
                .topic("spring-rest-docs")
                .label("Spring REST Docs");
    }

    private static DocumentationUploadDescriptor.DocumentationUploadDescriptorBuilder provenance() {
        return DocumentationUploadDescriptor.builder()
                .sourceRepository("ssh://git@bitbucket.example.ch/wvs/foo-bar-scs.git")
                .sourceRevision("9a1c2f8")
                .sourceRef("main")
                .sourceTimestamp(Instant.parse("2026-08-21T07:12:00Z"))
                .buildUrl("https://github.com/wvs/foo-bar-scs/actions/runs/1234567890")
                .generatedAt(Instant.parse("2026-08-21T07:15:00Z"));
    }
}
