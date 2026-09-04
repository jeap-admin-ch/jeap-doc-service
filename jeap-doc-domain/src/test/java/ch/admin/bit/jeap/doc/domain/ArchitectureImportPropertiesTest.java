package ch.admin.bit.jeap.doc.domain;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The configuration error that would otherwise be silent.
 * <p>
 * A cap of zero skips every artifact and every message schema of every environment, and it does it quietly: the
 * runs report a success, the pages simply carry no specifications. So it fails the deployment instead.
 */
class ArchitectureImportPropertiesTest {

    private final ArchitectureImportProperties properties = new ArchitectureImportProperties();

    @Test
    void check_whenTheArtifactCapIsZero_thenTheStartupFails() {
        properties.setMaxArtifactSize(DataSize.ofBytes(0));

        assertThatThrownBy(properties::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-artifact-size");
    }

    @Test
    void check_whenTheArtifactCapIsNegative_thenTheStartupFails() {
        properties.setMaxArtifactSize(DataSize.ofBytes(-1));

        assertThatThrownBy(properties::check).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void check_whenTheArtifactCapIsMissing_thenTheStartupFails() {
        properties.setMaxArtifactSize(null);

        assertThatThrownBy(properties::check).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void check_theDefaults_areLegal() {
        assertThatCode(properties::check).doesNotThrowAnyException();
    }
}
