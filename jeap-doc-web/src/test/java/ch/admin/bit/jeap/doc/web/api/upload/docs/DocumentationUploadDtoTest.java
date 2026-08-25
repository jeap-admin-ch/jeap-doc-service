package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.domain.DocumentationType;
import ch.admin.bit.jeap.doc.domain.DocumentationUploadDescriptor;
import ch.admin.bit.jeap.doc.domain.InvalidUploadException;
import ch.admin.bit.jeap.doc.domain.SourceFormat;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the request carries and what the domain gets out of it - the rules about which values may appear
 * together are tested on the descriptor itself, in the domain.
 */
class DocumentationUploadDtoTest {

    @Test
    void fromParameterValue_whenTypeIsUnknown_thenRejectedNamingTheAcceptedValues() {
        assertThatThrownBy(() -> DocumentationTypeDto.fromParameterValue("service-docs"))
                .isInstanceOfSatisfying(InvalidUploadException.class,
                        e -> assertThat(e.getCode()).isEqualTo(InvalidUploadException.Code.INVALID_PARAMETER_VALUE))
                .hasMessageContaining("component-docs");
    }

    @Test
    void fromParameterValue_whenSourceFormatIsUnknown_thenRejectedNamingTheAcceptedValues() {
        assertThatThrownBy(() -> SourceFormatDto.fromParameterValue("asciidoc"))
                .isInstanceOfSatisfying(InvalidUploadException.class,
                        e -> assertThat(e.getCode()).isEqualTo(InvalidUploadException.Code.INVALID_PARAMETER_VALUE))
                .hasMessageContaining("markdown");
    }

    @Test
    void toDescriptor_thenCarriesTheParametersAsTheDomainTypes() {
        DocumentationUploadDescriptor descriptor = DocumentationUploadDto.builder()
                .type(DocumentationTypeDto.COMPONENT_DOCS)
                .system("wvs")
                .component("foo-bar-scs")
                .version("1.4.0")
                .template("arc42")
                .sourceFormat(SourceFormatDto.MARKDOWN)
                .sourceRepository("ssh://git@bitbucket.example.ch/wvs/foo-bar-scs.git")
                .sourceRevision("9a1c2f8")
                .sourceRef("main")
                .sourceTimestamp(OffsetDateTime.parse("2026-08-21T09:12:00+02:00"))
                .build()
                .toDescriptor();

        assertThat(descriptor.type()).isEqualTo(DocumentationType.COMPONENT_DOCS);
        assertThat(descriptor.sourceFormat()).isEqualTo(SourceFormat.MARKDOWN);
        assertThat(descriptor.component()).isEqualTo("foo-bar-scs");
        assertThat(descriptor.site()).isEqualTo("default");
        assertThat(descriptor.subjectName()).isEqualTo("foo-bar-scs");
    }
}
