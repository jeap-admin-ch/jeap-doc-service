package ch.admin.bit.jeap.doc.objectstorage;

import ch.admin.bit.jeap.doc.domain.custom.CustomSet;
import ch.admin.bit.jeap.doc.domain.custom.CustomSetKey;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationStorage;
import ch.admin.bit.jeap.doc.domain.port.StoredBundle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.TaggingDirective;
import software.amazon.awssdk.services.s3.model.Tagging;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The documentation sets currently being published, in the S3 bucket of the doc service.
 * <p>
 * A set becomes current by a <b>server-side copy</b> of the bundle the upload stored: the bytes never come
 * back through this service, so the object holds exactly what the pipeline sent and carries the digest it was
 * told. The key is built here and reported back, so nothing outside this adapter has to know how one looks.
 */
@Slf4j
@RequiredArgsConstructor
class S3CustomDocumentationStorage implements CustomDocumentationStorage {

    /**
     * The tag every set carries. Its own value, because a lifecycle rule that expires the uploads must not
     * reach the sets: an upload's bundle is a staging copy, a set is the only copy there is.
     */
    static final String CONTENT_TAG_VALUE = "current";

    private static final String DOCS_SEGMENT = "docs";
    private static final String BUNDLE_NAME = "bundle.zip";

    private final S3Client s3Client;
    private final DocObjectStorageProperties properties;

    @Override
    public String promote(StoredBundle stored, CustomSetKey key, long revision, int attempt) {
        String objectKey = objectKey(key, revision, attempt);
        s3Client.copyObject(CopyObjectRequest.builder()
                .sourceBucket(properties.getBucket())
                .sourceKey(stored.objectKey())
                .destinationBucket(properties.getBucket())
                .destinationKey(objectKey)
                // The source is tagged as an upload, and this object must not be reached by the lifecycle
                // rule that expires those - so it starts with no tags at all rather than the source's.
                .taggingDirective(TaggingDirective.REPLACE)
                .build());
        // Tagged in a request of its own. A tag on the copy itself is not honoured by every S3
        // implementation, and an object under this prefix that is tagged as an upload would be expired.
        s3Client.putObjectTagging(PutObjectTaggingRequest.builder()
                .bucket(properties.getBucket())
                .key(objectKey)
                .tagging(Tagging.builder().tagSet(Tag.builder()
                        .key(S3DocumentationBundleStorage.CONTENT_TAG_KEY)
                        .value(CONTENT_TAG_VALUE)
                        .build()).build())
                .build());
        log.debug("The bundle {} is the current documentation of {} as {}.", stored.objectKey(), key, objectKey);
        return objectKey;
    }

    @Override
    public Optional<CustomDocumentationStorage.OpenedBundle> open(CustomSet set) {
        return spool(set).map(SpooledSetBundle::new);
    }

    @Override
    public void delete(String objectKey) {
        // Not guarded against an object that is not there: DeleteObject is idempotent and answers 204 either
        // way, so there is no second outcome to tell apart.
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(objectKey)
                .build());
    }

    @Override
    public List<String> listWrittenBefore(Instant writtenBefore) {
        List<String> keys = new ArrayList<>();
        // Exactly what this adapter writes, and not everything under the prefix. The sweep deletes what no
        // row names, and a further kind of uploaded thing gets a segment of its own beside this one - which
        // this must not reach into and remove on behalf of rows it does not read.
        String prefix = prefix() + "/" + DOCS_SEGMENT + "/";
        String token = null;
        do {
            var response = s3Client.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(properties.getBucket())
                    .prefix(prefix)
                    .continuationToken(token)
                    .build());
            response.contents().stream()
                    .filter(object -> object.lastModified().isBefore(writtenBefore))
                    .map(S3Object::key)
                    .forEach(keys::add);
            token = response.nextContinuationToken();
        } while (token != null);
        return keys;
    }

    /**
     * The key of a set: the configured prefix, the kind of documentation, what it documents, and the upload
     * and attempt it came from.
     * <p>
     * <b>The revision is in the key on purpose.</b> A set that is replaced is a different object rather than
     * the same one overwritten, so a row always names an object whose content it describes - and a build that
     * reads the rows and then fetches the object cannot see a set that is half replaced.
     * <p>
     * <b>And the attempt with it.</b> Two attempts of one upload carry the same revision, and an attempt that
     * was given up on keeps running: without the attempt it would copy its older bytes over the object the
     * attempt that took over made current, and the row's digest would stop describing the object it names.
     */
    private String objectKey(CustomSetKey key, long revision, int attempt) {
        return String.join("/", prefix(), DOCS_SEGMENT, key.site(), key.kind().name().toLowerCase(),
                key.system(), key.name() == null ? "-" : key.name(),
                key.sourceFormat().name().toLowerCase(), key.template(),
                key.location() == null ? "-" : key.location() + "-" + key.topic(),
                Long.toString(revision), Integer.toString(attempt), BUNDLE_NAME);
    }

    private String prefix() {
        return properties.getCurrentPrefix().replaceAll("^/+|/+$", "");
    }

    /**
     * The set's bundle on a temporary file. A build reads several files out of one archive, and a stream that
     * has to be re-opened per file would be one request per page.
     */
    private Optional<Path> spool(CustomSet set) {
        Path spooled;
        try {
            spooled = Files.createTempFile(properties.spoolDirectoryOrDefault(), "jeap-doc-set-", ".zip");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // Written through the file the temporary file already is, so it keeps the mode createTempFile gave it:
        // Files.copy would replace the file and recreate it under the umask, and an unreleased team's
        // documentation would be readable by every process on the task.
        try (var body = s3Client.getObject(GetObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(set.objectKey())
                .build());
             OutputStream file = Files.newOutputStream(spooled)) {
            body.transferTo(file);
        } catch (NoSuchKeyException e) {
            // The row named it and the object is gone: a removal or a replacement took it between this
            // build reading the rows and opening the bundle. Both have asked for the build that publishes
            // what is true now.
            delete(spooled);
            log.warn("The documentation of {} is recorded and its bundle {} is gone. That set is left out of "
                     + "this build; the removal or upload that took it has asked for another one.",
                    set.subject().slug(), set.objectKey());
            return Optional.empty();
        } catch (IOException e) {
            // The object storage failing and the disk failing both leave a part of a file behind, and every
            // build of every part would leave another one in a directory sized for the bundles being read.
            delete(spooled);
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            delete(spooled);
            throw e;
        }
        return Optional.of(spooled);
    }

    private static void delete(Path spooled) {
        try {
            Files.deleteIfExists(spooled);
        } catch (IOException e) {
            log.warn("Could not delete the temporary file {} of a documentation set.", spooled, e);
        }
    }
}
