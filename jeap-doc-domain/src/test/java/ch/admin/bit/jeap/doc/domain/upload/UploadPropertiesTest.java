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

        assertThat(properties.getValidation().getMaxPaths()).isEqualTo(200);
        assertThat(properties.getValidation().getMaxMicrositePaths()).isEqualTo(5_000);
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

    /**
     * A bound so high that it bounds nothing stops the startup too. The limit on the request body is derived
     * from this one, so a value above the ceiling would leave both meaningless on an endpoint every pipeline
     * can reach.
     */
    @ParameterizedTest
    @ValueSource(ints = {UploadProperties.MAX_PATHS_CEILING + 1, 100_000_000})
    void aValidationThatMayCarryAnyNumberOfPaths_stopsTheStartup(int paths) {
        UploadProperties properties = new UploadProperties();
        properties.getValidation().setMaxPaths(paths);

        assertThatThrownBy(properties::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jeap.doc.upload.validation.max-paths");
    }

    /** A microsite is bounded too, and by its own number: it is a built site rather than a set of pages. */
    @ParameterizedTest
    @ValueSource(ints = {0, -1, UploadProperties.MAX_PATHS_CEILING + 1})
    void aMicrositeBoundThatBoundsNothing_stopsTheStartup(int paths) {
        UploadProperties properties = new UploadProperties();
        properties.getValidation().setMaxMicrositePaths(paths);

        assertThatThrownBy(properties::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jeap.doc.upload.validation.max-microsite-paths");
    }

    /** The ceiling itself is a value an instance may configure. */
    @Test
    void theHighestPathCountAnInstanceMayConfigure_isAccepted() {
        UploadProperties properties = new UploadProperties();
        properties.getValidation().setMaxPaths(UploadProperties.MAX_PATHS_CEILING);

        assertThatCode(properties::check).doesNotThrowAnyException();
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
