package ch.admin.bit.jeap.doc.domain.upload;

import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.upload.DocumentationUploadDescriptor.DocumentationUploadDescriptorBuilder;
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
        DocumentationUploadDescriptorBuilder withoutComponent = componentDocs().component(null);
        assertThatThrownBy(withoutComponent::build)
                .isInstanceOfSatisfying(InvalidUploadException.class,
                        e -> assertThat(e.getCode()).isEqualTo(InvalidUploadException.Code.MISSING_PARAMETER))
                .hasMessageContaining("component");
    }

    @Test
    void build_whenLibraryDocumentationWithoutLibrary_thenRejected() {
        DocumentationUploadDescriptorBuilder withoutLibrary = libraryDocs().library(null);
        assertThatThrownBy(withoutLibrary::build)
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("library");
    }

    @Test
    void build_whenComponentDocumentationWithoutVersion_thenRejected() {
        DocumentationUploadDescriptorBuilder withoutVersion = componentDocs().version(null);
        assertThatThrownBy(withoutVersion::build)
                .isInstanceOfSatisfying(InvalidUploadException.class,
                        e -> assertThat(e.getCode()).isEqualTo(InvalidUploadException.Code.MISSING_PARAMETER))
                .hasMessageContaining("version");
    }

    @Test
    void build_whenLibraryDocumentationWithoutVersion_thenRejected() {
        DocumentationUploadDescriptorBuilder withoutVersion = libraryDocs().version(null);
        assertThatThrownBy(withoutVersion::build)
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("version");
    }

    @Test
    void build_whenSystemDocumentationWithoutVersion_thenAccepted() {
        assertThatCode(() -> systemDocs().version(null).build()).doesNotThrowAnyException();
    }

    @Test
    void build_whenSystemDocumentationNamesAComponent_thenRejected() {
        DocumentationUploadDescriptorBuilder withComponent = systemDocs().component("foo-bar-scs");
        assertThatThrownBy(withComponent::build)
                .isInstanceOfSatisfying(InvalidUploadException.class,
                        e -> assertThat(e.getCode()).isEqualTo(InvalidUploadException.Code.INVALID_PARAMETER_VALUE))
                .hasMessageContaining("component");
    }

    @Test
    void build_whenComponentDocumentationNamesALibrary_thenRejected() {
        DocumentationUploadDescriptorBuilder withLibrary = componentDocs().library("foo-bar-lib");
        assertThatThrownBy(withLibrary::build)
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("library");
    }

    @Test
    void build_whenHtmlWithoutLocation_thenRejected() {
        DocumentationUploadDescriptorBuilder withoutLocation = htmlComponentDocs().location(null);
        assertThatThrownBy(withoutLocation::build)
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("location");
    }

    @Test
    void build_whenHtmlWithoutTopic_thenRejected() {
        DocumentationUploadDescriptorBuilder withoutTopic = htmlComponentDocs().topic(null);
        assertThatThrownBy(withoutTopic::build)
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("topic");
    }

    @Test
    void build_whenHtmlWithoutLabel_thenRejected() {
        DocumentationUploadDescriptorBuilder withoutLabel = htmlComponentDocs().label(null);
        assertThatThrownBy(withoutLabel::build)
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("label");
    }

    @Test
    void build_whenMarkdownWithoutLocationTopicAndLabel_thenAccepted() {
        assertThatCode(() -> componentDocs().build()).doesNotThrowAnyException();
    }

    @Test
    void build_whenSiteIsGiven_thenAccepted() {
        assertThat(systemDocs().site("catalog").build().site()).isEqualTo("catalog");
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
        DocumentationUploadDescriptorBuilder withSite = systemDocs().site("Catalog");
        assertThatThrownBy(withSite::build)
                .isInstanceOfSatisfying(InvalidUploadException.class,
                        e -> assertThat(e.getCode()).isEqualTo(InvalidUploadException.Code.INVALID_PARAMETER_VALUE))
                .hasMessageContaining("site");
    }

    @Test
    void build_whenMarkdownCarriesTheParametersOfHtmlDocuments_thenRejected() {
        DocumentationUploadDescriptorBuilder withLocation = componentDocs().location("6-runtime-view");
        assertThatThrownBy(withLocation::build)
                .isInstanceOfSatisfying(InvalidUploadException.class,
                        e -> assertThat(e.getCode()).isEqualTo(InvalidUploadException.Code.INVALID_PARAMETER_VALUE))
                .hasMessageContaining("location");
        DocumentationUploadDescriptorBuilder withTopic = componentDocs().topic("spring-rest-docs");
        assertThatThrownBy(withTopic::build)
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("topic");
        DocumentationUploadDescriptorBuilder withLabel = componentDocs().label("Spring REST Docs");
        assertThatThrownBy(withLabel::build)
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("label");
    }

    @Test
    void build_whenIdentifierIsNoSlug_thenRejected() {
        DocumentationUploadDescriptorBuilder withSystem = systemDocs().system("ORDERS");
        assertThatThrownBy(withSystem::build)
                .isInstanceOfSatisfying(InvalidUploadException.class,
                        e -> assertThat(e.getCode()).isEqualTo(InvalidUploadException.Code.INVALID_PARAMETER_VALUE))
                .hasMessageContaining("system");
    }

    @Test
    void build_whenProvenanceIsIncomplete_thenRejected() {
        DocumentationUploadDescriptorBuilder withoutSourceRepository = systemDocs().sourceRepository(null);
        assertThatThrownBy(withoutSourceRepository::build)
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("source-repository");
        DocumentationUploadDescriptorBuilder withoutSourceRevision = systemDocs().sourceRevision(null);
        assertThatThrownBy(withoutSourceRevision::build)
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("source-revision");
        DocumentationUploadDescriptorBuilder withoutSourceRef = systemDocs().sourceRef(null);
        assertThatThrownBy(withoutSourceRef::build)
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("source-ref");
        DocumentationUploadDescriptorBuilder withoutSourceTimestamp = systemDocs().sourceTimestamp(null);
        assertThatThrownBy(withoutSourceTimestamp::build)
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
                .system("orders")
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
                .library("orders-common-lib")
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
                .sourceRepository("ssh://git@bitbucket.example.ch/orders/foo-bar-scs.git")
                .sourceRevision("9a1c2f8")
                .sourceRef("main")
                .sourceTimestamp(Instant.parse("2026-08-21T07:12:00Z"))
                .buildUrl("https://github.com/orders/foo-bar-scs/actions/runs/1234567890")
                .generatedAt(Instant.parse("2026-08-21T07:15:00Z"));
    }
}
