package ch.admin.bit.jeap.doc.objectstorage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A misconfigured instance has to fail while it starts, not on the first upload of a microsite. */
class DocObjectStoragePropertiesTest {

    @Test
    void theDefaultsAreAccepted() {
        assertThatCode(new DocObjectStorageProperties()::check).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void aMicrositeNothingWouldWrite_stopsTheStartup(int threads) {
        DocObjectStorageProperties properties = new DocObjectStorageProperties();
        properties.setMicrositeConcurrency(threads);

        assertThatThrownBy(properties::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jeap.doc.storage.microsite-concurrency");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void aSiteNothingWouldPublish_stopsTheStartup(int threads) {
        DocObjectStorageProperties properties = new DocObjectStorageProperties();
        properties.setPublicationConcurrency(threads);

        assertThatThrownBy(properties::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jeap.doc.storage.publication-concurrency");
    }
}
