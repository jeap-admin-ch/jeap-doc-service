package ch.admin.bit.jeap.doc.objectstorage;

import ch.admin.bit.jeap.doc.domain.port.PartPublication;
import ch.admin.bit.jeap.doc.domain.port.PublishedSite;
import ch.admin.bit.jeap.doc.domain.port.SitePublicationStorage;
import ch.admin.bit.jeap.doc.domain.port.StoredObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Stream;

/**
 * Publishes the generated sites into the S3 bucket of the doc service, and serves them from it.
 * <p>
 * A site is written under the identifier of the build that produced it, and the build is recorded as succeeded
 * only afterwards - so nothing that is being read is ever written to, and the switch from one site to the next is
 * one row in the database. That is the only part of publishing that has to be atomic, and it is the only part
 * that can be: the object storage has no transaction to borrow.
 */
@Slf4j
@RequiredArgsConstructor
class S3SitePublicationStorage implements SitePublicationStorage {

    /**
     * The tag every published file carries, so that a lifecycle rule of the bucket can name what it is expiring
     * rather than a prefix an instance configures for itself.
     */
    static final String CONTENT_TAG_VALUE = "site";

    /** How many keys a delete request carries; the S3 API takes a thousand. */
    private static final int DELETE_BATCH_SIZE = 1000;

    private final S3Client s3Client;
    private final DocObjectStorageProperties properties;

    /**
     * Writes the whole site into the bucket, several files at a time.
     * <p>
     * A generated site is thousands of small files, so publishing it is bound by round trips and not by
     * bandwidth: written one after another it takes as long as the site generator did, and the object storage
     * is idle for almost all of it. Concurrency is what fixes that, and it is bounded by
     * {@code jeap.doc.storage.publication-concurrency} rather than unbounded, because the S3 client has a
     * connection pool and asking it for more connections than it has only moves the queue.
     * <p>
     * Platform threads, not virtual ones: the limit here is that connection pool, so a thread per file would
     * park on the pool instead of on the network, and this way the number of threads says what is happening.
     */
    @Override
    public PublishedSite publish(PartPublication where, Path directory) {
        String prefix = where.prefix();
        List<Path> files = filesOf(directory);
        // The part's own files, and not the shared ones beside them. Every part build writes the same
        // assets/** and img/** to one prefix of the site, so counting them per part made the size of a
        // fifty-two-part site fifty-one copies of that bundle too large - and it is the row of one part.
        List<Path> ownFiles = files.stream()
                .filter(file -> !where.isShared(directory.relativize(file).toString().replace('\\', '/')))
                .toList();
        long size = ownFiles.stream().mapToLong(S3SitePublicationStorage::sizeOf).sum();
        // Written from every upload thread, so that the line below can say how much of the shared prefix this
        // build did not have to write again.
        LongAdder sharedAlreadyThere = new LongAdder();
        try (ExecutorService uploads = Executors.newFixedThreadPool(
                Math.min(properties.getPublicationConcurrency(), Math.max(files.size(), 1)),
                runnable -> {
                    Thread thread = new Thread(runnable, "site-publication");
                    thread.setDaemon(true);
                    return thread;
                })) {
            List<Callable<Void>> tasks = files.stream()
                    .map(file -> (Callable<Void>) () -> {
                        if (!put(where, directory, file)) {
                            sharedAlreadyThere.increment();
                        }
                        return null;
                    })
                    .toList();
            List<Future<Void>> pending = tasks.stream().map(uploads::submit).toList();
            awaitAll(pending, prefix);
        }
        int shared = files.size() - ownFiles.size();
        log.info("Published {} files ({} bytes) under {}, and {} shared file(s) of the site beside them, of "
                 + "which {} were already stored with the same content.",
                ownFiles.size(), size, prefix, shared, sharedAlreadyThere.sum());
        return new PublishedSite(prefix, ownFiles.size(), size);
    }

    /**
     * Writes one file of the output, and answers whether it was written.
     * <p>
     * <b>A shared file already stored with the same bytes is not written again.</b> The shared prefix belongs
     * to the site rather than to a build, so every part of a publication writes the same ninety-odd names into
     * it: a fifty-two-part site did that fifty-two times, some five thousand requests for bytes that were
     * already there. What decides is the stored entity tag against the digest of the file, and not merely that
     * the key exists - the fixed-name files of the prefix do change with a new version of the template, and
     * skipping on existence would strand a stale logo or a stale theme chunk there for ever.
     */
    private boolean put(PartPublication where, Path directory, Path file) {
        String path = directory.relativize(file).toString().replace('\\', '/');
        // The shared files of a site go to one prefix, written by every part build: the same bytes under the
        // same name, so one build overwriting another's file writes what was already there.
        String key = keyOf(where.prefixOf(path), path);
        if (where.isShared(path) && isAlreadyStored(key, file)) {
            return false;
        }
        s3Client.putObject(PutObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(key)
                .contentType(MediaTypes.of(file.getFileName().toString()))
                .contentLength(sizeOf(file))
                .tagging(Tagging.builder().tagSet(Tag.builder()
                        .key(S3DocumentationBundleStorage.CONTENT_TAG_KEY)
                        .value(CONTENT_TAG_VALUE)
                        .build()).build())
                .build(), RequestBody.fromFile(file));
        return true;
    }

    /**
     * Whether the object under this key is already the bytes of this file.
     * <p>
     * The entity tag of an object written in one request is the hex MD5 of its content, which is what this
     * compares against. Anything else - a missing object, an object written in parts, a storage that tags
     * differently - simply does not match, and the file is written; there is no case in which this decides to
     * skip a file whose bytes differ. A failure to read the tag is not one either: it is answered as "not
     * stored", because writing a file twice is cheap and not writing it is a broken site.
     */
    private boolean isAlreadyStored(String key, Path file) {
        try {
            HeadObjectResponse stored = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(key)
                    .build());
            return stored.contentLength() != null && stored.contentLength() == sizeOf(file)
                   && unquoted(stored.eTag()).equalsIgnoreCase(md5Of(file));
        } catch (NoSuchKeyException e) {
            return false;
        } catch (RuntimeException e) {
            log.debug("The stored copy of {} could not be read, so it is written again.", key, e);
            return false;
        }
    }

    private static String unquoted(String eTag) {
        return eTag == null ? "" : eTag.replace("\"", "");
    }

    /** The digest an entity tag is compared against. MD5 because that is what S3 puts in the tag. */
    @SuppressWarnings("java:S4790") // Not a security decision: this is the digest the S3 entity tag carries.
    private static String md5Of(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            try (InputStream content = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = content.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new UncheckedIOException("The file %s could not be read to publish it.".formatted(file), e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is not available.", e);
        }
    }

    /**
     * Waits for every upload and reports the first one that failed.
     * <p>
     * All of them are waited for even after one has failed, so that nothing is still writing into the prefix
     * once this returns - a half-written site under its own build identifier is harmless, one still being
     * written to while it is cleaned up is not. What it leaves behind is never published: the build only
     * becomes the served one if this method returns.
     */
    private static void awaitAll(List<Future<Void>> pending, String prefix) {
        RuntimeException failure = null;
        for (Future<Void> upload : pending) {
            try {
                upload.get();
            } catch (ExecutionException e) {
                if (failure == null) {
                    failure = e.getCause() instanceof RuntimeException cause ? cause
                            : new IllegalStateException("A file of the site under %s could not be published."
                            .formatted(prefix), e.getCause());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Publishing the site under %s was interrupted.".formatted(prefix), e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public Optional<StoredObject> open(String prefix, String path) {
        String key = keyOf(prefix, path);
        try {
            ResponseInputStream<GetObjectResponse> object = s3Client.getObject(GetObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(key)
                    .build());
            GetObjectResponse response = object.response();
            return Optional.of(new StoredObject(object, response.contentLength(), response.eTag(),
                    MediaTypes.of(path)));
        } catch (NoSuchKeyException e) {
            log.debug("There is no {} in the published documentation.", key, e);
            return Optional.empty();
        }
    }

    /**
     * Asks the object storage whether the key is there, and nothing else: a {@code HEAD} brings back no body,
     * so there is no stream to close and no connection to leak.
     */
    @Override
    public boolean exists(String prefix, String path) {
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(keyOf(prefix, path))
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            log.debug("There is no {} in the published documentation.", keyOf(prefix, path), e);
            return false;
        }
    }

    /**
     * Removes a published site that nothing serves any more, in batches rather than one request per object: a
     * site is many small files, and the deletion would otherwise be the slowest part of a fast build.
     */
    @Override
    public void delete(String prefix) {
        ListObjectsV2Iterable pages = s3Client.listObjectsV2Paginator(builder -> builder
                .bucket(properties.getBucket())
                .prefix(keyOf(prefix, "")));
        List<ObjectIdentifier> batch = new ArrayList<>(DELETE_BATCH_SIZE);
        int deleted = 0;
        for (S3Object object : pages.contents()) {
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
        log.info("Removed the published site under {} ({} files).", prefix, deleted);
    }

    /**
     * Where a file of a published site lies. The configured prefix is prepended here rather than by the caller,
     * so that publishing, reading and deleting cannot disagree about it - and so that the generated sites stay
     * out of the namespace of the uploaded bundles.
     */
    private String keyOf(String prefix, String path) {
        String sitePrefix = withoutSurroundingSlashes(properties.getSitePrefix());
        String root = sitePrefix.isEmpty() ? prefix : sitePrefix + "/" + prefix;
        return path.isEmpty() ? root + "/" : root + "/" + path;
    }

    /**
     * The configured prefix as a key segment: an instance may write it with or without slashes around it, and an
     * object key wants neither.
     */
    private static String withoutSurroundingSlashes(String configured) {
        if (configured == null) {
            return "";
        }
        String trimmed = configured.strip();
        int start = 0;
        int end = trimmed.length();
        while (start < end && trimmed.charAt(start) == '/') {
            start++;
        }
        while (end > start && trimmed.charAt(end - 1) == '/') {
            end--;
        }
        return trimmed.substring(start, end);
    }

    /**
     * One batch delete, and what it refused.
     * <p>
     * {@code DeleteObjects} answers {@code 200} with an error <b>per key</b> it would not delete, and throws
     * only where the whole request failed. Reading the response is therefore the only way to know: without it a
     * delete that removed almost nothing returns normally, logs the full count as removed, and the caller
     * records the prefix as forgotten - so the objects are never offered for deletion again.
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

    private static List<Path> filesOf(Path directory) {
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
