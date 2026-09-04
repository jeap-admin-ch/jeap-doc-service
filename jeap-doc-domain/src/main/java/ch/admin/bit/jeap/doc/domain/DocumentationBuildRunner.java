package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.BuildMetrics;
import ch.admin.bit.jeap.doc.domain.port.BuiltSite;
import ch.admin.bit.jeap.doc.domain.port.ContainerMemory;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRequestRepository;
import ch.admin.bit.jeap.doc.domain.port.DocumentationStatus;
import ch.admin.bit.jeap.doc.domain.port.ExclusiveWork;
import ch.admin.bit.jeap.doc.domain.port.PublishedSite;
import ch.admin.bit.jeap.doc.domain.port.SiteBuilder;
import ch.admin.bit.jeap.doc.domain.port.SitePublicationStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Publishes the documentation sites that have been asked for, one at a time per site.
 * <p>
 * Three things about the order of a run are what the rest of this rests on:
 * <ul>
 *   <li><b>The lock is taken before the request is claimed.</b> The other way round loses requests: an instance
 *   that clears the flag and then finds the lock held has thrown a build request away, and nobody will ask again
 *   until the next upload or the next schedule.</li>
 *   <li><b>The request is claimed before anything is read.</b> Every trigger arriving from then on finds the flag
 *   clear and sets it again, so a burst of triggers during a build produces exactly one follow-up run.</li>
 *   <li><b>One build per tick per instance.</b> A build is a process that wants a core; three pending sites must
 *   not become three of them inside one container. Different instances still build different sites at the same
 *   time, which is what the per-site lock is for.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentationBuildRunner {

    /** The prefix of the lock a site's build holds, so that two sites do not wait for each other. */
    static final String LOCK_PREFIX = "documentationBuild-";

    /**
     * How many poll intervals a request for a site this instance does not know is left alone. An instance not
     * knowing a site is not evidence that no instance does - during a rolling deployment that adds one, half
     * the instances have it and half do not.
     */
    static final int FORGET_UNKNOWN_REQUESTS_AFTER_POLLS = 20;

    private final DocumentationBuildRequestRepository requests;
    private final DocumentationBuildRepository builds;
    private final DocumentationSites sites;
    private final SiteBuilder siteBuilder;
    private final SitePublicationStorage publication;
    private final BuildProperties properties;
    private final BuildMetrics metrics;
    private final ExclusiveWork exclusiveWork;
    private final ArchitectureModelReadiness readiness;
    private final ContainerMemory containerMemory;
    private final Clock clock;

    /**
     * Held for as long as a tick is running, so that a stopping instance can wait for the bookkeeping of the
     * build it just gave up on. Acquiring it is the proof that this runner is idle - and idle here means the
     * terminal state was written, not merely that the site generator has stopped.
     */
    private final ReentrantLock ticking = new ReentrantLock();

    /** Set once this instance is stopping: from then on no tick starts a build. */
    private volatile boolean stopping;

    /**
     * Publishes at most one site, and reports whether it did. Called on a fixed delay, and by the tests directly.
     */
    public boolean runOnce() {
        if (stopping) {
            return false;
        }
        ticking.lock();
        try {
            // Checked again inside: the instance may have started stopping while this tick waited for the one
            // before it, and a build started now would be given up on immediately.
            return !stopping && runTick();
        } finally {
            ticking.unlock();
        }
    }

    /**
     * Stops this instance from starting further builds, and reports whether it was the call that did it.
     * <p>
     * One way only. It is called while the service is stopping, and there is no state in which a stopping
     * service should start generating a documentation site.
     */
    boolean stopAcceptingBuilds() {
        boolean wasRunning = !stopping;
        stopping = true;
        return wasRunning;
    }

    /**
     * Waits until this runner is between ticks, for at most the given time, and reports whether it got there.
     * <p>
     * Returning true means the build that was in flight has finished writing what it had to write - which is
     * what a stopping instance needs to know before it lets its beans be destroyed.
     */
    boolean awaitIdle(Duration atMost) throws InterruptedException {
        if (ticking.tryLock(atMost.toMillis(), TimeUnit.MILLISECONDS)) {
            ticking.unlock();
            return true;
        }
        return false;
    }

    private boolean runTick() {
        for (String site : sitesOwedABuild()) {
            Optional<Site> configured = sites.find(site);
            if (configured.isEmpty()) {
                forgetSiteThatIsGone(site);
                continue;
            }
            // Readiness is checked before the request is claimed, and short-circuits the build: claiming it and
            // then declining to build would throw it away, and nothing would ask again until the next upload or
            // the next schedule.
            if (readiness.isReadyToBuild(configured.get()) && buildUnderLock(site)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drops everything that still says a site nobody configures any more owes a build.
     * <p>
     * The request is the obvious half. The other half is a run that was left behind when the site was removed:
     * without giving up on it, the row stays {@code RUNNING} for ever, its identifier keeps its workspace from
     * being swept, and this warning is logged on every tick until someone notices.
     * <p>
     * <b>Under the site's lock all the same</b>, because the sites are configured per instance: during a rolling
     * deployment that removes a site, the instances that still have it are entitled to be building it. Giving up
     * on a run without the lock would mark a live build as abandoned, and its instance would then record it as
     * succeeded over a failure reason saying its instance had stopped.
     */
    private void forgetSiteThatIsGone(String site) {
        exclusiveWork.underLock(LOCK_PREFIX + site, properties.getLockLease(), () -> forgetUnderLock(site));
    }

    /**
     * Reports each of the two separately, because they say different things to whoever reads the log: a run
     * that never finished, and a request nobody served.
     */
    private boolean forgetUnderLock(String site) {
        int abandoned = builds.abandonRunning(site, clock.instant()).size();
        if (abandoned > 0) {
            metrics.abandoned(site, abandoned);
            log.warn("{} run(s) of the site {} never finished, and no such site is configured any more; they "
                     + "are given up on.", abandoned, site);
        }
        // Only a request that no instance has served for a long time. This instance not knowing the site does
        // not mean no instance does: during a rolling deployment that *adds* a site, the instances that have it
        // are serving its requests while the ones that do not would otherwise delete them - and a claimed
        // request is gone, so the build would never run and nothing would say why.
        boolean requestDropped = requests.pendingSince(site)
                .filter(since -> since.isBefore(clock.instant().minus(forgetRequestsAfter())))
                .map(since -> requests.claim(site).isPresent())
                .orElse(false);
        if (requestDropped) {
            log.warn("A build of the site {} was asked for, no such site is configured any more, and no "
                     + "instance picked it up for {}; the request is dropped.", site, forgetRequestsAfter());
        }
        return requestDropped || abandoned > 0;
    }

    /**
     * How long a request for a site this instance does not know is left alone before it is treated as junk:
     * long enough that no rolling deployment is still in progress, short enough that a site genuinely removed
     * does not leave its request growing the age gauge for ever.
     */
    private Duration forgetRequestsAfter() {
        return properties.getPollInterval().multipliedBy(FORGET_UNKNOWN_REQUESTS_AFTER_POLLS);
    }

    /**
     * The sites that may owe a build, oldest request first, and then the ones that only a leftover row says
     * anything about.
     * <p>
     * The second half is the recovery: a build whose instance died was claimed when it started, so nothing asks
     * for it any more and the request cannot be what says it is owed. <b>The row that is still {@code RUNNING}
     * is.</b> Whether it really is stale is not decided here - it is decided by whether its site's lock can be
     * taken, which only succeeds once the dead instance's lease has run out.
     */
    private List<String> sitesOwedABuild() {
        List<String> owed = new ArrayList<>(requests.pending().stream().map(BuildRequest::site).toList());
        for (String site : builds.sitesWithRunningBuilds()) {
            if (!owed.contains(site)) {
                owed.add(site);
            }
        }
        return owed;
    }

    /**
     * Takes the site's lock and builds it - or returns without doing anything when another instance holds it, in
     * which case the request stays pending and is served after that build.
     * <p>
     * The lease is far shorter than a build may take, because the lock is extended while the build runs. What it
     * sizes is how long a killed instance blocks its site.
     */
    private boolean buildUnderLock(String site) {
        return exclusiveWork.underLock(LOCK_PREFIX + site, properties.getLockLease(), () -> claimAndBuild(site))
                .orElse(false);
    }

    private boolean claimAndBuild(String site) {
        BuildTrigger trigger = whatThisSiteIsOwed(site);
        if (trigger == null) {
            return false;
        }
        build(sites.find(site).orElseThrow(), trigger);
        return true;
    }

    /**
     * Why this site is built now, or null if it turns out not to be owed anything after all - the request was
     * claimed by another instance between the poll and the lock, and no run was left half-finished.
     * <p>
     * Order matters twice over. <b>Giving up on the stale runs comes first</b>, because whether the site owes a
     * recovery is read from what that gives back. <b>Claiming comes before anything is read</b>, so that every
     * trigger arriving from now on finds the flag clear and sets it again, and a burst during a build produces
     * exactly one follow-up run.
     */
    private BuildTrigger whatThisSiteIsOwed(String site) {
        // Holding this site's lock means any build of it that is still marked as running has lost its lease, so
        // it is a run whose instance disappeared rather than one in progress.
        List<DocumentationBuild> abandoned = builds.abandonRunning(site, clock.instant());
        if (!abandoned.isEmpty()) {
            log.warn("{} build(s) of the site {} were still marked as running and have been given up on: the "
                     + "instance running them stopped.", abandoned.size(), site);
            metrics.abandoned(site, abandoned.size());
        }

        Optional<BuildTrigger> claimed = requests.claim(site);
        if (claimed.isPresent()) {
            return claimed.get();
        }
        if (abandoned.isEmpty()) {
            return null;
        }
        if (abandoned.stream().anyMatch(build -> build.trigger() == BuildTrigger.RECOVERY)) {
            // Twice in a row is a build that kills whatever runs it. Repeating it would be a crash loop, so the
            // site waits for an upload or its schedule instead.
            log.error("The site {} lost a build that was already a recovery attempt; it is not run again "
                      + "automatically. Something about this build is stopping the instance running it.", site);
            return null;
        }
        return BuildTrigger.RECOVERY;
    }

    private void build(Site site, BuildTrigger trigger) {
        DocumentationBuild build = builds.start(site.id(), trigger, instanceName(), clock.instant());
        log.info("Publishing the documentation site {} ({}), asked for by {}.", site.id(), build.id(), trigger);
        long startedAt = System.nanoTime();
        // From here to the end of the build, so that what the container held is this build's rather than the
        // container's history. It is the kernel's own high-water mark, not a sample: nothing runs while the
        // build does, and nothing between two readings is missed.
        ContainerMemory.Measurement memory = containerMemory.measure();
        // Past a publication nothing may take it back, so what follows one is deliberately outside the block
        // that can turn a build into a failure: a database hiccup while measuring the build or clearing away
        // what it superseded would otherwise rewrite a published build as failed - or, while stopping, as
        // aborted, and delete the very objects the row points at.
        Published result = publish(site, build, trigger, startedAt, memory);
        if (result != null) {
            afterPublishing(site, build, trigger, result, startedAt);
        }
    }

    /**
     * Generates the site, puts it in the object storage and records the build as the published one - or records
     * why it is not, and reports nothing.
     */
    private Published publish(Site site, DocumentationBuild build, BuildTrigger trigger, long startedAt,
                              ContainerMemory.Measurement memory) {
        try {
            siteBuilder.sweepWorkspaces(builds.runningIds());
            Instant generatedAt = clock.instant();
            BuiltSite generated = siteBuilder.generate(build.id(), site, generatedAt);
            // Read once, here, and used by all three of the places that report this build: the file beside the
            // site, the row, and the line that says it was published. The kernel's mark only rises, so reading
            // it again after the upload would give the row a higher number than the file it was written beside
            // - two numbers for one build, and an operator comparing them for nothing.
            ContainerMemory.Peak peak = memory.peak().orElse(null);
            // The seam: the numbers of this run exist now, the site is still on local disk, and the page that
            // prints them was written at the start of the run. So they are written into the output before the
            // upload, and the page fetches them - see DocumentationStatus.
            siteBuilder.describeRun(generated, DocumentationStatus.of(build.id(), generatedAt,
                    elapsed(startedAt).toMillis(), generated, peak));
            PublishedSite published = publication.publish(prefixOf(site, build.id()), generated.directory());
            builds.succeeded(build.id(), published.prefix(), generated.pageCount(), published.sizeInBytes(),
                    generated.docusaurusMillis(), peak, clock.instant());
            return new Published(generated, published, peak);
        } catch (RuntimeException e) {
            if (stopping) {
                // Not a failure: this instance asked the generator to stop. Recorded apart from one, because
                // the alarm is on failures and a deployment landing on a build must not page anybody.
                recordAbort(site, build, trigger, e, startedAt);
            } else {
                // On the row and in the prose of the reason, from one reading: a build killed for want of
                // memory exits with a number, and 'how close did it come' belongs beside that number - as a
                // column an operator can compare, and as a sentence in the reason they read first.
                ContainerMemory.Peak peak = memory.peak().orElse(null);
                builds.failed(build.id(), e.getMessage() + memoryClause(peak, ". "), peak, clock.instant());
                log.error("The documentation site {} ({}) could not be published; the site published before it "
                          + "is still being served.", site.id(), build.id(), e);
                metrics.failed(site.id(), trigger, elapsed(startedAt));
            }
            return null;
        } finally {
            siteBuilder.discard(build.id());
        }
    }

    /**
     * What a successful build produced, where it went, and what its container held - the last of these read
     * once, so that everything reporting this build reports the same number.
     */
    private record Published(BuiltSite generated, PublishedSite published, ContainerMemory.Peak peak) {
    }

    /**
     * What follows a publication: what it produced, and the sites it superseded. None of it can undo the
     * publication, and none of it is worth failing a build that has already succeeded.
     */
    private void afterPublishing(Site site, DocumentationBuild build, BuildTrigger trigger, Published result,
                                 long startedAt) {
        BuiltSite generated = result.generated();
        try {
            log.info("The documentation site {} ({}) is published: {} pages, {} bytes, {} of which was the site "
                     + "generator{}.", site.id(), build.id(), generated.pageCount(),
                    result.published().sizeInBytes(), Duration.ofMillis(generated.docusaurusMillis()),
                    memoryClause(result.peak(), ", "));
            metrics.succeeded(site.id(), trigger, elapsed(startedAt), generated);
            removeSitesBeyondRetention(site);
        } catch (RuntimeException e) {
            log.warn("The documentation site {} ({}) is published, but what follows a publication did not all "
                     + "run. The site is served; the next build tidies up after this one.",
                    site.id(), build.id(), e);
        }
    }

    /**
     * What a build that was given up on leaves behind, in the order of what it costs to lose it.
     * <p>
     * The terminal state first: it is what stops the row reading as running and lets the workspace be swept.
     * Then the request, so that another instance runs the build within a poll interval instead of the site
     * waiting for its next upload or schedule. The objects last, because that step is the slow one and the
     * bucket's lifecycle rule is its fallback - nothing else will ever reference them, since the retention only
     * ever deletes what a successful build published.
     * <p>
     * Each step is guarded on its own. None of them is what makes this correct: an instance that is killed
     * writes none of them, and a build left running is recovered from its row either way. They are here to make
     * the ordinary stop quiet and immediate rather than to be relied on.
     */
    private void recordAbort(Site site, DocumentationBuild build, BuildTrigger trigger, RuntimeException cause,
                             long startedAt) {
        // Cleared for the duration of the bookkeeping and restored afterwards: an interrupt makes the connection
        // pool refuse to hand out a connection, and these three writes are worth more than the promptness.
        boolean interrupted = Thread.interrupted();
        try {
            log.info("The build {} of the documentation site {} was given up on because this instance is "
                     + "stopping; it has been asked for again.", build.id(), site.id());
            whileStopping("record the build as aborted",
                    () -> builds.aborted(build.id(), cause.getMessage(), clock.instant()));
            whileStopping("ask for the build again",
                    () -> requests.request(site.id(), trigger, clock.instant()));
            whileStopping("remove what the build had already uploaded",
                    () -> publication.delete(prefixOf(site, build.id())));
            metrics.aborted(site.id(), trigger, elapsed(startedAt));
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Runs one step of the bookkeeping of a build given up on, and carries on when it fails. Losing one of them
     * must not cost the others, and none of them may throw out of a shutdown.
     */
    private void whileStopping(String what, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            log.warn("While stopping, this instance could not {}. The next instance to build this site puts it "
                     + "right; nothing was lost that cannot be found again.", what, e);
        }
    }

    /**
     * Removes what is past the retention, and only after the new site is the published one - so a reader is
     * never left without a site while the old one is being deleted.
     */
    private void removeSitesBeyondRetention(Site site) {
        List<String> obsolete = builds.prefixesBeyondRetention(site.id(), properties.getRetention());
        for (String prefix : obsolete) {
            try {
                publication.delete(prefix);
                // Recorded, so the retention does not offer this prefix again on every build from now on.
                builds.forgetObjectPrefix(prefix);
            } catch (RuntimeException e) {
                // Nothing is broken by a site that stays: it costs storage, and the lifecycle rule of the bucket
                // is the fallback for what the service never gets to delete.
                log.warn("The superseded site under {} could not be removed.", prefix, e);
            }
        }
    }

    /**
     * What the container held while this build ran, as a clause to append - and nothing at all where that
     * cannot be read, which is every platform but Linux and every kernel without a high-water mark.
     * <p>
     * It is the number a container is sized from: a build is a child process whose bundler allocates outside
     * any heap this service can see, so the JVM's own meters say nothing about it. Reported in megabytes
     * against the limit, because what an operator does with it is compare the two.
     *
     * @param separator what joins it to the sentence before it - the line and the failure reason differ
     */
    private static String memoryClause(ContainerMemory.Peak peak, String separator) {
        if (peak == null) {
            return "";
        }
        String at = peak.exact() ? "peak " : "peak at most ";
        if (peak.limitBytes() <= 0) {
            return separator + at + megabytes(peak.usedBytes()) + " in the container";
        }
        return separator + at + megabytes(peak.usedBytes()) + " of " + megabytes(peak.limitBytes())
               + " (" + percent(peak) + "%) in the container";
    }

    /**
     * How much of the container the build held, rounded rather than truncated - the same arithmetic the page
     * does in the browser, so that the log line and the page agree on the number.
     */
    private static long percent(ContainerMemory.Peak peak) {
        return Math.round(peak.usedBytes() * 100.0 / peak.limitBytes());
    }

    /** Megabytes, rounded, as the page in the browser writes them too. */
    private static String megabytes(long bytes) {
        return Math.round(bytes / (1024.0 * 1024.0)) + "MB";
    }

    /**
     * Where a site is published: under the site, and below it the build that produced it. A build id is used
     * once, so nothing that is being read is ever written to.
     */
    private static String prefixOf(Site site, long buildId) {
        return site.id() + "/" + buildId;
    }

    private Duration elapsed(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos);
    }

    /**
     * Which instance ran a build, so that its log can be found. The host name is the task or pod identifier on a
     * container platform, and something recognisable on a developer machine.
     */
    private static String instanceName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            log.debug("The host name of this instance could not be read.", e);
            return "unknown";
        }
    }

}
