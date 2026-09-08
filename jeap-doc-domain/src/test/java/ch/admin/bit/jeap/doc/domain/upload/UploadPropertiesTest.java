package ch.admin.bit.jeap.doc.domain.upload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A misconfigured instance has to fail while it starts, not on its first validation an hour later.
 */
class UploadPropertiesTest {

    @Test
    void theDefaultsAreTheOnesTheApiDocuments() {
        UploadProperties properties = new UploadProperties();

        assertThat(properties.getValidation().getMaxPaths()).isEqualTo(10_000);
        assertThat(properties.getValidation().getMaxFindings()).isEqualTo(50);
        assertThatCode(properties::check).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void aValidationThatMayCarryNoPath_stopsTheStartup(int paths) {
        UploadProperties properties = new UploadProperties();
        properties.getValidation().setMaxPaths(paths);

        assertThatThrownBy(properties::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jeap.doc.upload.validation.max-paths");
    }

    /** A report that may carry no finding could not say what is wrong, which is a typo rather than a choice. */
    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void aReportThatMayCarryNoFinding_stopsTheStartup(int findings) {
        UploadProperties properties = new UploadProperties();
        properties.getValidation().setMaxFindings(findings);

        assertThatThrownBy(properties::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jeap.doc.upload.validation.max-findings");
    }
}
