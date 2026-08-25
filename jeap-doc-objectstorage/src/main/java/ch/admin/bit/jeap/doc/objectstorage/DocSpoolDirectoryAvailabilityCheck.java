package ch.admin.bit.jeap.doc.objectstorage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Checks on startup that the uploaded bundles can be spooled where the instance configured it.
 * <p>
 * A directory that does not exist or cannot be written to is a configuration error of the instance, and it would
 * otherwise surface in the first upload - the same reasoning as for {@link DocStorageBucketAvailabilityCheck}.
 */
@Slf4j
@RequiredArgsConstructor
public class DocSpoolDirectoryAvailabilityCheck implements InitializingBean {

    private final DocObjectStorageProperties properties;

    @Override
    public void afterPropertiesSet() {
        Path spoolDirectory = properties.spoolDirectoryOrDefault();
        if (!Files.isDirectory(spoolDirectory)) {
            throw new IllegalStateException(
                    "The configured spool directory '%s' does not exist, set 'jeap.doc.storage.spool-directory' to a directory the service may write to."
                            .formatted(spoolDirectory));
        }
        try {
            Files.delete(Files.createTempFile(spoolDirectory, "jeap-doc-spool-check-", ".tmp"));
        } catch (IOException e) {
            throw new IllegalStateException("The spool directory '%s' cannot be written to: %s"
                    .formatted(spoolDirectory, e.getMessage()), e);
        }
        log.info("Uploaded bundles are spooled to '{}'.", spoolDirectory);
    }
}
