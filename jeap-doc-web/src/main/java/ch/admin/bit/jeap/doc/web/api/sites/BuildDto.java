package ch.admin.bit.jeap.doc.web.api.sites;

import ch.admin.bit.jeap.doc.domain.BuildState;
import ch.admin.bit.jeap.doc.domain.BuildTrigger;
import ch.admin.bit.jeap.doc.domain.DocumentationBuild;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * One run of the documentation generator, as an operator reads it.
 * <p>
 * The whole row, the failure reason included: it is already bounded where it is written, and a history in which
 * the failures are silent would have to be walked build by build to find out what went wrong.
 *
 * @param id               the identifier of the build, and the prefix its site is published under
 * @param part             the part of the site this run produced. A site is published as several builds, so a
 *                         history of the whole site cannot be read without it
 * @param trigger          what asked for this run
 * @param state            where it stands
 * @param startedAt        when it started
 * @param finishedAt       when it ended, null while it runs
 * @param durationMillis   how long it took, or how long it has been running
 * @param instance         the instance that ran it, for a log search
 * @param objectPrefix     where its output lies, null unless it succeeded
 * @param pageCount        how many pages it produced
 * @param sizeInBytes      how large the published site is
 * @param docusaurusMillis how much of the run was the Docusaurus build itself
 * @param contentDigest    what the content this run wrote hashes to, null unless it got that far. It is what
 *                         a later run compares against to decide whether the part has to be generated at all
 * @param failureReason    what went wrong, null unless it failed or was given up on
 */
@Schema(description = "One run of the documentation generator")
record BuildDto(
        long id,
        String part,
        BuildTrigger trigger,
        BuildState state,
        Instant startedAt,
        Instant finishedAt,
        long durationMillis,
        String instance,
        String objectPrefix,
        int pageCount,
        long sizeInBytes,
        long docusaurusMillis,
        String contentDigest,
        String failureReason) {

    static BuildDto of(DocumentationBuild build, Instant now) {
        return new BuildDto(build.id(), build.part(), build.trigger(), build.state(), build.startedAt(),
                build.finishedAt(), build.duration(now).toMillis(), build.instance(), build.objectPrefix(),
                build.pageCount(), build.sizeInBytes(), build.docusaurusMillis(), build.contentDigest(),
                build.failureReason());
    }
}
