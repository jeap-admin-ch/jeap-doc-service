package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.DocumentationUploadRepository;
import lombok.RequiredArgsConstructor;
import ch.admin.bit.jeap.doc.domain.port.ExclusiveWork;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Forgets the uploads the doc service has no use for any more.
 * <p>
 * An upload is kept for {@code jeap.doc.upload.housekeeping.retention} after it was last received and is then
 * removed, <b>whatever state it is in</b>: what has not been generated from in two weeks is not going to be, and
 * an upload that is still {@code UPLOADING} after two weeks belongs to a request nobody remembers. The
 * documentation generator never removes an upload - it only moves it on - so this is the only place uploads go.
 * <p>
 * <b>The database only.</b> The bundles are expired by a lifecycle rule of the bucket, set a little longer than
 * the retention, so that an upload never points at a bundle that is already gone. What an upload documented -
 * the system, component or library - is kept: that is the catalogue of the documentation, not a leftover.
 * <p>
 * Of several instances of the doc service, only one runs the clean-up: the job takes a lock in the database for
 * as long as it may run, so the others find it taken and skip the night.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentationUploadHousekeeping {

    private final DocumentationUploadRepository uploadRepository;
    private final UploadProperties uploadProperties;
    /** How long the lock of this nightly job survives an instance that dies holding it. */
    private static final Duration HOUSEKEEPING_LEASE = Duration.ofMinutes(30);

    private final Clock clock;
    private final ExclusiveWork exclusiveWork;

    /**
     * When this runs, and whether it runs at all, is decided by
     * {@link DocumentationUploadHousekeepingScheduling} from the configured values - there is one place that says
     * what the defaults are, and it is {@link UploadProperties.Housekeeping}.
     */
    public void removeOldUploads() {
        // Of several instances only one runs this. The lease is long enough that a clean-up which takes its time
        // is not run twice, and short enough that an instance dying with the lock does not skip more than one
        // night; it is extended while the work runs.
        exclusiveWork.underLock("documentationUploadHousekeeping", HOUSEKEEPING_LEASE, this::removeOldUploadsNow);
    }

    private void removeOldUploadsNow() {
        Instant receivedBefore = clock.instant().minus(uploadProperties.getHousekeeping().getRetention());
        int removed = uploadRepository.deleteReceivedBefore(receivedBefore);
        if (removed > 0) {
            log.info("Removed {} uploads received before {}; their bundles are expired by the lifecycle rule of "
                     + "the bucket.", removed, receivedBefore);
        } else {
            log.debug("No upload received before {} to remove.", receivedBefore);
        }
    }
}
