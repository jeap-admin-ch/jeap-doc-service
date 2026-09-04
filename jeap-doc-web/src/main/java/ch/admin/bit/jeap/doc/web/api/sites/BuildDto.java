package ch.admin.bit.jeap.doc.web.api.sites;

import ch.admin.bit.jeap.doc.domain.BuildState;
import ch.admin.bit.jeap.doc.domain.BuildTrigger;
import ch.admin.bit.jeap.doc.domain.DocumentationBuild;
import ch.admin.bit.jeap.doc.domain.port.ContainerMemory;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * One run of the documentation generator, as an operator reads it.
 * <p>
 * The whole row, the failure reason included: it is already bounded where it is written, and a history in which
 * the failures are silent would have to be walked build by build to find out what went wrong.
 *
 * @param id               the identifier of the build, and the prefix its site is published under
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
 * @param memoryPeakBytes  the highest the container went during the run, or null where that cannot be read.
 *                         It is the number a container is sized from: almost none of a build is the JVM
 * @param memoryLimitBytes what the container is killed at, or null with the peak
 * @param memoryPeakExact  whether the peak is this build's own, or only an upper bound on it - false where the
 *                         kernel's high-water mark could not be reset and the build stayed below an earlier one
 * @param failureReason    what went wrong, null unless it failed or was given up on
 */
@Schema(description = "One run of the documentation generator")
record BuildDto(
        long id,
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
        Long memoryPeakBytes,
        Long memoryLimitBytes,
        Boolean memoryPeakExact,
        String failureReason) {

    static BuildDto of(DocumentationBuild build, Instant now) {
        ContainerMemory.Peak peak = build.memoryPeak();
        return new BuildDto(build.id(), build.trigger(), build.state(), build.startedAt(), build.finishedAt(),
                build.duration(now).toMillis(), build.instance(), build.objectPrefix(), build.pageCount(),
                build.sizeInBytes(), build.docusaurusMillis(),
                peak == null ? null : peak.usedBytes(),
                peak == null || peak.limitBytes() <= 0 ? null : peak.limitBytes(),
                peak == null ? null : peak.exact(),
                build.failureReason());
    }
}
