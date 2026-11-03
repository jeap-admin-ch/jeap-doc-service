package ch.admin.bit.jeap.doc.domain.governance;

import ch.admin.bit.jeap.doc.domain.DocumentationBuildTrigger;
import ch.admin.bit.jeap.doc.domain.port.ExclusiveWork;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Runs the governance import under a lock, and asks for the governance site to be published when the import
 * reached a complete state.
 * <p>
 * Unlike the architecture import there is no environment and no chain of steps: {@link GovernanceImport} reads
 * both the systems and the rules of the one Governance Service this instance has, and this job only adds what
 * every scheduled import needs beside that - one instance at a time, and the trigger.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GovernanceImportJob {

    static final String LOCK = "governanceImport";

    /**
     * How long the lock survives an instance that dies holding it. Short, because a run is a handful of small
     * JSON resources and not a landscape: an instance that died mid-run must not block the next schedule - or
     * the webhook's catch-up - for longer than that.
     */
    private static final Duration LEASE = Duration.ofMinutes(5);

    private final GovernanceImport governanceImport;
    private final ExclusiveWork exclusiveWork;
    /**
     * The import is the trigger: a changed score or rule is documentation that is out of date, and this is
     * what publishes it minutes - or, on the webhook call, seconds - later. It asks after an unchanged import
     * too, because a request lost to a failure would otherwise never be repeated; the content digest makes
     * such a build a skip. A site fed by the Governance
     * Service is deliberately left out of the reconcile schedule, so nothing else would ask.
     */
    private final DocumentationBuildTrigger buildTrigger;

    /**
     * Imports the governance data, whether asked for by the schedule or by the webhook.
     * <p>
     * Of several instances, one runs the import and the others find the lock taken. For two schedules that
     * fired together one import is enough. A webhook call is different: the run holding the lock may have read
     * the indexes before the scoring it reports, so {@link GovernanceImportPickup} tries again later.
     *
     * @return whether this instance ran the import, false when another instance held the lock
     */
    public boolean importGovernanceData() {
        Optional<Boolean> changed = exclusiveWork.underLock(LOCK, LEASE, governanceImport::run);
        if (changed.isEmpty()) {
            log.debug("Another instance is importing the governance data.");
            return false;
        }
        if (changed.get()) {
            askForTheDocumentation();
        }
        return true;
    }

    /**
     * Asks for every part of the governance site, once the import changed something.
     * <p>
     * There is only ever one part - the site is shell only - but this does not special-case that: what decides
     * how a site is cut into parts is {@code SitePartition}, not this job.
     */
    private void askForTheDocumentation() {
        try {
            int parts = buildTrigger.requestBecauseTheGovernanceWasImported();
            if (parts > 0) {
                log.info("The governance import asked for {} part(s) of the documentation to be built.", parts);
            }
        } catch (RuntimeException e) {
            log.warn("The governance data was imported, but the documentation it changes could not be asked "
                     + "for. The next import asks again.", e);
        }
    }
}
