package ch.admin.bit.jeap.doc.web.api.sites;

import ch.admin.bit.jeap.doc.domain.BuildRequest;
import ch.admin.bit.jeap.doc.domain.BuildTrigger;
import ch.admin.bit.jeap.doc.domain.DocumentationBuild;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SiteEnvironment;
import ch.admin.bit.jeap.doc.domain.SiteStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * Where a documentation site stands: what it is configured to do, and what has actually happened.
 *
 * @param site                the identifier of the site
 * @param title               what the site calls itself
 * @param publicationSchedule the cron expression it is published on, null when it has none
 * @param publishOnUpload     whether an upload for it asks for a build
 * @param environments        its environments, in the order the switcher shows them
 * @param pending             the build owed to it, null when nothing is owed
 * @param running             the builds of it running right now
 * @param published           the build whose site is being served, null until one has succeeded
 * @param lastBuild           the newest build whatever became of it, null when it has never been built
 */
@Schema(description = "Where a documentation site stands")
record SiteStatusDto(
        String site,
        String title,
        String publicationSchedule,
        boolean publishOnUpload,
        List<String> environments,
        PendingBuildDto pending,
        List<BuildDto> running,
        BuildDto published,
        BuildDto lastBuild) {

    /**
     * A build that has been asked for and not started yet.
     *
     * @param since   when it was first asked for since the last build claimed it
     * @param trigger what asked first
     */
    @Schema(description = "A build that has been asked for and not started yet")
    record PendingBuildDto(Instant since, BuildTrigger trigger) {

        static PendingBuildDto of(BuildRequest request) {
            return request == null ? null : new PendingBuildDto(request.requestedAt(), request.trigger());
        }
    }

    static SiteStatusDto of(SiteStatus status, Instant now) {
        Site site = status.site();
        return new SiteStatusDto(
                site.id(),
                site.title(),
                site.publicationSchedule(),
                site.publishOnUpload(),
                site.environments().stream().map(SiteEnvironment::id).toList(),
                PendingBuildDto.of(status.pending()),
                status.running().stream().map(build -> BuildDto.of(build, now)).toList(),
                buildOrNull(status.published(), now),
                buildOrNull(status.lastBuild(), now));
    }

    private static BuildDto buildOrNull(DocumentationBuild build, Instant now) {
        return build == null ? null : BuildDto.of(build, now);
    }
}
