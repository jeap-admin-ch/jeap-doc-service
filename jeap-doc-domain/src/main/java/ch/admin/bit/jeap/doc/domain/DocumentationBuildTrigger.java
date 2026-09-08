package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource;
import ch.admin.bit.jeap.doc.domain.port.BuildMetrics;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

/**
 * Asks for a part of a documentation site to be published.
 * <p>
 * Everything that wants documentation rebuilt comes through here - an upload, the architecture import, an
 * operator - so there is exactly one path to a build and the collapsing rule covers all of them: a request
 * that is already pending is left alone, and however many triggers arrive while a build runs, the next run
 * serves all of them at once.
 * <p>
 * <b>A trigger also asks this instance to look now</b>, rather than at its next poll - see
 * {@link DocumentationBuildPickup}. It is advisory: the request is what a build rests on.
 * <p>
 * <b>What may not be skipped is decided here.</b> An operator's ask carries {@code forced}, so the build it
 * leads to is run whether the content moved or not; an upload and an import do not, because their whole
 * subject is content that moved and the digest is right about them. It is a field of the request rather than
 * a reading of the trigger, since a request already pending keeps the trigger that asked first.
 * <p>
 * <b>A trigger that asks for every part of a site mints a {@link Publication}</b> and puts its identifier on
 * every one of those requests, so that the wall clock of a full publication is a thing the service can measure
 * across its instances. An upload does not: one part is not a publication.
 * <p>
 * <b>Only the upload names one part.</b> An import asks for the whole site: which
 * systems its landscape changed is a question this variant does not ask, because a part is a system and a
 * system is quick to generate - so asking is more machinery than rebuilding.
 * <p>
 * <b>What a trigger names is a thing, not a part.</b> An upload names a system, the import names a system in an
 * environment; which part carries it is the {@link SitePartition}'s answer, and that is the whole reason this
 * class talks to one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentationBuildTrigger {

    private final DocumentationBuildRequestRepository requests;
    private final DocumentationSites sites;
    private final SitePartition partition;
    private final ArchitectureModelSource architectureModel;
    private final BuildMetrics metrics;
    private final DocumentationBuildPickup pickup;
    private final Clock clock;

    /**
     * Asks for the documentation of one system to be published, because something was uploaded for it - unless
     * that site does not want to be published on upload.
     * <p>
     * <b>An upload names no environment</b> ({@code DocumentationUploadDescriptor} carries the site, the system,
     * the component and the version), so the partition decides which parts that touches. With a part per system
     * it is exactly one.
     */
    public void requestBecauseOfUpload(String site, String system) {
        sites.find(site)
                .filter(Site::publishOnUpload)
                .ifPresent(configured -> {
                    List<SitePart> parts = partition.partsDocumenting(configured, null, system);
                    // No publication: an upload asks for one part, and one part is not a publication. Not
                    // forced either - an upload changes the content, so the digest decides and is right.
                    requestParts(parts, BuildTrigger.UPLOAD, null, false);
                    metrics.triggered(configured.id(), BuildTrigger.UPLOAD, parts.size());
                });
    }

    /**
     * Asks for the documentation of one environment's landscape to be published, and reports how many parts
     * that was.
     * <p>
     * <b>Every part of it</b>: the import knows the environment and nothing finer, on purpose. What each part
     * then costs is decided by its content - a part whose pages hash to what is published is not generated -
     * so the price of not knowing which systems moved is the content of every part, and not a site rebuilt.
     * <p>
     * Every site that has this environment, because an environment is not a site's own: one landscape can be
     * documented by several of them.
     */
    public int requestBecauseTheModelWasImported(String environment) {
        int requested = 0;
        for (Site site : sites.all()) {
            if (site.environments().stream().noneMatch(each -> each.id().equals(environment))) {
                continue;
            }
            List<SitePart> parts = partition.partsOf(site);
            requestParts(parts, BuildTrigger.IMPORT, Publication.askedAt(clock.instant()), false);
            metrics.triggered(site.id(), BuildTrigger.IMPORT, parts.size());
            requested += parts.size();
        }
        return requested;
    }

    /**
     * Asks for every part of every site that no import publishes, and reports how many parts that was.
     * <p>
     * A site whose environments have no architecture repository behind them is asked for by no import, so its
     * only other triggers are an upload and an operator. The content digest covers the service version, so
     * such a site would keep serving what an earlier release generated - a template change would reach it only
     * when somebody uploaded something. Nearly free all the same: a part whose content has not moved is not
     * generated.
     * <p>
     * Sites an import does publish are left alone here, so that a landscape is not asked for twice.
     */
    public int requestBecauseNothingElsePublishesTheSite() {
        int requested = 0;
        for (Site site : sites.all()) {
            if (isPublishedByAnImport(site)) {
                continue;
            }
            List<SitePart> parts = partition.partsOf(site);
            requestParts(parts, BuildTrigger.SCHEDULE, Publication.askedAt(clock.instant()), false);
            metrics.triggered(site.id(), BuildTrigger.SCHEDULE, parts.size());
            requested += parts.size();
        }
        return requested;
    }

    private boolean isPublishedByAnImport(Site site) {
        return site.environments().stream()
                .anyMatch(environment -> architectureModel.isConfiguredFor(environment.id()));
    }

    /**
     * Asks for one part to be published because somebody asked for it over the administration API, and reports
     * what became of the request.
     * <p>
     * Unlike {@link #requestBecauseOfUpload} this does <b>not</b> ask whether the site wants to be published on
     * upload: a site that is published only when something is uploaded to it is exactly the site somebody has to
     * be able to publish by hand. Whether the site and the part exist at all is decided by the caller, so that
     * an unknown one is refused rather than silently dropped.
     */
    public BuildRequestOutcome requestBecauseAnOperatorAsked(PartKey part) {
        boolean created = request(part, BuildTrigger.MANUAL, null, true);
        pickup.whenAskedFor();
        return new BuildRequestOutcome(created, standingRequestFor(part));
    }

    /**
     * Asks for <b>every</b> part of a site to be published, whether its content has moved or not.
     * <p>
     * What an operator gets when they force a publication: the digest is not consulted, because the reason to
     * force one is that something outside the content changed - the site template while it is being worked on,
     * most of all. Answers the parts that were asked for.
     */
    public List<SitePart> requestEveryPart(String site) {
        return sites.find(site)
                .map(configured -> {
                    List<SitePart> all = partition.partsOf(configured);
                    requestParts(all, BuildTrigger.MANUAL, Publication.askedAt(clock.instant()), true);
                    metrics.triggered(configured.id(), BuildTrigger.MANUAL, all.size());
                    return all;
                })
                .orElseGet(List::of);
    }

    /**
     * The request as it stands after the ask - the one just created, or the earlier one this ask joined.
     * <p>
     * It can be gone by the time it is read: the runner polls, and a request claimed in between is a build that
     * has already started. That is not worth a lock over, so the outcome says the request is no longer pending
     * and means it.
     */
    private BuildRequest standingRequestFor(PartKey part) {
        return requests.pending().stream()
                .filter(request -> request.part().equals(part))
                .findFirst()
                .orElse(null);
    }

    /**
     * One wake-up for the lot, and after the requests are written: an import asks for fifty parts, and fifty
     * wake-ups would be forty-nine passes that find what the first one is already building.
     * <p>
     * <b>One transaction for the lot too.</b> The parts of a publication have to become owed together, or an
     * instance that sees the first of them and none of the rest builds it, finds nothing else owed, and
     * reports a publication that took seconds - see {@code DocumentationBuildRequestRepository.requestAll}.
     */
    private void requestParts(List<SitePart> parts, BuildTrigger trigger, Publication publication,
                              boolean forced) {
        if (parts.isEmpty()) {
            return;
        }
        int created = requests.requestAll(parts.stream().map(SitePart::key).toList(), trigger,
                clock.instant(), publication, forced);
        log.info("A build of {} part(s) of {} was asked for by {}; {} of them were already pending.",
                parts.size(), parts.getFirst().site(), trigger, parts.size() - created);
        pickup.whenAskedFor();
    }

    private boolean request(PartKey part, BuildTrigger trigger, Publication publication, boolean forced) {
        if (requests.request(part, trigger, clock.instant(), publication, forced)) {
            log.info("A build of {} was asked for by {}.", part, trigger);
            return true;
        }
        log.debug("A build of {} is already pending; the {} trigger joins it{}.", part, trigger,
                forced ? " and it may no longer be skipped" : "");
        return false;
    }
}
