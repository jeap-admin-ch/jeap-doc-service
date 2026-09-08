package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.BuildRequest;
import ch.admin.bit.jeap.doc.domain.BuildTrigger;
import ch.admin.bit.jeap.doc.domain.PartKey;
import ch.admin.bit.jeap.doc.domain.Publication;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The standing request to publish one part of a site: at most one per part, however often it was asked for.
 * <p>
 * Per part rather than per site, so that a burst of uploads for one system is one build of that system's part -
 * and two systems changing at once are two builds rather than one of everything.
 */
public interface DocumentationBuildRequestRepository {

    /**
     * Asks for a build of the given site, and reports whether this call is what started the request waiting.
     * <p>
     * A request that is already pending is left exactly as it is - it keeps the instant, the trigger and the
     * publication it was first asked with, so the age of a request says how long the oldest unserved trigger has
     * been waiting rather than how long ago the last one arrived.
     * <p>
     * <b>Two things an ask does change about a request it joins</b>, and both are about what the build that
     * request leads to has to achieve rather than about who asked for it:
     * <ul>
     *   <li>an ask that may not be skipped by the content digest raises that flag on it - the strongest ask
     *       decides;</li>
     *   <li>an ask that is part of a publication puts the request into that publication where it belongs to
     *       none. A part already owed a build is built by the publication's own pass and <b>is</b> one of its
     *       parts: left out of it, the publication reads as finished while that part is still owed a build,
     *       and its wall clock stops before the last part finished. A request that already belongs to an
     *       earlier publication stays in it.</li>
     * </ul>
     *
     * @param forced whether the build this leads to may be skipped when the content hashes to what is
     *               published. An operator forcing a publication is the case: the reason to force one is that
     *               something outside the content changed
     */
    boolean request(PartKey part, BuildTrigger trigger, Instant now, Publication publication, boolean forced);

    /**
     * The parts with a pending request, oldest first. Read only: claiming happens inside the part's lock.
     */
    List<BuildRequest> pending();

    /**
     * Takes the pending request of a site, clearing it, and reports what had asked for it.
     * <p>
     * **This is what makes several triggers one run.** It is called at the start of a build, before anything is
     * read, so every trigger arriving from then on finds the flag clear and sets it again - and the next tick
     * performs exactly one further build, whatever the burst was.
     */
    Optional<BuildRequest> claim(PartKey part);

    /**
     * When the oldest pending request of a site was made, whichever part it is for - the age gauge is about the
     * site, because an operator asks whether <i>this documentation</i> is being published.
     */
    Optional<Instant> pendingSince(String site);

    /** How many parts of a site are owed a build right now. */
    int pendingCount(String site);
}
