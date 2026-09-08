package ch.admin.bit.jeap.doc.domain.upload;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Where a set of documents belongs, and the rules over it.
 * <p>
 * These rules used to sit in {@link DocumentationUploadDescriptor} alone. They are here because the structure
 * validation asks the same question of a request that carries no version and no provenance, and two callers
 * with two copies of a rule is how the two answers start to differ.
 */
class DocumentationPlacementTest {

    private static DocumentationPlacement systemDocs() {
        return new DocumentationPlacement(DocumentationType.SYSTEM_DOCS, "orders", null, null, "arc42",
                SourceFormat.MARKDOWN, null, null);
    }

    @Test
    void systemDocs_nameNeitherAComponentNorALibrary() {
        assertThatCode(DocumentationPlacementTest::systemDocs).doesNotThrowAnyException();
        assertThat(systemDocs().subject()).isEqualTo(SubjectKind.SYSTEM);
        assertThat(systemDocs().subjectName()).describedAs("a system documents itself").isNull();

        assertThatThrownBy(() -> new DocumentationPlacement(DocumentationType.SYSTEM_DOCS, "orders",
                "orders-intake", null, "arc42", SourceFormat.MARKDOWN, null, null))
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("component");
    }

    @Test
    void componentDocs_nameTheirComponentAndNoLibrary() {
        DocumentationPlacement placement = new DocumentationPlacement(DocumentationType.COMPONENT_DOCS,
                "orders", "orders-intake", null, "arc42", SourceFormat.MARKDOWN, null, null);

        assertThat(placement.subject()).isEqualTo(SubjectKind.COMPONENT);
        assertThat(placement.subjectName()).isEqualTo("orders-intake");
        assertThatThrownBy(() -> new DocumentationPlacement(DocumentationType.COMPONENT_DOCS, "orders", null,
                null, "arc42", SourceFormat.MARKDOWN, null, null))
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("component");
    }

    @Test
    void libraryDocs_nameTheirLibraryAndNoComponent() {
        DocumentationPlacement placement = new DocumentationPlacement(DocumentationType.LIBRARY_DOCS, "orders",
                null, "wvs-common-lib", "arc42", SourceFormat.MARKDOWN, null, null);

        assertThat(placement.subject()).isEqualTo(SubjectKind.LIBRARY);
        assertThat(placement.subjectName()).isEqualTo("wvs-common-lib");
        assertThatThrownBy(() -> new DocumentationPlacement(DocumentationType.LIBRARY_DOCS, "orders",
                "orders-intake", "wvs-common-lib", "arc42", SourceFormat.MARKDOWN, null, null))
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("component");
    }

    /** HTML documents name where they are embedded; Markdown documents must not. */
    @Test
    void html_namesItsLocationAndTopic() {
        assertThatCode(() -> new DocumentationPlacement(DocumentationType.SYSTEM_DOCS, "orders", null, null,
                "arc42", SourceFormat.HTML, "6-runtime-view", "load-test")).doesNotThrowAnyException();

        assertThatThrownBy(() -> new DocumentationPlacement(DocumentationType.SYSTEM_DOCS, "orders", null,
                null, "arc42", SourceFormat.HTML, null, "load-test"))
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("location");
        assertThatThrownBy(() -> new DocumentationPlacement(DocumentationType.SYSTEM_DOCS, "orders", null,
                null, "arc42", SourceFormat.MARKDOWN, "6-runtime-view", null))
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("location");
    }

    @Test
    void theSystemAndTheTemplateAreSlugs() {
        assertThatThrownBy(() -> new DocumentationPlacement(DocumentationType.SYSTEM_DOCS, "Not A Slug", null,
                null, "arc42", SourceFormat.MARKDOWN, null, null))
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("system");
        assertThatThrownBy(() -> new DocumentationPlacement(DocumentationType.SYSTEM_DOCS, "orders", null,
                null, "not a slug", SourceFormat.MARKDOWN, null, null))
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("template");
    }

    /**
     * <b>A template of a name nobody implements is a slug here and nothing more.</b> Whether it exists is the
     * validation's question - see `StructureValidation` - because an upload naming an unknown template is
     * accepted today, and this record must not start refusing one.
     */
    @Test
    void anUnknownTemplateIsAcceptedHere() {
        assertThatCode(() -> new DocumentationPlacement(DocumentationType.SYSTEM_DOCS, "orders", null, null,
                "arc24", SourceFormat.MARKDOWN, null, null)).doesNotThrowAnyException();
    }

    @Test
    void theTypeAndTheSourceFormatAreRequired() {
        assertThatThrownBy(() -> new DocumentationPlacement(null, "orders", null, null, "arc42",
                SourceFormat.MARKDOWN, null, null))
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("type");
        assertThatThrownBy(() -> new DocumentationPlacement(DocumentationType.SYSTEM_DOCS, "orders", null,
                null, "arc42", null, null, null))
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("source-format");
    }

    /** The descriptor answers with the same placement it was checked against. */
    @Test
    void aDescriptorAnswersItsOwnPlacement() {
        DocumentationUploadDescriptor descriptor = DocumentationUploadDescriptor.builder()
                .type(DocumentationType.COMPONENT_DOCS)
                .system("orders")
                .component("orders-intake")
                .template("arc42")
                .sourceFormat(SourceFormat.MARKDOWN)
                .version("1.2.3")
                .sourceRepository("git@example.ch:orders/orders-intake.git")
                .sourceRevision("0123456789abcdef")
                .sourceRef("refs/heads/main")
                .sourceTimestamp(java.time.Instant.parse("2026-09-07T06:00:00Z"))
                .build();

        assertThat(descriptor.placement()).isEqualTo(new DocumentationPlacement(
                DocumentationType.COMPONENT_DOCS, "orders", "orders-intake", null, "arc42",
                SourceFormat.MARKDOWN, null, null));
        assertThat(descriptor.subjectName()).isEqualTo("orders-intake");
    }
}
