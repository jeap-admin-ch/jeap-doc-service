package ch.admin.bit.jeap.doc.domain.custom;

import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import ch.admin.bit.jeap.doc.domain.upload.UploadProperties;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomPropertiesTest {

    @Test
    void theDefaultIsTheOneTheDocumentationNames() {
        CustomProperties properties = new CustomProperties();

        assertThat(properties.getMaxUnpackedSize()).isEqualTo(DataSize.ofMegabytes(200));
        assertThat(properties.getRefusedExtensions()).contains("exe", "sh", "jar").doesNotContain("md");
        assertThatCode(properties::check).doesNotThrowAnyException();
    }

    @Test
    void aSetThatMayUnpackToNothing_stopsTheStartup() {
        CustomProperties properties = new CustomProperties();
        properties.setMaxUnpackedSize(DataSize.ofBytes(0));

        assertThatThrownBy(properties::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-unpacked-size");
    }

    @Test
    void theLimits_takeThePathCountFromTheUploadProperties() {
        CustomProperties properties = new CustomProperties();
        UploadProperties uploads = new UploadProperties();

        assertThat(properties.limitsWith(uploads, SourceFormat.MARKDOWN).maxPaths()).isEqualTo(200);
        assertThat(properties.limitsWith(uploads, SourceFormat.HTML).maxPaths())
                .describedAs("a microsite is a built site, not a chapter of pages").isEqualTo(5_000);
        assertThat(properties.limitsWith(uploads, SourceFormat.MARKDOWN).maxUnpackedSize())
                .isEqualTo(DataSize.ofMegabytes(200).toBytes());
    }
}
