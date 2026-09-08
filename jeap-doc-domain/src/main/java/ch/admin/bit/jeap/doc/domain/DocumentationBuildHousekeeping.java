package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import lombok.RequiredArgsConstructor;
import ch.admin.bit.jeap.doc.domain.port.ExclusiveWork;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Forgets the builds the doc service has no use for any more.
 * <p>
 * A build is kept for {@code jeap.doc.build.history-retention} after it finished and is then removed: it is the
 * evidence of what was generated and when, and that is worth a quarter rather than for ever.
 * <p>
 * <b>Except what each part has published.</b> The newest successful build of a part is not only a record, it
 * <i>is</i> the publication - and a part whose content does not move is not rebuilt at all, so its publication
 * is routinely older than the retention. The delete spares it, rather than this job naming the rows to spare:
 * a keep-set assembled from the configured parts loses the publication of a part the model no longer has, and
 * the pages it serves then 404 with nothing left to name their objects.
 * <p>
 * Of several instances of the doc service, only one runs the clean-up.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentationBuildHousekeeping {

    private final DocumentationBuildRepository builds;
    private final BuildProperties properties;
    /** How long the lock of this nightly job survives an instance that dies holding it. */
    private static final Duration HOUSEKEEPING_LEASE = Duration.ofMinutes(30);

    private final Clock clock;
    private final ExclusiveWork exclusiveWork;

    /**
     * When this runs is decided by {@link DocumentationBuildScheduling}, from the configured values.
     */
    public void removeOldBuilds() {
        // Of several instances only one runs this. The lease is long enough that a clean-up which takes its time
        // is not run twice, and short enough that an instance dying with the lock does not skip more than one
        // night; it is extended while the work runs.
        exclusiveWork.underLock("documentationBuildHousekeeping", HOUSEKEEPING_LEASE, this::removeOldBuildsNow);
    }

    private void removeOldBuildsNow() {
        Instant finishedBefore = clock.instant().minus(properties.getHistoryRetention());
        int removed = builds.deleteFinishedBefore(finishedBefore);
        if (removed > 0) {
            log.info("Removed the record of {} build(s) that finished before {}; what each part has published "
                     + "is kept whatever its age.", removed, finishedBefore);
        } else {
            log.debug("No build record older than {} to remove.", finishedBefore);
        }
    }
}
