package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.DocumentationBundleStorage;
import ch.admin.bit.jeap.doc.domain.port.DocumentationSubjectRepository;
import ch.admin.bit.jeap.doc.domain.port.DocumentationUploadRepository;
import ch.admin.bit.jeap.doc.domain.port.StoredBundle;
import ch.admin.bit.jeap.doc.domain.port.UploadClaim;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Receives the documentation the doc pipelines upload: it records the upload, stores its bundle and leaves it
 * pending for the documentation generator.
 * <p>
 * Two rules shape the order of what happens here. The upload is <b>recorded before its bundle is read</b>, so a
 * bundle on its way is a visible state rather than an object nobody knows about - and <b>no transaction is open
 * while the bundle streams</b>, which is why the steps are separate calls to the repository instead of one
 * transactional method around everything.
 * <p>
 * The upload id is the idempotency key: repeating an upload under the same id never produces a second
 * documentation set. What each repetition does is decided in {@link #receive}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentationUploadService {

    private static final int DRAIN_BUFFER_SIZE = 8192;

    /**
     * What is recorded when the object storage did not take the bundle - the same words the caller is answered
     * with, while what actually happened is in the log.
     */
    private static final String STORAGE_FAILED_REASON = "The bundle could not be stored.";

    private final DocumentationUploadRepository uploadRepository;
    private final DocumentationSubjectRepository subjectRepository;
    private final DocumentationBundleStorage bundleStorage;
    private final UploadProperties uploadProperties;
    private final Clock clock;

    /**
     * Receives one upload and reports what became of it.
     *
     * @param uploadId    the upload id the client chose
     * @param descriptor  what is being uploaded
     * @param bundle      the bundle, to be read to its end
     * @param sizeInBytes the size the client announced
     * @return what was recorded, and whether this request is the one that stored the bundle
     * @throws InvalidUploadException if the upload id belongs to a different upload, if another attempt is in
     *                                flight, or if the bundle could not be stored
     */
    public UploadReceipt receive(UUID uploadId, DocumentationUploadDescriptor descriptor,
                                 InputStream bundle, long sizeInBytes) {
        Instant now = clock.instant();
        Optional<DocumentationUpload> recorded = uploadRepository.findByUploadId(uploadId);
        recorded.ifPresent(upload -> requireSameUpload(upload, descriptor));
        if (recorded.filter(DocumentationUpload::isPending).isPresent()) {
            return replay(recorded.get(), bundle);
        }

        DocumentationSubject subject = subjectRepository.findOrCreate(DocumentationSubject.of(descriptor), now);
        UploadClaim claim = uploadRepository.claim(uploadId, subject, descriptor, now, staleBefore(now));
        return switch (claim) {
            case UploadClaim.Claimed(DocumentationUpload upload) -> {
                logReceiving(upload, sizeInBytes);
                yield UploadReceipt.stored(store(upload, bundle, sizeInBytes));
            }
            case UploadClaim.AlreadyCompleted(DocumentationUpload upload) -> replay(upload, bundle);
            case UploadClaim.InProgress(DocumentationUpload upload) -> throw inProgress(upload, now);
        };
    }

    /**
     * The upload recorded under the given upload id, if it belongs to the given system.
     * <p>
     * An upload of another system is answered as if it did not exist: the write role is granted per system, and
     * what another system uploaded is none of the caller's business - not even whether it exists.
     */
    public Optional<DocumentationUpload> statusOf(UUID uploadId, String system) {
        return uploadRepository.findByUploadId(uploadId)
                .filter(upload -> upload.descriptor().system().equals(system));
    }

    private DocumentationUpload store(DocumentationUpload upload, InputStream bundle, long sizeInBytes) {
        StoredBundle stored;
        try {
            stored = bundleStorage.store(upload.id(), upload.attempt(), bundle, sizeInBytes);
        } catch (InvalidUploadException e) {
            // The upload itself is at fault - a bundle that is not as long as it announced, or longer than the
            // service accepts. That reason is what the caller has to hear, so it travels on unchanged instead of
            // being reported as a service that failed, and it is logged where it is answered.
            uploadRepository.save(upload.failed(e.getMessage()));
            log.debug("The bundle of the upload {} ({}) was not accepted: {}",
                    upload.uploadId(), upload.id(), e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            // What went wrong belongs in the log, with its cause - not on the upload: the reason is answered to
            // the caller, and the message of a storage client names buckets, endpoints and credential providers.
            uploadRepository.save(upload.failed(STORAGE_FAILED_REASON));
            log.error("Failed to store the bundle of the upload {} ({}) of the system {} - the upload is "
                      + "recorded as failed and can be retried.",
                    upload.uploadId(), upload.id(), upload.descriptor().system(), e);
            throw new InvalidUploadException(InvalidUploadException.Code.STORAGE_FAILED,
                    STORAGE_FAILED_REASON + " The upload can be retried.", e);
        }
        DocumentationUpload recorded = uploadRepository.save(upload.completed(stored, sizeInBytes, clock.instant()));
        log.info("Stored the upload {} ({}) of the system {} as {} ({} bytes, sha-256 {}), pending generation.",
                recorded.uploadId(), recorded.id(), recorded.descriptor().system(), stored.objectKey(),
                sizeInBytes, stored.sha256());
        return recorded;
    }

    /**
     * A repetition of an upload that is already stored: nothing is written, and the body is read to its end so
     * the caller can finish sending what it does not know is superfluous.
     */
    private UploadReceipt replay(DocumentationUpload upload, InputStream bundle) {
        drain(bundle);
        log.info("The upload {} ({}) of the system {} is already stored; the repetition changed nothing.",
                upload.uploadId(), upload.id(), upload.descriptor().system());
        return UploadReceipt.repeated(upload);
    }

    private void requireSameUpload(DocumentationUpload upload, DocumentationUploadDescriptor descriptor) {
        if (!upload.describesTheSameAs(descriptor)) {
            throw new InvalidUploadException(InvalidUploadException.Code.UPLOAD_ID_CONFLICT,
                    ("The upload id %s belongs to an upload that describes something else. An upload id identifies "
                     + "one upload: a retry repeats the request it was used with, and anything else needs its own "
                     + "upload id.").formatted(upload.uploadId()));
        }
    }

    private InvalidUploadException inProgress(DocumentationUpload upload, Instant now) {
        Duration retryAfter = retryAfter(upload, now);
        return InvalidUploadException.inProgress(
                ("The upload %s is currently being received. Retry in %d seconds, when the attempt that holds it "
                 + "has either finished or been given up on.").formatted(upload.uploadId(), retryAfter.toSeconds()),
                retryAfter);
    }

    private Duration retryAfter(DocumentationUpload upload, Instant now) {
        Duration remaining = Duration.between(now, upload.receivedAt().plus(uploadProperties.getInProgressTimeout()));
        return remaining.isPositive() ? remaining : Duration.ofSeconds(1);
    }

    private Instant staleBefore(Instant now) {
        return now.minus(uploadProperties.getInProgressTimeout());
    }

    /**
     * The line that ties what a pipeline knows - the upload id it chose - to what the doc service knows: the
     * identifier its bundle is stored under, what the upload documents, and which attempt this is.
     */
    private static void logReceiving(DocumentationUpload upload, long sizeInBytes) {
        DocumentationUploadDescriptor descriptor = upload.descriptor();
        log.info("Receiving the upload {} ({}), attempt {}: {} of the system {}{} on the site {}, {} bytes.",
                upload.uploadId(), upload.id(), upload.attempt(), descriptor.type(), descriptor.system(),
                descriptor.subjectName() == null ? "" : " (" + descriptor.subjectName() + ")",
                descriptor.site(), sizeInBytes);
    }

    private static void drain(InputStream bundle) {
        try (bundle) {
            byte[] buffer = new byte[DRAIN_BUFFER_SIZE];
            while (bundle.read(buffer) != -1) {
                // the bundle is not stored again, but it has to be received
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
