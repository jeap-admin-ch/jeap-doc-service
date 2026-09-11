package ch.admin.bit.jeap.doc.objectstorage;

import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationStorage;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A documentation set's bundle on a temporary file, open while a build reads its pages out of it.
 * <p>
 * Closing it removes the file and the open archive with it.
 */
@Slf4j
class SpooledSetBundle implements CustomDocumentationStorage.OpenedBundle {

    private final Path file;
    private final ZipFile archive;

    SpooledSetBundle(Path file) {
        this.file = file;
        try {
            this.archive = new ZipFile(file.toFile());
        } catch (IOException e) {
            delete(file);
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<InputStream> read(String path) {
        ZipEntry entry = archive.getEntry(path);
        if (entry == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(archive.getInputStream(entry));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        try {
            archive.close();
        } catch (IOException e) {
            log.warn("Could not close the bundle of a documentation set: {}", file, e);
        }
        delete(file);
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete the temporary file {} of a documentation set.", file, e);
        }
    }
}
