package ch.admin.bit.jeap.doc.objectstorage;

import ch.admin.bit.jeap.doc.domain.custom.CustomProvenance;
import ch.admin.bit.jeap.doc.domain.custom.CustomSet;
import ch.admin.bit.jeap.doc.domain.custom.CustomSetKey;
import ch.admin.bit.jeap.doc.domain.port.BundleLimits;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationStorage;
import ch.admin.bit.jeap.doc.domain.port.UploadedBundles;
import ch.admin.bit.jeap.doc.domain.port.StoredBundle;
import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The current documentation against a real S3-compatible object storage.
 */
class S3CustomDocumentationStorageIT extends RustFsTestContainerBase {

    private static final Instant NOW = Instant.parse("2026-09-11T08:00:00Z");
    private static final String PAGE = "1-intro/goals.md";
    private static final String PAGE_TEXT = "# Goals\n\nWhat this system is for.\n";

    private DocObjectStorageProperties properties;
    private S3CustomDocumentationStorage storage;
    private S3DocumentationBundleStorage uploads;

    @TempDir
    Path spool;

    @BeforeEach
    void setUp() {
        properties = new DocObjectStorageProperties();
        properties.setBucket(TEST_BUCKET_NAME);
        properties.setUploadPrefix("uploads");
        properties.setCurrentPrefix("current");
        properties.setSpoolDirectory(spool);
        storage = new S3CustomDocumentationStorage(S3_CLIENT, properties);
        uploads = new S3DocumentationBundleStorage(S3_CLIENT, properties);
    }

    private static CustomSetKey key() {
        return new CustomSetKey("default", SubjectKind.SYSTEM, "orders", null, SourceFormat.MARKDOWN, "arc42",
                null, null);
    }

    private static byte[] bundle() {
        try (var bytes = new java.io.ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(PAGE));
            zip.write(PAGE_TEXT.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Stores a bundle the way an upload does, which is what a promotion copies. */
    private StoredBundle upload(long uploadId) {
        byte[] bytes = bundle();
        try (UploadedBundles.ReceivedBundle received = uploads.receive(new ByteArrayInputStream(bytes), bytes.length,
                new BundleLimits(200, 1 << 20))) {
            return uploads.store(uploadId, 1, received);
        }
    }

    @Test
    void promote_copiesTheBundleWithoutReadingItBack() {
        StoredBundle stored = upload(11);

        String objectKey = storage.promote(stored, key(), 11, 1);

        assertThat(objectKey).startsWith("current/docs/default/system/orders/");
        assertThat(objectKey)
                .describedAs("the upload and the attempt are in the key, so a replaced set is a new object "
                             + "and an attempt that was given up on cannot overwrite what took over")
                .contains("/11/1/bundle.zip");
        assertThat(storage.listWrittenBefore(Instant.now().plusSeconds(60))).contains(objectKey);
    }

    @Test
    void aPromotedSet_isTaggedAsCurrentRatherThanAsAnUpload() {
        StoredBundle stored = upload(12);

        String objectKey = storage.promote(stored, key(), 12, 1);

        List<String> values = S3_CLIENT.getObjectTagging(GetObjectTaggingRequest.builder()
                        .bucket(TEST_BUCKET_NAME).key(objectKey).build())
                .tagSet().stream().map(tag -> tag.value()).toList();
        assertThat(values).describedAs("the rule that expires the uploads must not reach a set")
                .containsExactly(S3CustomDocumentationStorage.CONTENT_TAG_VALUE);
    }

    @Test
    void twoRevisionsOfOneSet_areTwoObjects() {
        String first = storage.promote(upload(13), key(), 13, 1);
        String second = storage.promote(upload(14), key(), 14, 1);

        assertThat(first).isNotEqualTo(second);
        assertThat(storage.listWrittenBefore(Instant.now().plusSeconds(60))).contains(first, second);
    }

    @Test
    void open_readsThePagesOfASetBack() throws IOException {
        StoredBundle stored = upload(15);
        String objectKey = storage.promote(stored, key(), 15, 1);

        try (CustomDocumentationStorage.OpenedBundle opened =
                     storage.open(setAt(objectKey, stored)).orElseThrow()) {
            Optional<InputStream> page = opened.read(PAGE);

            assertThat(page).isPresent();
            assertThat(new String(page.orElseThrow().readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo(PAGE_TEXT);
            assertThat(opened.read("1-intro/nothing-wrote-this.md"))
                    .describedAs("a page the rows name and the archive does not is left out")
                    .isEmpty();
        }
    }

    @Test
    void listWrittenBefore_leavesOutWhatWasJustWritten() {
        String objectKey = storage.promote(upload(17), key(), 17, 1);

        assertThat(storage.listWrittenBefore(Instant.now().minusSeconds(60)))
                .describedAs("an object an upload may still be committing rows for")
                .doesNotContain(objectKey);
    }

    @Test
    void delete_takesTheObject() {
        String objectKey = storage.promote(upload(16), key(), 16, 1);

        storage.delete(objectKey);

        assertThat(storage.listWrittenBefore(Instant.now().plusSeconds(60))).doesNotContain(objectKey);
    }

    /** Object storage deletes are idempotent, so removing what is already gone is not a second outcome. */
    @Test
    void delete_whenTheObjectIsAlreadyGone_thenItIsStillNotAFailure() {
        String objectKey = storage.promote(upload(18), key(), 18, 1);
        storage.delete(objectKey);

        assertThatCode(() -> storage.delete(objectKey)).doesNotThrowAnyException();
    }

    /**
     * <b>Two attempts of one upload are two objects.</b> An attempt slower than the in-progress timeout is
     * given up on and keeps running: sharing a key with the attempt that took over, it would copy its older
     * bytes over what is current, and the row's digest would stop describing the object it names.
     */
    @Test
    void twoAttemptsOfOneUpload_areTwoObjects() {
        String first = storage.promote(upload(19), key(), 19, 1);
        String second = storage.promote(upload(19), key(), 19, 2);

        assertThat(first).isNotEqualTo(second);
        assertThat(storage.listWrittenBefore(Instant.now().plusSeconds(60))).contains(first, second);
    }

    /**
     * <b>The sweep deletes what no row names, so it may only see what this adapter writes.</b> A further kind
     * of uploaded thing gets a segment of its own beside this one, and the first object stored under it would
     * be swept six hours later on behalf of rows nothing reads.
     */
    @Test
    void listWrittenBefore_leavesOutWhatAnotherKindOfUploadWrote() {
        String objectKey = storage.promote(upload(20), key(), 20, 1);
        String other = "current/some-other-kind/whatever/bundle.zip";
        S3_CLIENT.putObject(software.amazon.awssdk.services.s3.model.PutObjectRequest.builder()
                        .bucket(TEST_BUCKET_NAME).key(other).build(),
                software.amazon.awssdk.core.sync.RequestBody.fromBytes(bundle()));

        assertThat(storage.listWrittenBefore(Instant.now().plusSeconds(60)))
                .contains(objectKey)
                .doesNotContain(other);
    }

    /**
     * A set whose object is gone - swept, or never committed - must not leave a spool file behind. Every
     * failed build would otherwise leave another one in a directory sized for the bundles being read.
     */
    @Test
    void open_whenTheObjectIsGone_thenNothingIsOpenedAndNoTemporaryFileIsLeftBehind() throws IOException {
        StoredBundle stored = upload(21);
        String objectKey = storage.promote(stored, key(), 21, 1);
        storage.delete(objectKey);

        assertThat(storage.open(setAt(objectKey, stored)))
                .describedAs("a set whose object is gone costs that set, not the build of the whole part")
                .isEmpty();

        try (var spooled = Files.list(properties.spoolDirectoryOrDefault())) {
            assertThat(spooled).describedAs("the spool directory is left as it was found").isEmpty();
        }
    }

    private static CustomSet setAt(String objectKey, StoredBundle stored) {
        return new CustomSet(1L, key(), 15, objectKey, stored.sha256(), 100,
                new CustomProvenance("orders-docs", "main", "cafebabe", NOW, null, NOW), List.of());
    }
}
