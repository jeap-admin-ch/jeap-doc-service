package ch.admin.bit.jeap.doc.objectstorage;

import ch.admin.bit.jeap.doc.domain.upload.InvalidUploadException;
import ch.admin.bit.jeap.doc.domain.port.StoredBundle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import ch.admin.bit.jeap.doc.domain.port.BundleLimits;
import ch.admin.bit.jeap.doc.domain.port.UploadedBundles;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The storage adapter against a real S3-compatible object storage, and against nothing else.
 */
class DocumentationBundleStorageIT extends RustFsTestContainerBase {

    /** A real archive, because the receive reads the entry list and refuses anything it cannot open. */
    private static final byte[] BUNDLE = archiveOf("# the documentation of a component");

    private static final BundleLimits GENEROUS = new BundleLimits(200, 200L * 1024 * 1024);

    private DocObjectStorageProperties properties;
    private S3DocumentationBundleStorage storage;

    @BeforeEach
    void setUp() {
        properties = new DocObjectStorageProperties();
        properties.setBucket(TEST_BUCKET_NAME);
        storage = new S3DocumentationBundleStorage(S3_CLIENT, properties);
    }

    /** What an upload does: read the bundle, then store what was read. */
    private StoredBundle store(long uploadId, int attempt, byte[] bundle, long announced) {
        try (UploadedBundles.ReceivedBundle received =
                     storage.receive(new ByteArrayInputStream(bundle), announced, GENEROUS)) {
            return storage.store(uploadId, attempt, received);
        }
    }

    private static byte[] archiveOf(String page) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("1-intro/goals.md"));
            zip.write(page.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void store_thenUnderTheConfiguredPrefixAndTheIdOfTheUpload() {
        StoredBundle stored = store(42, 1, BUNDLE, BUNDLE.length);

        assertThat(stored.objectKey()).isEqualTo("uploads/docs/42/1/bundle.zip");
        assertThat(read(stored.objectKey())).isEqualTo(BUNDLE);
    }

    @Test
    void store_whenThePrefixIsConfigured_thenUsed() {
        properties.setUploadPrefix("/incoming/");

        assertThat(store(43, 1, BUNDLE, BUNDLE.length).objectKey())
                .isEqualTo("incoming/docs/43/1/bundle.zip");
    }

    /**
     * Every attempt writes its own object. An attempt that was taken over as abandoned is not dead, only slow -
     * it will finish and write, and it may not replace the bundle of the attempt that took the upload over.
     */
    @Test
    void store_whenASecondAttemptStoresTheSameUpload_thenEachAttemptHasItsOwnObject() {
        byte[] second = archiveOf("# a second attempt of the same upload");

        StoredBundle first = store(44, 1, BUNDLE, BUNDLE.length);
        StoredBundle again = store(44, 2, second, second.length);

        assertThat(again.objectKey()).isNotEqualTo(first.objectKey());
        assertThat(read(first.objectKey())).isEqualTo(BUNDLE);
        assertThat(read(again.objectKey())).isEqualTo(second);
        assertThat(again.sha256()).isNotEqualTo(first.sha256());
    }

    @Test
    void store_whenTheBundleIsLarge_thenStoredByteForByte() {
        // Random bytes do not compress, so the archive is about as large as what went into it.
        byte[] payload = new byte[8 * 1024 * 1024];
        new Random(42).nextBytes(payload);
        byte[] large = archiveOf(new String(payload, StandardCharsets.ISO_8859_1));

        StoredBundle stored = store(45, 1, large, large.length);

        assertThat(read(stored.objectKey())).isEqualTo(large);
        assertThat(stored.sha256()).isEqualTo(sha256Of(large));
    }

    /**
     * A body that is shorter than announced has to be caught before an object exists - the AWS SDK would
     * otherwise store exactly the announced number of bytes and publish a truncated bundle.
     */
    @Test
    void store_whenTheBundleIsShorterThanAnnounced_thenRejectedWithoutStoringAnything() {
        assertThatThrownBy(() -> store(46, 1, BUNDLE, BUNDLE.length + 100))
                .isInstanceOfSatisfying(InvalidUploadException.class,
                        e -> assertThat(e.getCode()).isEqualTo(InvalidUploadException.Code.CONTENT_LENGTH_MISMATCH));

        assertThat(objectsUnder("uploads/docs/46/")).isEmpty();
    }

    /**
     * Spooling to the configured directory, and to no other: a directory that does not exist cannot be spooled
     * to, which is what proves the configured one is used.
     */
    @Test
    void store_whenASpoolDirectoryIsConfigured_thenTheBundleIsSpooledThere(@TempDir Path spoolDirectory) {
        properties.setSpoolDirectory(spoolDirectory);

        StoredBundle stored = store(48, 1, BUNDLE, BUNDLE.length);

        assertThat(read(stored.objectKey())).isEqualTo(BUNDLE);
        assertThat(spoolDirectory).isEmptyDirectory();

        properties.setSpoolDirectory(spoolDirectory.resolve("not-there"));
        assertThatThrownBy(() -> store(49, 1, BUNDLE, BUNDLE.length))
                .isInstanceOf(UncheckedIOException.class);
    }

    /**
     * The digest is of the bytes that were stored, so it can be held against what a pipeline sent - and against
     * what another attempt of the same upload sent.
     */
    @Test
    void store_thenTheDigestIsTheOneOfTheStoredBytes() {
        StoredBundle stored = store(50, 1, BUNDLE, BUNDLE.length);

        assertThat(stored.sha256()).isEqualTo(sha256Of(BUNDLE)).isEqualTo(sha256Of(read(stored.objectKey())));
        assertThat(stored.sha256()).hasSize(64).isLowerCase();
    }

    /**
     * The tag is what a lifecycle rule of the bucket selects on to expire the incoming documentation - the
     * configured prefix would not do, because every instance may choose its own.
     */
    @Test
    void store_thenTheBundleIsTaggedAsAnUpload() {
        StoredBundle stored = store(51, 1, BUNDLE, BUNDLE.length);

        assertThat(S3_CLIENT.getObjectTagging(request -> request.bucket(TEST_BUCKET_NAME).key(stored.objectKey()))
                .tagSet())
                .singleElement()
                .satisfies(tag -> {
                    assertThat(tag.key()).isEqualTo(S3DocumentationBundleStorage.CONTENT_TAG_KEY);
                    assertThat(tag.value()).isEqualTo(S3DocumentationBundleStorage.CONTENT_TAG_VALUE);
                });
    }

    @Test
    void store_thenNoTemporaryFileIsLeftBehind() throws IOException {
        List<Path> before = temporaryFiles();

        store(47, 1, BUNDLE, BUNDLE.length);

        assertThat(temporaryFiles()).containsExactlyInAnyOrderElementsOf(before);
    }

    private static String sha256Of(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Path> temporaryFiles() throws IOException {
        try (var files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(file -> file.getFileName().toString().startsWith("jeap-doc-upload-")).toList();
        }
    }

    private static byte[] read(String objectKey) {
        ResponseBytes<GetObjectResponse> object = S3_CLIENT.getObject(
                GetObjectRequest.builder().bucket(TEST_BUCKET_NAME).key(objectKey).build(),
                ResponseTransformer.toBytes());
        return object.asByteArray();
    }

    private static List<String> objectsUnder(String prefix) {
        return S3_CLIENT.listObjectsV2(request -> request.bucket(TEST_BUCKET_NAME).prefix(prefix))
                .contents().stream()
                .map(software.amazon.awssdk.services.s3.model.S3Object::key)
                .toList();
    }
}
