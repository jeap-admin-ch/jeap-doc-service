package ch.admin.bit.jeap.doc.objectstorage;

import ch.admin.bit.jeap.doc.domain.upload.InvalidUploadException;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBundleStorage;
import ch.admin.bit.jeap.doc.domain.port.StoredBundle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

/**
 * Stores the uploaded bundles in the S3 bucket of the doc service.
 * <p>
 * The bundle is spooled to a temporary file before it is put, for three reasons: the AWS SDK has to be able to
 * re-read a body when it retries a request, and the stream of a request cannot be reset; a body that does not
 * match the announced length is caught before an object exists rather than after; and the request to S3 then
 * lasts as long as the upload to S3 takes instead of as long as the client takes to send.
 * <p>
 * The key is built here and reported back, so nothing outside this adapter has to know how a key looks.
 * <p>
 * The request always carries its content length, and that is what keeps the bundle out of the heap: the
 * {@code UrlConnectionHttpClient} of the jEAP object storage starter puts the connection into fixed-length
 * streaming mode when the request has a {@code Content-Length}, and {@code HttpURLConnection} would buffer the
 * whole body in memory without it. Verified with a 256 MB bundle against a 128 MB heap.
 */
@Slf4j
@RequiredArgsConstructor
class S3DocumentationBundleStorage implements DocumentationBundleStorage {

    /**
     * The tag every uploaded bundle carries. It is what a lifecycle rule of the bucket selects on to expire the
     * incoming documentation - the prefix would not do, because an instance configures its own.
     */
    static final String CONTENT_TAG_KEY = "jeap-doc-content";
    static final String CONTENT_TAG_VALUE = "upload";

    private static final String DOCS_SEGMENT = "docs";
    private static final String BUNDLE_NAME = "bundle.zip";
    private static final String CONTENT_TYPE = "application/zip";
    private static final int COPY_BUFFER_SIZE = 8192;

    private final S3Client s3Client;
    private final DocObjectStorageProperties properties;

    @Override
    public StoredBundle store(long uploadId, int attempt, InputStream bundle, long sizeInBytes) {
        String objectKey = objectKey(uploadId, attempt);
        MessageDigest digest = sha256();
        Path spooled = spool(new DigestInputStream(bundle, digest), sizeInBytes);
        try {
            s3Client.putObject(PutObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(objectKey)
                    .contentType(CONTENT_TYPE)
                    .contentLength(sizeInBytes)
                    .tagging(Tagging.builder()
                            .tagSet(Tag.builder().key(CONTENT_TAG_KEY).value(CONTENT_TAG_VALUE).build())
                            .build())
                    .build(), RequestBody.fromFile(spooled));
            String sha256 = HexFormat.of().formatHex(digest.digest());
            log.debug("Stored the bundle of the upload {} as {} (sha-256 {}).", uploadId, objectKey, sha256);
            return new StoredBundle(objectKey, sha256);
        } finally {
            delete(spooled);
        }
    }

    /**
     * The digest is taken while the bundle is spooled - the bytes are read once anyway, and what is hashed is
     * exactly what is written to the object storage.
     */
    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available.", e);
        }
    }

    /**
     * The key of the bundle: the configured prefix, the kind of upload, the identifier the doc service gave the
     * upload, and the attempt that is writing. The kind is part of it because another kind of upload will have
     * its own identifiers; the attempt is, because two attempts of one upload can be writing at the same time
     * and the key an upload records has to name the bytes it describes.
     */
    private String objectKey(long uploadId, int attempt) {
        String prefix = properties.getUploadPrefix().replaceAll("^/+|/+$", "");
        return "%s/%s/%d/%d/%s".formatted(prefix, DOCS_SEGMENT, uploadId, attempt, BUNDLE_NAME);
    }

    /**
     * Reads the bundle into a temporary file and checks that it is as long as the upload announced - a body cut
     * short would otherwise be published as a truncated, valid-looking bundle.
     */
    private Path spool(InputStream bundle, long sizeInBytes) {
        Path spooled;
        try {
            spooled = Files.createTempFile(properties.spoolDirectoryOrDefault(), "jeap-doc-upload-", ".zip");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        long received = 0;
        try (OutputStream file = Files.newOutputStream(spooled)) {
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int read;
            while ((read = bundle.read(buffer)) != -1) {
                file.write(buffer, 0, read);
                received += read;
            }
        } catch (IOException e) {
            delete(spooled);
            // A body that stops early is reported by the servlet container as a broken connection rather than as
            // an end of the stream, so what looks like an I/O failure here is usually the upload announcing more
            // than it sent - and that is the caller's mistake, not the service's.
            throw received < sizeInBytes ? contentLengthMismatch(received, sizeInBytes, e) : new UncheckedIOException(e);
        } catch (RuntimeException e) {
            delete(spooled);
            throw e;
        }
        if (received != sizeInBytes) {
            delete(spooled);
            throw contentLengthMismatch(received, sizeInBytes, null);
        }
        return spooled;
    }

    private static InvalidUploadException contentLengthMismatch(long received, long announced, Throwable cause) {
        return new InvalidUploadException(InvalidUploadException.Code.CONTENT_LENGTH_MISMATCH,
                "The uploaded bundle is %d bytes long, but Content-Length announced %d."
                        .formatted(received, announced), cause);
    }

    private static void delete(Path spooled) {
        try {
            Files.deleteIfExists(spooled);
        } catch (IOException e) {
            log.warn("Could not delete the temporary file {} of an upload.", spooled, e);
        }
    }
}
