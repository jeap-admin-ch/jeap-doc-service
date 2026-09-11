package ch.admin.bit.jeap.doc.objectstorage;

import ch.admin.bit.jeap.doc.domain.port.BundleLimits;
import ch.admin.bit.jeap.doc.domain.port.UploadedBundles;
import ch.admin.bit.jeap.doc.domain.upload.InvalidUploadException;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * An uploaded bundle on a temporary file, listed but not unpacked.
 * <p>
 * The list of entries comes out of the archive's central directory, which lies at the end of the file and
 * names every entry with the size it unpacks to. So the whole structure of a set is known after one seek,
 * and <b>not one entry is decompressed</b> - which is what lets the upload API answer before it has stored
 * anything.
 */
@Slf4j
record SpooledBundle(Path file, List<String> paths, long declaredUnpackedSize, String sha256, long sizeInBytes)
        implements UploadedBundles.ReceivedBundle {

    /**
     * Reads the entry names and their declared sizes, refusing an archive that holds more than the limits
     * allow.
     * <p>
     * The declared sizes are the uploader's to state, so this is the cheap check and not the true one: what a
     * set really unpacks to is only known while it is being written.
     */
    static SpooledBundle of(Path file, String sha256, long sizeInBytes, BundleLimits limits) {
        List<String> paths = new ArrayList<>();
        long declared = 0;
        try (ZipFile archive = new ZipFile(file.toFile())) {
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                if (paths.size() == limits.maxPaths()) {
                    throw InvalidUploadException.tooManyPaths(limits.maxPaths());
                }
                paths.add(entry.getName());
                // A size of -1 is an archive that does not say. It is counted as nothing here, and the bytes
                // are counted for real when a build writes them.
                declared += Math.max(entry.getSize(), 0);
                if (declared > limits.maxUnpackedSize()) {
                    throw unpacksToTooMuch(declared, limits.maxUnpackedSize());
                }
            }
        } catch (ZipException e) {
            throw new InvalidUploadException(InvalidUploadException.Code.INVALID_BUNDLE,
                    "The uploaded bundle is not a readable ZIP archive.", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new SpooledBundle(file, List.copyOf(paths), declared, sha256, sizeInBytes);
    }

    private static InvalidUploadException unpacksToTooMuch(long declared, long allowed) {
        return new InvalidUploadException(InvalidUploadException.Code.UNPACKS_TO_TOO_MUCH,
                ("The uploaded bundle says its files unpack to more than %d bytes, and %d is the most a "
                 + "documentation set may be.").formatted(declared, allowed));
    }

    /**
     * The first bytes of one file. Opened and closed per call: this reads a handful of pages once, when a set
     * has already been accepted, and a bundle held open would outlive the request.
     */
    @Override
    public byte[] head(String path, int maxBytes) {
        try (ZipFile archive = new ZipFile(file.toFile())) {
            ZipEntry entry = archive.getEntry(path);
            if (entry == null) {
                return new byte[0];
            }
            try (InputStream in = archive.getInputStream(entry)) {
                return in.readNBytes(maxBytes);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete the temporary file {} of an upload.", file, e);
        }
    }
}
