package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import lombok.RequiredArgsConstructor;
import ch.admin.bit.jeap.doc.domain.port.ExclusiveWork;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Forgets the builds the doc service has no use for any more.
 * <p>
 * A build is kept for {@code jeap.doc.build.history-retention} after it finished and is then removed: it is the
 * evidence of what was generated and when, and that is worth a quarter rather than for ever.
 * <p>
 * <b>Except the one that is published.</b> The newest successful build of a site is not only a record, it *is*
 * the publication - so a site that is published rarely, or one that stopped being published at all, would
 * otherwise lose the row that says what is being served and start answering that it has never been generated.
 * <p>
 * Of several instances of the doc service, only one runs the clean-up.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentationBuildHousekeeping {

    private final DocumentationBuildRepository builds;
    private final DocumentationSites sites;
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
        Set<Long> published = new LinkedHashSet<>();
        for (Site site : sites.all()) {
            builds.published(site.id()).map(DocumentationBuild::id).ifPresent(published::add);
        }
        int removed = builds.deleteFinishedBefore(finishedBefore, published);
        if (removed > 0) {
            log.info("Removed the record of {} build(s) that finished before {}; the {} published build(s) are "
                     + "kept whatever their age.", removed, finishedBefore, published.size());
        } else {
            log.debug("No build record older than {} to remove.", finishedBefore);
        }
    }
}
