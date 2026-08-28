package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.BuildRequest;
import ch.admin.bit.jeap.doc.domain.BuildTrigger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The standing request to publish a site: at most one per site, however often it was asked for.
 */
public interface DocumentationBuildRequestRepository {

    /**
     * Asks for a build of the given site, and reports whether this call is what started the request waiting.
     * <p>
     * A request that is already pending is left exactly as it is - it keeps the instant and the trigger it was
     * first asked with, so the age of a request says how long the oldest unserved trigger has been waiting
     * rather than how long ago the last one arrived.
     */
    boolean request(String site, BuildTrigger trigger, Instant now);

    /**
     * The sites with a pending request, oldest first. Read only: claiming happens inside the site's lock.
     */
    List<BuildRequest> pending();

    /**
     * Takes the pending request of a site, clearing it, and reports what had asked for it.
     * <p>
     * **This is what makes several triggers one run.** It is called at the start of a build, before anything is
     * read, so every trigger arriving from then on finds the flag clear and sets it again - and the next tick
     * performs exactly one further build, whatever the burst was.
     */
    Optional<BuildTrigger> claim(String site);

    /**
     * When the oldest pending request of a site was made, for the age gauge.
     */
    Optional<Instant> pendingSince(String site);
}
