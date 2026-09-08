package ch.admin.bit.jeap.doc.web.api.sites;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Duration;

/**
 * What became of an ask to publish a whole site.
 * <p>
 * A site is published as several builds, so the honest answer is not <i>a build was requested</i> but <i>this
 * many parts were</i>. None of them runs on this request: they are picked up within the poll interval.
 *
 * @param site                 the site that was asked for
 * @param partsRequested       how many parts are owed a build
 * @param picksUpWithinSeconds how long it takes at most until an instance picks a build up
 */
@Schema(description = "What became of an ask to publish a site")
record PublicationRequestedDto(
        String site,
        int partsRequested,
        long picksUpWithinSeconds) {

    static PublicationRequestedDto of(String site, int parts, Duration pollInterval) {
        return new PublicationRequestedDto(site, parts, pollInterval.toSeconds());
    }
}
