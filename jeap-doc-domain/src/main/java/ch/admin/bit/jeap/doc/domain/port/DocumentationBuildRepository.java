package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.BuildTrigger;
import ch.admin.bit.jeap.doc.domain.DocumentationBuild;
import ch.admin.bit.jeap.doc.domain.PartKey;
import ch.admin.bit.jeap.doc.domain.Publication;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The record of what the documentation generator has run, and - because what is published for a part is the
 * newest successful run of it - the publication itself.
 * <p>
 * Everything here is keyed by the <b>part</b>: a site is published as several builds, one per part, and each of
 * them is published, superseded and retained on its own.
 */
public interface DocumentationBuildRepository {

    /**
     * Records a build that is starting, and reports it with the identifier it was given. That identifier is what
     * the workspace and the published objects are named after.
     */
    DocumentationBuild start(PartKey part, BuildTrigger trigger, String instance, Instant startedAt,
                             Publication publication);

    /**
     * Records that a build produced a site and makes that site the published one - **one transaction, because
     * the two are one fact**: the state and the prefix of the site being served may never disagree.
     */
    DocumentationBuild succeeded(long id, String objectPrefix, int pageCount, long sizeInBytes,
                                 long docusaurusMillis, String contentDigest,
                                 Instant finishedAt);

    /**
     * Records that a build did not finish. What was published before it stays published.
     */
    DocumentationBuild failed(long id, String failureReason,
                              Instant finishedAt);

    /**
     * Records that a build was not run at all, because the content of its part is what is already published.
     * Nothing is published and nothing is wrong - see {@link ch.admin.bit.jeap.doc.domain.BuildState#SKIPPED}.
     */
    DocumentationBuild skipped(long id, Instant finishedAt);

    /**
     * Records that a build was given up on because the instance running it is stopping. It is not a failure -
     * nothing about the generator is wrong - and the build is asked for again by the instance that aborts it.
     */
    DocumentationBuild aborted(long id, String reason, Instant finishedAt);

    /**
     * Marks the builds of a part that are still running although they cannot be, because the caller holds that
     * part's lock and their lease has therefore expired. Reports <b>which</b> they were: more than none means an
     * instance died mid-build, which is worth a metric, and what triggered them decides whether the part is
     * built again straight away - see {@link ch.admin.bit.jeap.doc.domain.BuildTrigger#RECOVERY}.
     */
    List<DocumentationBuild> abandonRunning(PartKey part, Instant finishedAt);

    /**
     * The parts that have a build still marked as running, whichever instance started it.
     * <p>
     * It is what makes a crashed build recoverable without anything having been written on the way down: the row
     * outlives the instance, and a part named here whose lock can be taken is a part that owes a build.
     */
    Set<PartKey> partsWithRunningBuilds();

    /**
     * The builds that are running right now, whichever site and instance they belong to, newest first.
     * <p>
     * The whole rows, where {@link #partsWithRunningBuilds()} and {@link #runningIds()} are the projections the
     * runner and the workspace clean-up ask for. This one is read by the administration API, which shows what an
     * operator would otherwise look up in the database - who is building what, since when, and on which instance.
     */
    List<DocumentationBuild> running();

    /**
     * The most recent builds of a site, every part of it, newest first, at most {@code limit} of them.
     */
    List<DocumentationBuild> recent(String site, int limit);

    /**
     * The most recent builds of one part, newest first, at most {@code limit} of them.
     */
    List<DocumentationBuild> recentOf(PartKey part, int limit);

    /**
     * One build of one site.
     * <p>
     * By site <b>and</b> identifier, because the identifier comes from a sequence shared by every site: reading
     * it by identifier alone would let the URL of one site answer with a build of another.
     */
    Optional<DocumentationBuild> find(String site, long id);

    /**
     * The build of this part that is currently served, if one has ever succeeded.
     */
    Optional<DocumentationBuild> published(PartKey part);

    /**
     * What is published for every part of a site: which part, where its files are, and what they were made of.
     * <p>
     * <b>This is what resolves a request.</b> A path is served out of the publication of the part that owns it,
     * so this is asked - behind a short cache - for every request that is not answered from that cache.
     */
    List<PublishedPart> publishedPartsOf(String site);

    /**
     * What the whole site adds up to as it is published: the parts, their pages and their size.
     * <p>
     * One query rather than one per part. It is what the gauges of a site read, and a site of two hundred parts
     * must not cost two hundred statements every time it is scraped.
     */
    PublicationTotals publishedTotalsOf(String site);

    /**
     * When any part of this site was last published, for the age gauge - read from the database so that it
     * survives a restart and reads the same on every instance.
     */
    Optional<Instant> lastSuccessAt(String site);

    /**
     * When any part of this site was last <b>confirmed current</b>: published, or found to hash to exactly
     * what is already published.
     * <p>
     * <b>Not the same question as {@link #lastSuccessAt}, and this is the one to alarm on.</b> Since a part
     * whose content has not moved is not generated at all, a site nobody changes goes days without a
     * publication - correctly - and its last success ages without bound. What says the service is still
     * going through this site's parts is a build that reached either of the two outcomes meaning <i>this part
     * is up to date</i>. A failed build is not one of them: it says the part is not up to date, and the
     * failure alarm is what it belongs to.
     */
    Optional<Instant> lastCheckAt(String site);

    /**
     * When the <b>oldest</b> published part of this site was published, or empty when the site has no
     * publication at all.
     * <p>
     * The gauge an operator watches: the newest publication says the generator is running, and only the oldest
     * one says whether a part has quietly stopped being rebuilt.
     */
    Optional<Instant> oldestPublicationAt(String site);

    /**
     * The prefixes of the sites of a site that are past the retention, newest first beyond the ones to keep.
     * They are what the publication deletes after it has made a new one current.
     */
    List<String> prefixesBeyondRetention(PartKey part, int keep);

    /**
     * The newest full publication of this site that is <b>over</b>: nothing of it is still building and nothing
     * of it is still owed a build.
     * <p>
     * Read from the database rather than remembered, for the reason every cross-instance number here is: the
     * parts of a publication are built on several instances, so no single one of them knows when the last of
     * them finished - and an instance that has just restarted knows nothing at all.
     */
    Optional<CompletedPublication> lastCompletedPublicationOf(String site);

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
     * <b>The newest succeeded build of a part is never removed</b>, whatever its age: that row <i>is</i> the
     * part's publication, and without it nothing names what is being served. A part whose content does not
     * move is not rebuilt at all, so its publication is routinely older than the retention. The rule belongs
     * to this method rather than to a set of identifiers a caller passes in, because a caller deriving that
     * set from anything but the build rows can get it wrong.
     */
    int deleteFinishedBefore(Instant finishedBefore);

    /**
     * Removes every build record of one part, and reports how many there were.
     * <p>
     * For a part that has left the site: the axis was re-cut, or the system the part documented is no longer
     * in the architecture model. {@link #deleteFinishedBefore} spares the newest succeeded row of every part
     * whatever its age, which is what keeps a part nobody rebuilds served - so a part that will never be
     * rebuilt again needs somebody to say so, and this is that.
     * <p>
     * The objects it published are removed first. This is what makes the site stop offering it.
     */
    int forgetPart(PartKey part);
}
