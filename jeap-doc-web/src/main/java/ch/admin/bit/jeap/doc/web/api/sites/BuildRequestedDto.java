package ch.admin.bit.jeap.doc.web.api.sites;

import ch.admin.bit.jeap.doc.domain.BuildRequest;
import ch.admin.bit.jeap.doc.domain.BuildRequestOutcome;
import ch.admin.bit.jeap.doc.domain.BuildTrigger;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Duration;
import java.time.Instant;

/**
 * What became of an ask for a build.
 * <p>
 * The answer is the same {@code 202} whether or not the ask created the request, because what the caller asked
 * for - a build of the current state - is going to happen either way. {@code requested} says which of the two it
 * was, and {@code pendingSince} is then the <i>earlier</i> request's timestamp, which is the honest answer to
 * when the site will be built.
 *
 * @param site                  the site a build was asked for
 * @param requested             whether this ask created the request, rather than joining one already pending
 * @param trigger               what asked for the request that now stands, null once it has been claimed
 * @param pendingSince          when that request was made, null once it has been claimed
 * @param picksUpWithinSeconds  how long it takes at most until an instance looks for it
 */
@Schema(description = "What became of an ask for a build")
record BuildRequestedDto(
        String site,
        boolean requested,
        BuildTrigger trigger,
        Instant pendingSince,
        long picksUpWithinSeconds) {

    static BuildRequestedDto of(String site, BuildRequestOutcome outcome, Duration pollInterval) {
        BuildRequest request = outcome.request();
        return new BuildRequestedDto(site, outcome.created(),
                request == null ? null : request.trigger(),
                request == null ? null : request.requestedAt(),
                pollInterval.toSeconds());
    }
}
