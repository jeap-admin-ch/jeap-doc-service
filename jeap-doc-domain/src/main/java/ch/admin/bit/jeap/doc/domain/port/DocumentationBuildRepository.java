package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.BuildTrigger;
import ch.admin.bit.jeap.doc.domain.DocumentationBuild;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The record of what the documentation generator has run, and - because the published site of a site is the
 * newest successful run - the publication itself.
 */
public interface DocumentationBuildRepository {

    /**
     * Records a build that is starting, and reports it with the identifier it was given. That identifier is what
     * the workspace and the published objects are named after.
     */
    DocumentationBuild start(String site, BuildTrigger trigger, String instance, Instant startedAt);

    /**
     * Records that a build produced a site and makes that site the published one - **one transaction, because
     * the two are one fact**: the state and the prefix of the site being served may never disagree.
     */
    DocumentationBuild succeeded(long id, String objectPrefix, int pageCount, long sizeInBytes,
                                 long docusaurusMillis, Instant finishedAt);

    /**
     * Records that a build did not finish. What was published before it stays published.
     */
    DocumentationBuild failed(long id, String failureReason, Instant finishedAt);

    /**
     * Records that a build was given up on because the instance running it is stopping. It is not a failure -
     * nothing about the generator is wrong - and the build is asked for again by the instance that aborts it.
     */
    DocumentationBuild aborted(long id, String reason, Instant finishedAt);

    /**
     * Marks the builds of a site that are still running although they cannot be, because the caller holds that
     * site's lock and their lease has therefore expired. Reports <b>which</b> they were: more than none means an
     * instance died mid-build, which is worth a metric, and what triggered them decides whether the site is
     * built again straight away - see {@link ch.admin.bit.jeap.doc.domain.BuildTrigger#RECOVERY}.
     */
    List<DocumentationBuild> abandonRunning(String site, Instant finishedAt);

    /**
     * The sites that have a build still marked as running, whichever instance started it.
     * <p>
     * It is what makes a crashed build recoverable without anything having been written on the way down: the row
     * outlives the instance, and a site named here whose lock can be taken is a site that owes a build.
     */
    Set<String> sitesWithRunningBuilds();

    /**
     * The builds that are running right now, whichever site and instance they belong to, newest first.
     * <p>
     * The whole rows, where {@link #sitesWithRunningBuilds()} and {@link #runningIds()} are the projections the
     * runner and the workspace clean-up ask for. This one is read by the administration API, which shows what an
     * operator would otherwise look up in the database - who is building what, since when, and on which instance.
     */
    List<DocumentationBuild> running();

    /**
     * The most recent builds of a site, newest first, at most {@code limit} of them.
     */
    List<DocumentationBuild> recent(String site, int limit);

    /**
     * One build of one site.
     * <p>
     * By site <b>and</b> identifier, because the identifier comes from a sequence shared by every site: reading
     * it by identifier alone would let the URL of one site answer with a build of another.
     */
    Optional<DocumentationBuild> find(String site, long id);

    /**
     * The build whose site is currently served, if any has ever succeeded.
     */
    Optional<DocumentationBuild> published(String site);

    /**
     * When each site was last published, for the age gauge - read from the database so that it survives a
     * restart and reads the same on every instance.
     */
    Optional<Instant> lastSuccessAt(String site);

    /**
     * The prefixes of the sites of a site that are past the retention, newest first beyond the ones to keep.
     * They are what the publication deletes after it has made a new one current.
     */
    List<String> prefixesBeyondRetention(String site, int keep);

    /**
     * Records that the objects of a published site have been removed, so that the retention does not offer the
     * same prefix again on every build.
     */
    void forgetObjectPrefix(String objectPrefix);

    /**
     * The identifiers of every build that is still running, whichever site and instance it belongs to. It is
     * what the workspace clean-up asks: a directory named after one of these is in use.
     */
    Set<Long> runningIds();

    /**
     * Removes the record of builds that finished before the given instant, and reports how many.
     * <p>
     * The builds named in {@code keep} are spared whatever their age. That is not a nicety: the newest
     * successful build of a site <b>is</b> its publication, so a site that is published rarely - one that is only
     * ever built when something is uploaded to it - would otherwise lose the row that says what is being served,
     * and start answering that it has never been generated.
     */
    int deleteFinishedBefore(Instant finishedBefore, Set<Long> keep);
}
