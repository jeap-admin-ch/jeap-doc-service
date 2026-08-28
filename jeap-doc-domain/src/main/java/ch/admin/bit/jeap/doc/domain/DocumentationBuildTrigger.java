package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * Asks for a documentation site to be published.
 * <p>
 * Everything that wants a site rebuilt comes through here - an upload, the schedule - so there is exactly one
 * path to a build and the collapsing rule covers all of them: a request that is already pending is left alone,
 * and however many triggers arrive while a build runs, the next run serves all of them at once.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentationBuildTrigger {

    private final DocumentationBuildRequestRepository requests;
    private final DocumentationSites sites;
    private final Clock clock;

    /**
     * Asks for a build of the given site because something was uploaded to it, unless that site does not want to
     * be published on upload.
     */
    public void requestBecauseOfUpload(String site) {
        sites.find(site)
                .filter(Site::publishOnUpload)
                .ifPresent(configured -> request(configured.id(), BuildTrigger.UPLOAD));
    }

    /**
     * Asks for a build of the given site because its schedule came round.
     */
    public void requestBecauseOfSchedule(String site) {
        request(site, BuildTrigger.SCHEDULE);
    }

    /**
     * Asks for a build of the given site because somebody asked for one over the administration API, and reports
     * what became of the request.
     * <p>
     * Unlike {@link #requestBecauseOfUpload} this does <b>not</b> ask whether the site wants to be published on
     * upload: a site that is published only when something is uploaded to it is exactly the site somebody has to
     * be able to publish by hand. Whether the site exists at all is decided by the caller, so that an unknown one
     * is refused rather than silently dropped.
     */
    public BuildRequestOutcome requestBecauseAnOperatorAsked(String site) {
        boolean created = request(site, BuildTrigger.MANUAL);
        return new BuildRequestOutcome(created, standingRequestFor(site));
    }

    /**
     * The request as it stands after the ask - the one just created, or the earlier one this ask joined.
     * <p>
     * It can be gone by the time it is read: the runner polls, and a request claimed in between is a build that
     * has already started. That is not worth a lock over, so the outcome says the request is no longer pending
     * and means it.
     */
    private BuildRequest standingRequestFor(String site) {
        return requests.pending().stream()
                .filter(request -> request.site().equals(site))
                .findFirst()
                .orElse(null);
    }

    private boolean request(String site, BuildTrigger trigger) {
        if (requests.request(site, trigger, clock.instant())) {
            log.info("A build of the documentation site {} was asked for by {}.", site, trigger);
            return true;
        }
        log.debug("A build of the documentation site {} is already pending; the {} trigger joins it.",
                site, trigger);
        return false;
    }
}
