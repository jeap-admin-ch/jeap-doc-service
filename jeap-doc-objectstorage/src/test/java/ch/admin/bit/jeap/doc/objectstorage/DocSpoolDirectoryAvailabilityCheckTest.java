package ch.admin.bit.jeap.doc.objectstorage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A spool directory an instance cannot write to is a configuration error, and it should surface while the
 * service starts instead of in the first upload.
 */
class DocSpoolDirectoryAvailabilityCheckTest {

    @TempDir
    private Path spoolDirectory;

    @Test
    void afterPropertiesSet_whenTheDirectoryIsWritable_thenStarted() {
        assertThatCode(() -> check(spoolDirectory).afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    void afterPropertiesSet_whenNoDirectoryIsConfigured_thenTheTemporaryDirectoryOfTheJvmIsUsed() {
        DocObjectStorageProperties properties = new DocObjectStorageProperties();

        assertThatCode(() -> new DocSpoolDirectoryAvailabilityCheck(properties).afterPropertiesSet())
                .doesNotThrowAnyException();
        assertThatCode(() -> Files.delete(
                Files.createTempFile(properties.spoolDirectoryOrDefault(), "probe-", ".tmp")))
                .doesNotThrowAnyException();
    }

    @Test
    void afterPropertiesSet_whenTheDirectoryDoesNotExist_thenTheServiceDoesNotStart() {
        assertThatThrownBy(() -> check(spoolDirectory.resolve("not-there")).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jeap.doc.storage.spool-directory");
    }

    private static DocSpoolDirectoryAvailabilityCheck check(Path spoolDirectory) {
        DocObjectStorageProperties properties = new DocObjectStorageProperties();
        properties.setSpoolDirectory(spoolDirectory);
        return new DocSpoolDirectoryAvailabilityCheck(properties);
    }
}
