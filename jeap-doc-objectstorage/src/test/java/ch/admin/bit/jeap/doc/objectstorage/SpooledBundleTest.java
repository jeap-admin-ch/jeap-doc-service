package ch.admin.bit.jeap.doc.objectstorage;

import ch.admin.bit.jeap.doc.domain.port.BundleLimits;
import ch.admin.bit.jeap.doc.domain.upload.InvalidUploadException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading what a bundle holds without unpacking it. No bucket is involved: the archive is on a file by the
 * time anything asks.
 */
class SpooledBundleTest {

    private static final BundleLimits GENEROUS = new BundleLimits(200, 200L * 1024 * 1024);
    private static final String SHA256 =
            "6b86b273ff34fce19d6b804eff5a3f5747ada4eaa22f1d49c01e52ddb7875b4b";

    @TempDir
    Path directory;

    private Path archiveOf(String... paths) throws IOException {
        Path file = directory.resolve("bundle-" + paths.length + ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (String path : paths) {
                zip.putNextEntry(new ZipEntry(path));
                zip.write(("# " + path).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }

    @Test
    void paths_areTheFilesOfTheArchive() throws IOException {
        Path file = archiveOf("1-intro/goals.md", "2-constraints/given.md");

        var bundle = SpooledBundle.of(file, SHA256, Files.size(file), GENEROUS);

        assertThat(bundle.paths()).containsExactly("1-intro/goals.md", "2-constraints/given.md");
        assertThat(bundle.sha256()).isEqualTo(SHA256);
        assertThat(bundle.declaredUnpackedSize()).isPositive();
    }

    @Test
    void aDirectoryEntry_isNotAFile() throws IOException {
        Path file = directory.resolve("with-folders.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("1-intro/"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("1-intro/goals.md"));
            zip.write("# goals".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        assertThat(SpooledBundle.of(file, SHA256, Files.size(file), GENEROUS).paths())
                .containsExactly("1-intro/goals.md");
    }

    @Test
    void tooManyPaths_isRefusedWhileReading() throws IOException {
        Path file = archiveOf("1-intro/a.md", "1-intro/b.md", "1-intro/c.md");

        long size = Files.size(file);
        BundleLimits limits = new BundleLimits(2, 1 << 20);
        assertThatThrownBy(() -> SpooledBundle.of(file, SHA256, size, limits))
                .isInstanceOf(InvalidUploadException.class)
                .hasFieldOrPropertyWithValue("code", InvalidUploadException.Code.TOO_MANY_PATHS);
    }

    @Test
    void anArchiveThatSaysItUnpacksToTooMuch_isRefusedWithoutReadingAnEntry() throws IOException {
        Path file = archiveOf("1-intro/goals.md");

        long size = Files.size(file);
        BundleLimits limits = new BundleLimits(200, 1);
        assertThatThrownBy(() -> SpooledBundle.of(file, SHA256, size, limits))
                .isInstanceOf(InvalidUploadException.class)
                .hasFieldOrPropertyWithValue("code", InvalidUploadException.Code.UNPACKS_TO_TOO_MUCH)
                .hasMessageContaining("unpack")
                // The message is answered to the uploading pipeline, so the numbers in it have to be numbers.
                .hasMessageContaining("and 1 is the most")
                .hasMessageNotContaining("%d");
    }

    @Test
    void aBundleThatIsNotAnArchive_isRefused() throws IOException {
        Path file = directory.resolve("not-a-zip.zip");
        Files.writeString(file, "this is not a ZIP at all", StandardCharsets.UTF_8);

        long size = Files.size(file);
        assertThatThrownBy(() -> SpooledBundle.of(file, SHA256, size, GENEROUS))
                .isInstanceOf(InvalidUploadException.class)
                .hasFieldOrPropertyWithValue("code", InvalidUploadException.Code.INVALID_BUNDLE);
    }

    @Test
    void close_removesTheFile() throws IOException {
        Path file = archiveOf("1-intro/goals.md");

        var bundle = SpooledBundle.of(file, SHA256, Files.size(file), GENEROUS);
        bundle.close();

        assertThat(file).doesNotExist();
    }

    @Test
    void anArchiveOfNothing_holdsNoPath() throws IOException {
        Path file = directory.resolve("empty.zip");
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.finish();
        }

        assertThat(SpooledBundle.of(file, SHA256, Files.size(file), GENEROUS).paths()).isEmpty();
    }
}
