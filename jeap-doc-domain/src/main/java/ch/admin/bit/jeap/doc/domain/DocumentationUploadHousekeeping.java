package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.DocumentationUploadRepository;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
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
    private final Clock clock;

    /**
     * When this runs, and whether it runs at all, is decided by
     * {@link DocumentationUploadHousekeepingScheduling} from the configured values - there is one place that says
     * what the defaults are, and it is {@link UploadProperties.Housekeeping}.
     */
    @SchedulerLock(name = "documentationUploadHousekeeping", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void removeOldUploads() {
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
