package ch.admin.bit.jeap.doc.objectstorage;

import ch.admin.bit.jeap.doc.domain.custom.CustomSet;
import ch.admin.bit.jeap.doc.domain.custom.CustomSetKey;
import ch.admin.bit.jeap.doc.domain.custom.MicrositePageText;
import ch.admin.bit.jeap.doc.domain.port.BundleLimits;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationStorage;
import ch.admin.bit.jeap.doc.domain.port.StoredBundle;
import ch.admin.bit.jeap.doc.domain.port.StoredObject;
import ch.admin.bit.jeap.doc.domain.port.UploadedBundles;
import ch.admin.bit.jeap.doc.domain.upload.InvalidUploadException;
import ch.admin.bit.jeap.doc.domain.upload.validation.IgnoredPaths;
import ch.admin.bit.jeap.doc.domain.upload.validation.MicrositeRules;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.TaggingDirective;
import software.amazon.awssdk.services.s3.model.Tagging;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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
    /** The segment the unpacked files of a microsite lie under, beside where its bundle would have. */
    private static final String FILES_SEGMENT = "files";
    /** What the extracted text of a microsite is: a tab separated line per page. */
    private static final String SEARCH_TEXT_CONTENT_TYPE = "text/tab-separated-values; charset=utf-8";
    /** How much of an entry is read at a time while it is counted against the limit. */
    private static final int SPOOL_BUFFER_BYTES = 64 * 1024;
    /** How many keys a delete request carries; the S3 API takes a thousand. */
    private static final int DELETE_BATCH_SIZE = 1000;

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

    /**
     * Writes every file of a microsite as an object of its own, several at a time.
     * <p>
     * A built site is hundreds of small files, so this is bound by round trips rather than bandwidth - the
     * same reason a published site is written concurrently, and bounded for the same one: asking the S3
     * client for more connections than its pool holds only moves the queue.
     * <p>
     * <b>Each entry is spooled to a file before it is put.</b> A ZIP entry may declare no size, the SDK has
     * to be able to re-read a body when it retries, and reading an entry into the heap would undo the
     * spooling the upload path exists for.
     * <p>
     * <b>The limit is counted while the bytes come out of the archive</b>, across every writer, and the first
     * one past it stops them all. The sizes an archive declares are the uploader's to state, so one small
     * entry may unpack to anything - and counted after it was spooled and put, it would already be on the
     * disk and in the bucket. What a refused set did write is removed again: no row names it.
     */
    @Override
    public String promoteFiles(UploadedBundles.ReceivedBundle received, CustomSetKey key, long revision,
                               int attempt, BundleLimits limits) {
        String prefix = filesPrefix(key, revision, attempt);
        List<String> paths = received.paths().stream().filter(path -> !IgnoredPaths.isIgnored(path)).toList();
        AtomicLong unpacked = new AtomicLong();
        AtomicBoolean refused = new AtomicBoolean();
        try (ZipFile archive = new ZipFile(((SpooledBundle) received).file().toFile());
             ExecutorService writers = Executors.newFixedThreadPool(
                     Math.min(properties.getMicrositeConcurrency(), Math.max(paths.size(), 1)),
                     runnable -> {
                         Thread thread = new Thread(runnable, "microsite-publication");
                         thread.setDaemon(true);
                         return thread;
                     })) {
            List<Future<Void>> pending = paths.stream()
                    .map(path -> writers.submit((Callable<Void>) () -> {
                        if (refused.get()) {
                            // The set is already too large: a file still queued is not written at all.
                            return null;
                        }
                        try {
                            put(archive, prefix, path, unpacked, limits.maxUnpackedSize());
                        } catch (InvalidUploadException e) {
                            refused.set(true);
                            throw e;
                        }
                        return null;
                    }))
                    .toList();
            awaitAll(pending, prefix);
        } catch (IOException e) {
            removeWhatWasWritten(prefix);
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            removeWhatWasWritten(prefix);
            throw e;
        }
        log.debug("The microsite of {} is {} file(s) ({} bytes) under {}.", key, paths.size(),
                unpacked.get(), prefix);
        return prefix;
    }

    /**
     * Removes the files of a microsite that was not taken over. Best effort: no row names the prefix, so
     * whatever this cannot remove is the sweep's, and failing here would hide why the upload failed.
     */
    private void removeWhatWasWritten(String prefix) {
        try {
            deletePrefix(prefix);
        } catch (RuntimeException e) {
            log.warn("The files a refused microsite wrote under {} could not be removed; the sweep of "
                     + "unreferenced objects takes them.", prefix, e);
        }
    }

    /**
     * One file of the microsite, spooled and put.
     *
     * @param unpacked    what every writer of this set has spooled so far, this one included
     * @param maxUnpacked the most the set may unpack to
     * @throws InvalidUploadException as soon as the set is past its limit, before this file is put
     */
    private void put(ZipFile archive, String prefix, String path, AtomicLong unpacked, long maxUnpacked)
            throws IOException {
        ZipEntry entry = archive.getEntry(path);
        if (entry == null) {
            // The paths were read from this archive's own directory, so this is a set that changed under us.
            return;
        }
        Path spooled = Files.createTempFile(properties.spoolDirectoryOrDefault(), "jeap-doc-file-", ".part");
        try (InputStream content = archive.getInputStream(entry);
             OutputStream file = Files.newOutputStream(spooled)) {
            byte[] buffer = new byte[SPOOL_BUFFER_BYTES];
            int read;
            while ((read = content.read(buffer)) >= 0) {
                long total = unpacked.addAndGet(read);
                if (total > maxUnpacked) {
                    throw unpacksToTooMuch(total, maxUnpacked);
                }
                file.write(buffer, 0, read);
            }
        } catch (IOException | RuntimeException e) {
            delete(spooled);
            throw e;
        }
        try {
            long size = Files.size(spooled);
            s3Client.putObject(PutObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(prefix + path)
                    .contentType(MediaTypes.of(path))
                    .contentLength(size)
                    .tagging(Tagging.builder().tagSet(Tag.builder()
                            .key(S3DocumentationBundleStorage.CONTENT_TAG_KEY)
                            .value(CONTENT_TAG_VALUE)
                            .build()).build())
                    .build(), RequestBody.fromFile(spooled));
        } finally {
            delete(spooled);
        }
    }

    private static InvalidUploadException unpacksToTooMuch(long unpacked, long allowed) {
        return new InvalidUploadException(InvalidUploadException.Code.UNPACKS_TO_TOO_MUCH,
                ("The uploaded microsite unpacks to more than %d bytes, which is the most a documentation set "
                 + "may be; unpacking stopped at %d.").formatted(allowed, unpacked));
    }

    /**
     * Waits for every file and reports the first failure, after all of them have finished: nothing may still
     * be writing into the prefix once this returns, or the sweep would be removing what is being written.
     */
    private static void awaitAll(List<Future<Void>> pending, String prefix) {
        RuntimeException failure = null;
        for (Future<Void> write : pending) {
            try {
                write.get();
            } catch (ExecutionException e) {
                if (failure == null) {
                    failure = e.getCause() instanceof RuntimeException cause ? cause
                            : new IllegalStateException(
                                    "A file of the microsite under %s could not be written.".formatted(prefix),
                                    e.getCause());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Writing the microsite under %s was interrupted.".formatted(prefix), e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * The text of a microsite's pages, one line per page.
     * <p>
     * <b>Tab separated, not JSON.</b> The fields are a path, a title and one line of text with every run of
     * whitespace already collapsed to a single space - so no field can hold a tab or a newline, and there is
     * nothing to escape. It is also why this needs no JSON mapper in an adapter that has no other use for
     * one. A stray control character is replaced on the way in rather than trusted.
     */
    @Override
    public void storeSearchText(String prefix, List<MicrositePageText> pages) {
        StringBuilder lines = new StringBuilder();
        for (MicrositePageText page : pages) {
            lines.append(oneLine(page.path())).append('\t')
                    .append(oneLine(page.title())).append('\t')
                    .append(oneLine(page.text())).append('\n');
        }
        byte[] content = lines.toString().getBytes(StandardCharsets.UTF_8);
        s3Client.putObject(PutObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(prefix + MicrositeRules.SEARCH_TEXT)
                .contentType(SEARCH_TEXT_CONTENT_TYPE)
                .contentLength((long) content.length)
                .tagging(Tagging.builder().tagSet(Tag.builder()
                        .key(S3DocumentationBundleStorage.CONTENT_TAG_KEY)
                        .value(CONTENT_TAG_VALUE)
                        .build()).build())
                .build(), RequestBody.fromBytes(content));
        log.debug("The text of {} page(s) of the microsite under {} is stored.", pages.size(), prefix);
    }

    @Override
    public List<MicrositePageText> readSearchText(String prefix) {
        Optional<StoredObject> stored = openFile(prefix, MicrositeRules.SEARCH_TEXT);
        if (stored.isEmpty()) {
            return List.of();
        }
        try (InputStream content = stored.get().content()) {
            List<MicrositePageText> pages = new ArrayList<>();
            for (String line : new String(content.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String[] fields = line.split("\t", 3);
                if (fields.length == 3) {
                    pages.add(new MicrositePageText(fields[0], fields[1], fields[2]));
                }
            }
            return List.copyOf(pages);
        } catch (IOException e) {
            // One microsite's content missing from the index, rather than a failed index run.
            log.warn("The stored text of the microsite under {} could not be read; its pages are not "
                     + "indexed this time.", prefix, e);
            return List.of();
        }
    }

    /** No tab and no newline, whatever a document held: they are what separates the fields. */
    private static String oneLine(String value) {
        return value == null ? "" : value.replaceAll("\\s", " ").trim();
    }

    @Override
    public Optional<CustomDocumentationStorage.OpenedBundle> open(CustomSet set) {
        return spool(set).map(SpooledSetBundle::new);
    }

    @Override
    public Optional<StoredObject> openFile(String prefix, String path) {
        String key = prefix + path;
        try {
            ResponseInputStream<GetObjectResponse> object = s3Client.getObject(GetObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(key)
                    .build());
            GetObjectResponse response = object.response();
            return Optional.of(new StoredObject(object, response.contentLength(), response.eTag(),
                    MediaTypes.of(path)));
        } catch (NoSuchKeyException e) {
            log.debug("The microsite under {} holds no {}.", prefix, path, e);
            return Optional.empty();
        }
    }

    @Override
    public void delete(String objectKey) {
        if (objectKey.endsWith("/")) {
            // An HTML set's row names the prefix its files lie under, so removing the set is removing all
            // of them - in batches, because a microsite is hundreds of small objects.
            deletePrefix(objectKey);
            return;
        }
        // Not guarded against an object that is not there: DeleteObject is idempotent and answers 204 either
        // way, so there is no second outcome to tell apart.
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(objectKey)
                .build());
    }

    private void deletePrefix(String prefix) {
        List<ObjectIdentifier> batch = new ArrayList<>(DELETE_BATCH_SIZE);
        int deleted = 0;
        for (S3Object object : s3Client.listObjectsV2Paginator(builder -> builder
                .bucket(properties.getBucket()).prefix(prefix)).contents()) {
            batch.add(ObjectIdentifier.builder().key(object.key()).build());
            if (batch.size() == DELETE_BATCH_SIZE) {
                deleteBatch(batch);
                deleted += batch.size();
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            deleteBatch(batch);
            deleted += batch.size();
        }
        log.debug("Removed the {} file(s) of the microsite under {}.", deleted, prefix);
    }

    /**
     * One batch delete, and what it refused. {@code DeleteObjects} answers {@code 200} with an error per key
     * it would not delete, so reading the response is the only way to know that anything was left behind.
     */
    private void deleteBatch(List<ObjectIdentifier> batch) {
        DeleteObjectsResponse answer = s3Client.deleteObjects(DeleteObjectsRequest.builder()
                .bucket(properties.getBucket())
                .delete(Delete.builder().objects(batch).build())
                .build());
        if (answer.hasErrors() && !answer.errors().isEmpty()) {
            S3Error first = answer.errors().getFirst();
            throw new IllegalStateException(
                    "The object storage refused to delete %d of %d objects, the first of them '%s': %s (%s)."
                            .formatted(answer.errors().size(), batch.size(), first.key(), first.message(),
                                    first.code()));
        }
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

    /**
     * Where the files of a microsite lie: the key its bundle would have had, with {@code files/} in place of
     * the archive name. It ends with a slash, which is what tells a prefix from an object key.
     */
    private String filesPrefix(CustomSetKey key, long revision, int attempt) {
        String bundle = objectKey(key, revision, attempt);
        return bundle.substring(0, bundle.length() - BUNDLE_NAME.length()) + FILES_SEGMENT + "/";
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
