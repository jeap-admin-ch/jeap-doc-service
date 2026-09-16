package ch.admin.bit.jeap.doc.objectstorage;

import ch.admin.bit.jeap.doc.domain.custom.CustomProvenance;
import ch.admin.bit.jeap.doc.domain.custom.CustomSet;
import ch.admin.bit.jeap.doc.domain.custom.CustomSetKey;
import ch.admin.bit.jeap.doc.domain.custom.MicrositePageText;
import ch.admin.bit.jeap.doc.domain.upload.validation.MicrositeRules;
import ch.admin.bit.jeap.doc.domain.port.BundleLimits;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationStorage;
import ch.admin.bit.jeap.doc.domain.port.UploadedBundles;
import ch.admin.bit.jeap.doc.domain.port.StoredBundle;
import ch.admin.bit.jeap.doc.domain.upload.InvalidUploadException;
import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.Tag;

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

    /** A microsite as a build writes one: an entry point, a nested asset, and a file nobody wrote. */
    private static byte[] micrositeBundle() {
        try (var bytes = new java.io.ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String[] entry : new String[][]{
                    {"index.html", "<!doctype html><title>Configuration</title>"},
                    {"assets/app.js", "console.log('hello')"},
                    {".DS_Store", "nobody wrote this"}}) {
                zip.putNextEntry(new ZipEntry(entry[0]));
                zip.write(entry[1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** The key of an HTML set: where it is embedded is part of what identifies it. */
    private static CustomSetKey htmlKey() {
        return new CustomSetKey("default", SubjectKind.COMPONENT, "orders", "orders-intake",
                SourceFormat.HTML, "arc42", "8-crosscutting-concepts", "configuration-reference");
    }

    private UploadedBundles.ReceivedBundle receivedMicrosite() {
        byte[] bytes = micrositeBundle();
        return uploads.receive(new ByteArrayInputStream(bytes), bytes.length, MICROSITE_LIMITS);
    }

    private static final BundleLimits MICROSITE_LIMITS = new BundleLimits(5000, 1 << 20);

    /**
     * <b>A microsite is files, not an archive.</b> A reader opens its entry point and the browser fetches
     * what that page names, so each file is an object of its own under one prefix.
     */
    @Test
    void promoteFiles_writesEveryFileOfTheSetUnderOnePrefix() {
        try (UploadedBundles.ReceivedBundle received = receivedMicrosite()) {
            String prefix = storage.promoteFiles(received, htmlKey(), 30, 1, MICROSITE_LIMITS);

            assertThat(prefix).describedAs("a prefix, which is what tells it from an object key")
                    .endsWith("/30/1/files/");
            assertThat(storage.listWrittenBefore(Instant.now().plusSeconds(60)))
                    .contains(prefix + "index.html", prefix + "assets/app.js")
                    .describedAs("the files nobody wrote are left out here too")
                    .doesNotContain(prefix + ".DS_Store");
        }
    }

    /** Served as itself: a browser refuses a module script it was told is an octet stream. */
    @Test
    void promoteFiles_writesEachFileAsWhatItIs() {
        try (UploadedBundles.ReceivedBundle received = receivedMicrosite()) {
            String prefix = storage.promoteFiles(received, htmlKey(), 31, 1, MICROSITE_LIMITS);

            assertThat(contentTypeOf(prefix + "index.html")).startsWith("text/html");
            assertThat(contentTypeOf(prefix + "assets/app.js")).contains("javascript");
        }
    }

    @Test
    void promoteFiles_tagsEveryFileAsCurrentRatherThanAsAnUpload() {
        try (UploadedBundles.ReceivedBundle received = receivedMicrosite()) {
            String prefix = storage.promoteFiles(received, htmlKey(), 32, 1, MICROSITE_LIMITS);

            List<String> values = S3_CLIENT.getObjectTagging(GetObjectTaggingRequest.builder()
                            .bucket(TEST_BUCKET_NAME).key(prefix + "index.html").build())
                    .tagSet().stream().map(Tag::value).toList();
            assertThat(values).describedAs("the rule that expires the uploads must not reach a microsite")
                    .containsExactly(S3CustomDocumentationStorage.CONTENT_TAG_VALUE);
        }
    }

    /**
     * <b>What a set really unpacks to is only known while it is written.</b> The sizes the archive declares
     * are the uploader's to state, so this is the check that measures.
     */
    @Test
    void promoteFiles_whenTheFilesUnpackToMoreThanAllowed_isRefused() {
        try (UploadedBundles.ReceivedBundle received = receivedMicrosite()) {
            CustomSetKey key = htmlKey();
            BundleLimits limits = new BundleLimits(5000, 8);
            assertThatThrownBy(() -> storage.promoteFiles(received, key, 33, 1, limits))
                    .isInstanceOf(InvalidUploadException.class)
                    .hasFieldOrPropertyWithValue("code",
                            InvalidUploadException.Code.UNPACKS_TO_TOO_MUCH);
        }
        assertThat(keysUnderPrefixOf(33)).describedAs("no row names a refused set, so nothing of it is left")
                .isEmpty();
    }

    /**
     * <b>One small entry may unpack to anything.</b> The limit is counted while the bytes come out of the
     * archive, so an entry past it is refused before it is put - and nothing is left behind of it.
     */
    @Test
    void promoteFiles_whenOneEntryUnpacksToMoreThanAllowed_isRefusedBeforeItIsWritten() {
        byte[] zeros = new byte[4 * 1024 * 1024];
        byte[] bytes;
        try (var out = new java.io.ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("index.html"));
            zip.write(zeros);
            zip.closeEntry();
            zip.finish();
            bytes = out.toByteArray();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        try (UploadedBundles.ReceivedBundle received = uploads.receive(new ByteArrayInputStream(bytes),
                bytes.length, new BundleLimits(5000, 64 * 1024 * 1024))) {
            assertThatThrownBy(() ->
                    storage.promoteFiles(received, htmlKey(), 36, 1, new BundleLimits(5000, 1024 * 1024)))
                    .hasFieldOrPropertyWithValue("code", InvalidUploadException.Code.UNPACKS_TO_TOO_MUCH);
        }
        assertThat(keysUnderPrefixOf(36)).isEmpty();
    }

    /** Every object of the HTML set of one revision, however far it got. */
    private List<String> keysUnderPrefixOf(long revision) {
        return storage.listWrittenBefore(Instant.now().plusSeconds(60)).stream()
                .filter(objectKey -> objectKey.contains("/" + revision + "/1/files/"))
                .toList();
    }

    /**
     * <b>The extracted text lies under the set's own prefix</b>, which is what keeps the rest true: the row
     * names the prefix, a removal takes everything below it, and the sweep counts it. Nothing about the
     * storage of a set had to learn that this file exists.
     */
    @Test
    void searchText_isWrittenUnderThePrefixAndReadBackAsItWas() {
        String prefix;
        try (UploadedBundles.ReceivedBundle received = receivedMicrosite()) {
            prefix = storage.promoteFiles(received, htmlKey(), 40, 1, MICROSITE_LIMITS);
        }
        List<MicrositePageText> pages = List.of(
                new MicrositePageText("index.html", "Configuration", "Every property of this service"),
                new MicrositePageText("pages/a.html", "A", "The first one"));

        storage.storeSearchText(prefix, pages);

        assertThat(storage.listWrittenBefore(Instant.now().plusSeconds(60)))
                .contains(prefix + MicrositeRules.SEARCH_TEXT);
        assertThat(storage.readSearchText(prefix)).isEqualTo(pages);
    }

    /** A set uploaded before the text was ever extracted costs its own content in the index, not a run. */
    @Test
    void searchText_ofASetThatHasNone_isNothing() {
        try (UploadedBundles.ReceivedBundle received = receivedMicrosite()) {
            String prefix = storage.promoteFiles(received, htmlKey(), 41, 1, MICROSITE_LIMITS);

            assertThat(storage.readSearchText(prefix)).isEmpty();
        }
    }

    /** The row names the prefix, so removing the set is removing every file under it. */
    @Test
    void delete_ofAPrefix_takesEveryFileOfTheMicrosite() {
        String prefix;
        try (UploadedBundles.ReceivedBundle received = receivedMicrosite()) {
            prefix = storage.promoteFiles(received, htmlKey(), 34, 1, MICROSITE_LIMITS);
        }

        storage.delete(prefix);

        assertThat(storage.listWrittenBefore(Instant.now().plusSeconds(60)))
                .describedAs("no file of it is left behind")
                .noneMatch(key -> key.startsWith(prefix));
    }

    private static String contentTypeOf(String key) {
        return S3_CLIENT.headObject(HeadObjectRequest.builder()
                .bucket(TEST_BUCKET_NAME).key(key).build()).contentType();
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

        assertThat(objectKey).startsWith("current/docs/default/system/orders/")
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
                .tagSet().stream().map(Tag::value).toList();
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
        return new CustomSet(1L, key(), null, 15, objectKey, stored.sha256(), 100,
                new CustomProvenance("orders-docs", "main", "cafebabe", NOW, null, NOW), List.of());
    }
}
