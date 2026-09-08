package ch.admin.bit.jeap.doc.domain;


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
 * @param part             the part of that site this build produced - see {@link SitePart}
 * @param trigger          what asked for this run
 * @param state            where the build stands
 * @param startedAt        when it started
 * @param finishedAt       when it ended, null while it runs
 * @param instance         the instance that ran it, for a log search
 * @param objectPrefix     where its output lies, null unless it succeeded
 * @param pageCount        how many pages it produced
 * @param sizeInBytes      how large the published site is
 * @param docusaurusMillis how much of the run was the Docusaurus build itself
 * @param failureReason    what went wrong, null unless it failed
 * @param contentDigest    what the generated content of this part hashed to, null unless it succeeded. A build
 *                         whose content hashes to this is not run again
 */
public record DocumentationBuild(
        Long id,
        String site,
        String part,
        BuildTrigger trigger,
        BuildState state,
        Instant startedAt,
        Instant finishedAt,
        String instance,
        String objectPrefix,
        int pageCount,
        long sizeInBytes,
        long docusaurusMillis,
        String failureReason,
        String contentDigest) {

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
        return new DocumentationBuild(id, site, part, trigger, BuildState.ABANDONED, startedAt, finishedAt,
                instance, objectPrefix, pageCount, sizeInBytes, docusaurusMillis, failureReason,
                contentDigest);
    }

    /** What this build is called where a site and a part have to read as one name: a log line, a lock. */
    public String qualifiedName() {
        return site + "/" + part;
    }
}
