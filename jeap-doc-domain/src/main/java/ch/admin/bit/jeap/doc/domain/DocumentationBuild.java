package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.ContainerMemory;

import java.time.Duration;
import java.time.Instant;

/**
 * One run of the documentation generator, and the evidence that it happened.
 * <p>
 * The row is the record an operator reads: when the site was last generated, why that run happened, how long it
 * took, how much of it was Docusaurus, what it produced and what went wrong. It is also the publication itself -
 * the newest {@link BuildState#SUCCEEDED} build of a site is the one being served - so the identifier is both
 * the name of the run and the prefix its output lies under.
 *
 * @param id               the identifier of the build, and the prefix its site is published under
 * @param site             the site that was built
 * @param trigger          what asked for this run
 * @param state            where the build stands
 * @param startedAt        when it started
 * @param finishedAt       when it ended, null while it runs
 * @param instance         the instance that ran it, for a log search
 * @param objectPrefix     where its output lies, null unless it succeeded
 * @param pageCount        how many pages it produced
 * @param sizeInBytes      how large the published site is
 * @param docusaurusMillis how much of the run was the Docusaurus build itself
 * @param memoryPeak       what the run did to the memory of its container, or null where that cannot be read.
 *                         It is the kernel's own high-water mark rather than a sample, and it is the number a
 *                         container is sized from - almost none of a build is the JVM
 * @param failureReason    what went wrong, null unless it failed
 */
public record DocumentationBuild(
        Long id,
        String site,
        BuildTrigger trigger,
        BuildState state,
        Instant startedAt,
        Instant finishedAt,
        String instance,
        String objectPrefix,
        int pageCount,
        long sizeInBytes,
        long docusaurusMillis,
        ContainerMemory.Peak memoryPeak,
        String failureReason) {

    /**
     * How long the build took, or how long it has been running.
     */
    public Duration duration(Instant now) {
        return Duration.between(startedAt, finishedAt == null ? now : finishedAt);
    }

    /**
     * The same build as it stands once it has been given up on, for the caller of
     * {@link ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository#abandonRunning}: the row it read was
     * still {@code RUNNING}, and what it is handed back should say what is now true.
     */
    public DocumentationBuild abandonedAt(Instant finishedAt) {
        return new DocumentationBuild(id, site, trigger, BuildState.ABANDONED, startedAt, finishedAt, instance,
                objectPrefix, pageCount, sizeInBytes, docusaurusMillis, memoryPeak, failureReason);
    }
}
