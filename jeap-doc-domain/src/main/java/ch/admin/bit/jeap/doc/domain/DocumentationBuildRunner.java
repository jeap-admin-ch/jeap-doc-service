package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.BuildMetrics;
import ch.admin.bit.jeap.doc.domain.port.BuiltSite;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRequestRepository;
import ch.admin.bit.jeap.doc.domain.port.DocumentationStatus;
import ch.admin.bit.jeap.doc.domain.port.ExclusiveWork;
import ch.admin.bit.jeap.doc.domain.port.PartPublication;
import ch.admin.bit.jeap.doc.domain.port.PreparedPart;
import ch.admin.bit.jeap.doc.domain.port.PublishedPart;
import ch.admin.bit.jeap.doc.domain.port.PublishedSite;
import ch.admin.bit.jeap.doc.domain.port.SiteBuildTimeoutException;
import ch.admin.bit.jeap.doc.domain.port.SiteBuilder;
import ch.admin.bit.jeap.doc.domain.port.SitePublicationStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.ToIntFunction;

/**
 * Publishes the parts of the documentation sites that have been asked for, one at a time per part.
 * <p>
 * Four things about the order of a pass are what the rest of this rests on:
 * <ul>
 *   <li><b>The lock is taken before the request is claimed.</b> The other way round loses requests: an instance
 *   that clears the flag and then finds the lock held has thrown a build request away, and nobody will ask again
 *   until the next upload or the next pass.</li>
 *   <li><b>The request is claimed before anything is read.</b> Every trigger arriving from then on finds the flag
 *   clear and sets it again, so a burst of triggers during a build produces exactly one follow-up run.</li>
 *   <li><b>The content is written and hashed before the generator starts.</b> A part whose content is what is
 *   already published is not generated at all - it is the cheap half deciding whether the expensive half runs,
 *   and it is what makes a part per system affordable.</li>
 *   <li><b>A pass keeps the slots full.</b> One instance builds {@code max-concurrent-parts} parts at a time -
 *   a build is a process that wants a core, so a hundred pending parts must not become a hundred of them inside
 *   one container - and it fills the slot of a part that is done at once, rather than waiting for the batch or
 *   for the next poll. Different instances build different parts at the same time, which is what the per-part
 *   lock is for.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentationBuildRunner {

    /** The prefix of the lock a part's build holds, so that two parts do not wait for each other. */
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
    private final SitePartition partition;
    private final SiteBuilder siteBuilder;
    private final SitePublicationStorage publication;
    private final BuildProperties properties;
    private final BuildMetrics metrics;
    private final ExclusiveWork exclusiveWork;
    private final ArchitectureModelReadiness readiness;
    /** What makes the documentation searchable, run once at the end of a pass that published something. */
    private final SearchIndexing searchIndexing;
    private final Clock clock;

    /**
     * Held for as long as a pass is running, so that a stopping instance can wait for the bookkeeping of the
     * build it just gave up on. Acquiring it is the proof that this runner is idle - and idle here means the
     * terminal state was written, not merely that the site generator has stopped.
     */
    private final ReentrantLock ticking = new ReentrantLock();

    /** Set once this instance is stopping: from then on no pass starts a build. */
    private volatile boolean stopping;

    /**
     * Publishes everything this instance is owed, and reports whether it published anything. Called on a fixed
     * delay, and by the tests directly.
     * <p>
     * It returns when the pass is over - the builds it started have finished and written their terminal state -
     * which is what {@link #awaitIdle} and the shutdown handling rest on.
     */
    public boolean runOnce() {
        if (stopping) {
            return false;
        }
        ticking.lock();
        try {
            // Checked again inside: the instance may have started stopping while this pass waited for the one
            // before it, and a build started now would be given up on immediately.
            return !stopping && runPass();
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
     * Waits until this runner is between passes, for at most the given time, and reports whether it got there.
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

    /**
     * One pass over everything this instance is owed, and whether it built anything.
     */
    private boolean runPass() {
        return new Pass().run();
    }

    /**
     * One pass over what is owed: it keeps the configured number of builds running until nothing is left that
     * this instance can build.
     * <p>
     * <b>A pass does not wait for a batch to finish.</b> The slot of a part that is done is filled with the
     * next candidate at once. So a large part no longer holds the other slots empty, and a queue is no longer
     * drained one poll interval per batch - what the poll interval decides is how soon an <i>idle</i> instance
     * notices work.
     * <p>
     * <b>What is owed is read again after every build</b>, because a pass over a whole landscape lasts minutes:
     * an upload arriving in the middle of one is served by that pass rather than by the next.
     * <p>
     * <b>A part is offered once per pass, unless it is asked for again after that.</b> Not looking at a part
     * twice is what keeps a pass from spinning on the parts the other instances are building - but a pass can
     * run for an hour, and a part it built in the first minute would otherwise stand unserved for the rest of
     * it. So a part built or found current is offered again when a request arrives after the pass offered it;
     * a part held by another instance, or one whose build threw, is not.
     */
    private final class Pass {

        /** The parts this pass is building right now. */
        private final Set<PartKey> inFlight = new HashSet<>();

        /** The parts this pass is finished with: when it offered each of them, and how that ended. */
        private final Map<PartKey, Settlement> settled = new HashMap<>();

        /** Candidates read but not yet started, in the order they should be taken. */
        private final Deque<Buildable> queue = new ArrayDeque<>();

        /**
         * How many pages each part of a site was published with, read once per pass. It is what the order of
         * pick-up is banded by, and it barely moves during a pass - a part built in this one is settled and is
         * not ordered again.
         */
        private final Map<String, Map<String, Integer>> publishedPages = new HashMap<>();

        /**
         * The sites this pass published a part of, which are the ones whose search index is out of date by the
         * time it ends. A pass that built nothing indexes nothing.
         */
        private final Set<String> sitesWithABuild = new LinkedHashSet<>();

        private final long startedAtNanos = System.nanoTime();
        /** The build time of this pass added up over every slot: three busy slots for a minute is three. */
        private final AtomicLong busyNanos = new AtomicLong();
        private int built;
        private int contended;
        private int notOwed;
        private int broken;

        boolean run() {
            if (!refill()) {
                // Nothing is owed, which is what most passes find. No pool, no line, no lock.
                return false;
            }
            // The leftovers of builds that are no longer running, once for the pass and before a slot is
            // filled. It used to be a step of every build, and once the slots of a pass overlapped that meant
            // every slot walking the same directory at the same time: each of them removed trees the others
            // were walking, and each of them reported the vanished tree as a workspace it had failed to
            // remove. A sweep is housekeeping over a directory this instance owns alone, and the pass is what
            // knows when none of its builds has started yet.
            siteBuilder.sweepWorkspaces(builds.runningIds());
            try {
                if (slots() > 1) {
                    drainOnAPool();
                } else {
                    drainOnThisThread();
                }
            } finally {
                // However the pass ended. A database blip in the middle of a refill propagates out of here,
                // and without this the busy-slots gauge keeps the value it had while the instance sits idle.
                report();
            }
            indexWhatWasPublished();
            return built > 0;
        }

        /**
         * Makes the documentation this pass published searchable, once, for each site it built a part of.
         * <p>
         * <b>After the parts, not before them, and outside the build of any one of them.</b> A build is one
         * part and the index is over the whole site, so indexing per build would index the whole site
         * fifty-two times for one publication and throw fifty-one of them away. The end of the pass is the
         * first moment at which everything this instance was owed has been published.
         * <p>
         * <b>It cannot fail the pass.</b> The parts are already published: a site whose index could not be
         * built serves its new pages with the search it had before, which is worse than the alternative only
         * for as long as it takes the next pass to fix it. What that costs is a log line at error and a row
         * saying why - never a publication.
         */
        private void indexWhatWasPublished() {
            for (String site : sitesWithABuild) {
                if (stopping) {
                    // The instance is going. An index takes minutes and half of one is not published anyway,
                    // and the shutdown is waiting on this pass - so the sites this has not started are left to
                    // the instance that runs the next pass. Checked per site rather than once: a stop
                    // signalled while the first site is being indexed has to stop the second.
                    return;
                }
                try {
                    sites.find(site).ifPresent(searchIndexing::index);
                } catch (RuntimeException e) {
                    // SearchIndexing records and swallows its own failures; this is the one that got past it -
                    // a site that went out of the configuration, a database that went away. Same rule: the
                    // publication stands.
                    log.error("The documentation of {} was published and could not be indexed for search. It "
                              + "is served with the index it had before.", site, e);
                }
            }
        }

        /**
         * Builds one part after another on the calling thread. It is what an instance with room for a single
         * build wants: no pool, and the pass still carries on to the next part instead of waiting a poll
         * interval for it.
         */
        private void drainOnThisThread() {
            while (!stopping) {
                Buildable next = nextCandidate();
                if (next == null) {
                    return;
                }
                PartKey key = next.part().key();
                Instant offeredAt = clock.instant();
                inFlight.add(key);
                metrics.slotsBusy(inFlight.size());
                PartOutcome outcome = buildOrReport(next);
                inFlight.remove(key);
                settle(key, offeredAt, outcome);
            }
        }

        /**
         * Keeps the slots full. The pool is created for the pass and closed with it - a pass exists only when
         * there is something to build, so it never holds threads for nothing.
         */
        private void drainOnAPool() {
            try (ExecutorService pool = Executors.newFixedThreadPool(slots(), runnable -> {
                Thread thread = new Thread(runnable, "documentation-build");
                thread.setDaemon(true);
                return thread;
            })) {
                CompletionService<PartResult> finished = new ExecutorCompletionService<>(pool);
                while (true) {
                    fill(finished);
                    if (inFlight.isEmpty() || !awaitOne(finished)) {
                        return;
                    }
                }
            }
        }

        /**
         * Starts builds while there is a free slot and a candidate to put in it. <b>Nothing is started once the
         * instance is stopping</b>: the builds already running have a terminal state to write, and the shutdown
         * budget is theirs.
         * <p>
         * <b>Which part goes into a free slot is not decided here.</b> The candidates arrive in the order
         * {@link BuildPickUpOrder} put them in - oldest second first, largest band first within a second, and
         * <b>the shell of a site last of all</b>, because it carries the links into the system parts. A slot
         * filled with whatever came to hand would publish the shell before the parts it links to.
         */
        private void fill(CompletionService<PartResult> finished) {
            while (inFlight.size() < slots() && !stopping) {
                Buildable next = nextCandidate();
                if (next == null) {
                    return;
                }
                PartKey key = next.part().key();
                // Taken when the part leaves the queue and not when its build ends: the build claims the
                // request row before it reads anything, so a request arriving from here on is unserved by it.
                Instant offeredAt = clock.instant();
                inFlight.add(key);
                metrics.slotsBusy(inFlight.size());
                finished.submit(() -> new PartResult(key, offeredAt, buildOrReport(next)));
            }
        }

        /**
         * Waits for one of the running builds and frees its slot, and reports whether the pass may go on.
         * <p>
         * <b>A slot is freed by the build that held it, and by nothing else.</b> Either branch below leaves
         * the pass over instead of freeing slots it cannot account for: {@code fill} would put a full
         * complement of builds beside the ones still running, and the number of Docusaurus processes at once
         * is what the container is sized for.
         */
        private boolean awaitOne(CompletionService<PartResult> finished) {
            try {
                PartResult result = finished.take().get();
                inFlight.remove(result.part());
                settle(result.part(), result.offeredAt(), result.outcome());
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // Every slot is given up on and the pass ends here, so nothing is read from settled again.
                inFlight.clear();
                return false;
            } catch (ExecutionException e) {
                // Unreachable, because the task answers with an outcome whatever happens - see buildOrReport.
                // Were it ever reached, which slot came free is exactly what is not known.
                log.error("A documentation build ended in a way its own error handling did not cover; the pass "
                          + "ends here and what is still owed is picked up by the next one.", e.getCause());
                return false;
            }
        }

        /**
         * The next candidate, or null when there is nothing left.
         * <p>
         * <b>What is owed is read again for every candidate</b>, and not only once the queue has run dry. A
         * pass lasts minutes, and the point of reading again is that an upload arriving during one is served
         * by it rather than by the next - which a refill on an empty queue does not give: a part asked for
         * after the queue was filled would wait out the whole pass. One read per build started is nothing
         * beside a build.
         */
        private Buildable nextCandidate() {
            refill();
            return queue.poll();
        }

        /**
         * Reads what is owed a build and puts what this pass may still take into the queue, reporting whether
         * that was anything.
         * <p>
         * Anything that cannot be built is dealt with on the way - a part of a site nobody configures any more,
         * or of an axis the site was cut on before - and settled, so that the junk is handled once per pass
         * rather than on every refill.
         */
        @SuppressWarnings("java:S135") // Guard clauses: each continue names one reason a candidate is not queued.
        private boolean refill() {
            queue.clear();
            // Once per site rather than once per candidate: it reads the import state of every environment of
            // the site, and fifty parts of one site are one answer.
            Map<String, Boolean> readyToBuild = new HashMap<>();
            for (Owed owed : partsOwedABuild(this::pagesOf)) {
                PartKey key = owed.part();
                if (inFlight.contains(key) || !mayOffer(key, owed.requestedAt())) {
                    continue;
                }
                Optional<Site> configured = sites.find(key.site());
                if (configured.isEmpty()) {
                    forgetPartThatIsGone(key, owed.requestedAt());
                    settleWithoutABuild(key);
                    continue;
                }
                Optional<SitePart> part = partition.partOf(configured.get(), key.part());
                if (part.isEmpty()) {
                    // A part this partition would never produce: the axis of the split was changed, and what is
                    // asked for belongs to the one before it.
                    log.warn("A build of {} was asked for, and the {} partition of that site has no such part; "
                             + "the request is dropped.", key, partition.axis());
                    forgetPartThatIsGone(key, owed.requestedAt());
                    settleWithoutABuild(key);
                    continue;
                }
                // Readiness is checked before the request is claimed, and short-circuits the build: claiming it
                // and then declining to build would throw it away, and nothing would ask again until the next
                // upload or the next import. Not settled either - a model that arrives during this pass makes
                // the part buildable, and the next refill picks it up.
                Site site = configured.get();
                if (Boolean.TRUE.equals(
                        readyToBuild.computeIfAbsent(key.site(), id -> readiness.isReadyToBuild(site)))) {
                    queue.add(new Buildable(site, part.get()));
                }
            }
            return !queue.isEmpty();
        }

        /**
         * Builds one part, and turns anything its own error handling did not cover into an outcome. The time it
         * held its slot is added up here, because that is what says whether the slots were used.
         * <p>
         * <b>{@code Throwable} and not {@code RuntimeException}</b>: a task that throws is a task whose slot
         * the pass cannot account for - see {@link #awaitOne}. An {@code Error} is the likely one in a feature
         * whose whole subject is memory pressure, and the answer to it must not be to start more builds.
         */
        @SuppressWarnings("java:S1181") // Catching Throwable is the point: see above.
        private PartOutcome buildOrReport(Buildable next) {
            long startedAt = System.nanoTime();
            try {
                // Everything this build logs - the workspace, the generator's own output, the line that says
                // it failed - carries the site, the part and the build. The scope is opened here because this
                // is where one build's work begins and ends on one thread; the slots share a pool, so a
                // context left behind would label the next build's lines with this part. See BuildLogContext.
                try (BuildLogContext _ = BuildLogContext.of(next.part().key())) {
                    return buildPart(next.site(), next.part());
                }
            } catch (Throwable e) {
                log.error("The build of {} ended in a way its own error handling did not cover.",
                        next.part().key(), e);
                return PartOutcome.BROKEN;
            } finally {
                busyNanos.addAndGet(System.nanoTime() - startedAt);
            }
        }

        /**
         * Whether this pass may offer the part now.
         * <p>
         * A part it has not reached is always offered. One it has is offered again only when a request
         * arrived after the pass offered it - see {@link Settlement} for which settlements allow that.
         * <p>
         * <b>It cannot spin, and that is a property of the data rather than of a guard.</b> A request keeps
         * the instant it was first asked with, so a burst of triggers during one build is one row and one
         * re-offer, and the re-offered build claims that row. The next re-offer needs a request written after
         * <i>that</i> claim, which is a strictly later instant.
         *
         * @param requestedAt when the pending request was made, or null where only a running build says
         *                    anything about this part. There is nothing to compare then, so once per pass is
         *                    right for it
         */
        private boolean mayOffer(PartKey part, Instant requestedAt) {
            Settlement settlement = settled.get(part);
            return settlement == null
                   || (settlement.mayBeOfferedAgain() && requestedAt != null
                       && requestedAt.isAfter(settlement.offeredAt()));
        }

        /** A part there was nothing to build: no site configured, or no such part. Nothing offers it again. */
        private void settleWithoutABuild(PartKey part) {
            settled.put(part, new Settlement(clock.instant(), false));
        }

        private void settle(PartKey part, Instant offeredAt, PartOutcome outcome) {
            settled.put(part, new Settlement(offeredAt, mayBeOfferedAgain(outcome)));
            metrics.slotsBusy(inFlight.size());
            switch (outcome) {
                case BUILT -> {
                    built++;
                    sitesWithABuild.add(part.site());
                }
                case NOTHING_OWED -> notOwed++;
                // Counted as it happens rather than when the pass ends: a pass can run for hours, and these
                // two are what says the fleet is unwell while it still is.
                case LOCKED_ELSEWHERE -> {
                    contended++;
                    metrics.contended();
                }
                case BROKEN -> {
                    broken++;
                    metrics.broken();
                }
            }
        }

        private int slots() {
            return Math.max(1, properties.getMaxConcurrentParts());
        }

        /** What building this part cost last time, in pages - see {@link BuildPickUpOrder}. */
        private int pagesOf(PartKey part) {
            return publishedPages
                    .computeIfAbsent(part.site(), this::pagesByPartOf)
                    .getOrDefault(part.part(), BuildPickUpOrder.NEVER_PUBLISHED);
        }

        private Map<String, Integer> pagesByPartOf(String site) {
            Map<String, Integer> pages = new HashMap<>();
            for (PublishedPart published : builds.publishedPartsOf(site)) {
                pages.put(published.part(), published.pageCount());
            }
            return pages;
        }

        /**
         * What the pass did, in one line. <b>The utilisation is the point of it</b>: a pass whose slots stood
         * empty is one that took longer than it had to, and every other thing reported about a build looks
         * perfectly normal while it happens.
         */
        private void report() {
            metrics.slotsBusy(0);
            Duration duration = elapsed(startedAtNanos);
            log.info("A build pass is over after {}: {} part(s) built, {} left to another instance, {} owed "
                     + "nothing after all, {} broken - {}% of {} slot(s) busy.", duration, built, contended,
                    notOwed, broken, Math.round(utilisation(duration) * 100), slots());
        }

        /**
         * How much of the pass the slots were busy, from 0 to 1 - and 0 for a pass too short to divide by.
         * <p>
         * One is every slot building throughout. A third means the pass could have been a third as long, and
         * the reasons are worth looking for in that order: fewer parts were owed than there are slots, the
         * other instances held the locks, or the pass ran out of parts before it ran out of slots.
         */
        private double utilisation(Duration duration) {
            long available = duration.toNanos() * slots();
            return available <= 0 ? 0 : Math.min(1.0, (double) busyNanos.get() / available);
        }
    }

    /** Which part a pass finished, when it was offered, and how it ended. */
    private record PartResult(PartKey part, Instant offeredAt, PartOutcome outcome) {
    }

    /**
     * When a pass offered a part, and whether a later request may make it offer that part again.
     * <p>
     * Only two outcomes allow it, because only for those is going back to the part work rather than a retry.
     * <b>{@code LOCKED_ELSEWHERE} is another instance's part</b>, and reaching for it again is exactly the
     * spin the once-per-pass rule exists to prevent. <b>{@code BROKEN} is a build that threw</b>, and running
     * it again inside the same pass is a retry loop wearing a different hat. A part there was nothing to
     * build at all is not offered again either.
     */
    private record Settlement(Instant offeredAt, boolean mayBeOfferedAgain) {
    }

    /**
     * A part a pass may take, and when it was asked for - null where only a running build says anything about
     * it, which is the recovery half of what is owed and carries no request.
     */
    private record Owed(PartKey part, Instant requestedAt) {
    }

    /** Which outcomes a later request may make a pass revisit - see {@link Settlement}. */
    private static boolean mayBeOfferedAgain(PartOutcome outcome) {
        return outcome == PartOutcome.BUILT || outcome == PartOutcome.NOTHING_OWED;
    }

    /** How one part of a pass ended. */
    private enum PartOutcome {

        /** A build ran. Whether it succeeded, was skipped by its digest or failed is that build's own record. */
        BUILT,

        /** Another instance holds this part's lock, and is building it. The request stays pending. */
        LOCKED_ELSEWHERE,

        /** Nothing was owed after all: another instance claimed the request between the read and the lock. */
        NOTHING_OWED,

        /** Something threw where nothing should. Counted, so that a pass full of them is visible. */
        BROKEN
    }

    /** A part that is owed a build, with the site it belongs to. */
    private record Buildable(Site site, SitePart part) {
    }

    /**
     * Drops everything that still says a site nobody configures any more owes a build.
     * <p>
     * The request is the obvious half. The other half is a run that was left behind when the site was removed:
     * without giving up on it, the row stays {@code RUNNING} for ever, its identifier keeps its workspace from
     * being swept, and this warning is logged on every pass until someone notices.
     * <p>
     * <b>Under the part's lock all the same</b>, because the sites are configured per instance: during a rolling
     * deployment that removes a site, the instances that still have it are entitled to be building it. Giving up
     * on a run without the lock would mark a live build as abandoned, and its instance would then record it as
     * succeeded over a failure reason saying its instance had stopped.
     */
    // A pass is one run of the runner and holds no state of its own beyond its queue; the lock, the
    // properties and the repositories these three read belong to the runner and stay with it.
    @SuppressWarnings("java:S3398")
    private void forgetPartThatIsGone(PartKey part, Instant requestedAt) {
        exclusiveWork.underLock(LOCK_PREFIX + part, properties.getLockLease(),
                () -> forgetUnderLock(part, requestedAt));
    }

    /**
     * Reports each of the two separately, because they say different things to whoever reads the log: a run
     * that never finished, and a request nobody served.
     */
    private boolean forgetUnderLock(PartKey part, Instant requestedAt) {
        int abandoned = builds.abandonRunning(part, clock.instant()).size();
        if (abandoned > 0) {
            metrics.abandoned(part.site(), abandoned);
            log.warn("{} run(s) of {} never finished, and nothing configures that part any more; they are given "
                     + "up on.", abandoned, part);
        }
        // Only a request that no instance has served for a long time, and the age is this part's own: another
        // part of the site having waited an hour says nothing about this one. This instance not knowing the
        // site does not mean no instance does - during a rolling deployment that *adds* a site, the instances
        // that have it are serving its requests while the ones that do not would otherwise delete them, and a
        // claimed request is gone, so the build would never run and nothing would say why.
        boolean requestDropped = requestedAt != null
                                 && requestedAt.isBefore(clock.instant().minus(forgetRequestsAfter()))
                                 && requests.claim(part).isPresent();
        if (requestDropped) {
            log.warn("A build of {} was asked for, nothing configures that part any more, and no instance "
                     + "picked it up for {}; the request is dropped.", part, forgetRequestsAfter());
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
     * The parts that may owe a build, in the order this instance takes them, and then the ones that only a
     * leftover row says anything about.
     * <p>
     * The second half is the recovery: a build whose instance died was claimed when it started, so nothing asks
     * for it any more and the request cannot be what says it is owed. <b>The row that is still {@code RUNNING}
     * is.</b> Whether it really is stale is not decided here - it is decided by whether that part's lock can be
     * taken, which only succeeds once the dead instance's lease has run out.
     */
    @SuppressWarnings("java:S3398") // See forgetPartThatIsGone.
    private List<Owed> partsOwedABuild(ToIntFunction<PartKey> pagesOf) {
        // Largest first and shuffled within a size band, so that the tail of a pass is not one large part and
        // two instances do not both go for the head of the queue - see BuildPickUpOrder.
        List<Owed> owed = new ArrayList<>();
        Set<PartKey> requested = new HashSet<>();
        for (BuildRequest request : BuildPickUpOrder.of(requests.pending(), pagesOf,
                ThreadLocalRandom.current())) {
            owed.add(new Owed(request.part(), request.requestedAt()));
            requested.add(request.part());
        }
        for (PartKey part : builds.partsWithRunningBuilds()) {
            if (requested.add(part)) {
                owed.add(new Owed(part, null));
            }
        }
        return owed;
    }

    /**
     * Takes the part's lock and builds it - or reports that another instance holds it, in which case the request
     * stays pending and is served after that instance's build.
     * <p>
     * The lease is far shorter than a build may take, because the lock is extended while the build runs. What it
     * sizes is how long a killed instance blocks that one part.
     */
    @SuppressWarnings("java:S3398") // See forgetPartThatIsGone.
    private PartOutcome buildPart(Site site, SitePart part) {
        return exclusiveWork
                .underLock(LOCK_PREFIX + part.key(), properties.getLockLease(), () -> claimAndBuild(site, part))
                .orElse(PartOutcome.LOCKED_ELSEWHERE);
    }

    private PartOutcome claimAndBuild(Site site, SitePart part) {
        BuildRequest claimed = whatThisPartIsOwed(part.key());
        if (claimed == null) {
            return PartOutcome.NOTHING_OWED;
        }
        build(site, part, claimed);
        return PartOutcome.BUILT;
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
    private BuildRequest whatThisPartIsOwed(PartKey part) {
        // Holding this part's lock means any build of it that is still marked as running has lost its lease, so
        // it is a run whose instance disappeared rather than one in progress.
        List<DocumentationBuild> abandoned = builds.abandonRunning(part, clock.instant());
        if (!abandoned.isEmpty()) {
            log.warn("{} build(s) of {} were still marked as running and have been given up on: the instance "
                     + "running them stopped.", abandoned.size(), part);
            metrics.abandoned(part.site(), abandoned.size());
        }

        Optional<BuildRequest> claimed = requests.claim(part);
        if (claimed.isPresent()) {
            return claimed.get();
        }
        if (abandoned.isEmpty()) {
            return null;
        }
        if (abandoned.stream().anyMatch(build -> build.trigger() == BuildTrigger.RECOVERY)) {
            // Twice in a row is a build that kills whatever runs it. Repeating it would be a crash loop, so the
            // part waits for an upload or the next import instead.
            log.error("{} lost a build that was already a recovery attempt; it is not run again automatically. "
                      + "Something about this build is stopping the instance running it.", part);
            return null;
        }
        // Outside any publication: what the lost build belonged to is not on its row, and a recovery is a new
        // ask rather than part of the ask that was interrupted.
        // Not forced: a build that was interrupted is worth running again, and if its content turns out to be
        // exactly what is published then the interrupted run had already done the work.
        return new BuildRequest(part, clock.instant(), BuildTrigger.RECOVERY, null, false);
    }

    private void build(Site site, SitePart part, BuildRequest request) {
        BuildTrigger trigger = request.trigger();
        DocumentationBuild build = builds.start(part.key(), trigger, instanceName(), clock.instant(),
                request.publication());
        // As soon as there is a row to name. Undone by the scope buildOrReport opened around this.
        BuildLogContext.buildIs(build.id());
        log.info("Publishing {} - {} - ({}), asked for by {}.", part.key(), part.documents(), build.id(),
                trigger);
        long startedAt = System.nanoTime();
        // Past a publication nothing may take it back, so what follows one is deliberately outside the block
        // that can turn a build into a failure: a database hiccup while measuring the build or clearing away
        // what it superseded would otherwise rewrite a published build as failed - or, while stopping, as
        // aborted, and delete the very objects the row points at.
        Published result = publish(site, part, build, request, startedAt);
        if (result != null) {
            afterPublishing(site, part, build, trigger, result, startedAt);
        }
    }

    /**
     * Generates the site, puts it in the object storage and records the build as the published one - or records
     * why it is not, and reports nothing.
     */
    private Published publish(Site site, SitePart part, DocumentationBuild build, BuildRequest request,
                              long startedAt) {
        BuildTrigger trigger = request.trigger();
        try {
            Instant generatedAt = clock.instant();
            // The cheap half first: the content, and what it hashes to.
            PreparedPart prepared = siteBuilder.prepare(build.id(), site, part, generatedAt);
            // The request and not its trigger: a request already pending when an operator forced a
            // publication keeps the trigger that asked first, so reading the trigger meant a forced build was
            // skipped for exactly the parts an import had already asked for - most of them, most of the hour.
            if (!request.forced() && isAlreadyPublished(part, prepared)) {
                builds.skipped(build.id(), clock.instant());
                metrics.skipped(site.id(), trigger);
                log.info("{} was asked for and its content is exactly what is published, so the site generator "
                         + "was not started ({}).", part.key(), build.id());
                return null;
            }
            BuiltSite generated = siteBuilder.generate(prepared);
            // The seam: the numbers of this run exist now, the site is still on local disk, and the page that
            // prints them was written at the start of the run. So they are written into the output before the
            // upload, and the page fetches them - see DocumentationStatus.
            siteBuilder.describeRun(generated, DocumentationStatus.of(build.id(), generatedAt,
                    elapsed(startedAt).toMillis(), generated));
            PublishedSite published = publication.publish(whereToPublish(site, build.id()),
                    generated.directory());
            builds.succeeded(build.id(), published.prefix(), generated.pageCount(), published.sizeInBytes(),
                    generated.docusaurusMillis(), prepared.digest(), clock.instant());
            return new Published(generated, published);
        } catch (RuntimeException e) {
            recordThatItDidNotWork(site, part, build, request, e, startedAt);
            return null;
        } catch (Error e) {
            // An OutOfMemoryError is the failure this feature invites, and a row left RUNNING keeps its
            // workspace and reads as a live build until another pass abandons it. Recorded, then rethrown:
            // what to do about an Error is not this method's decision.
            recordThatItDidNotWork(site, part, build, request, e, startedAt);
            throw e;
        } finally {
            siteBuilder.discard(build.id());
        }
    }

    /**
     * Records a build that ended badly: the row first, then the meter.
     * <p>
     * Every write is guarded. This runs under memory pressure and with a stopping context, where a write is
     * one more thing that can throw - and losing the record of a failure to a failure of recording it is how a
     * row stays RUNNING for ever.
     */
    @SuppressWarnings("java:S1181") // The caller catches Error deliberately; this is where it is recorded.
    private void recordThatItDidNotWork(Site site, SitePart part, DocumentationBuild build,
                                        BuildRequest request, Throwable e, long startedAt) {
        BuildTrigger trigger = request.trigger();
        try {
            if (stopping && e instanceof RuntimeException stopped) {
                // Not a failure: this instance asked the generator to stop. Recorded apart from one, because
                // the alarm is on failures and a deployment landing on a build must not page anybody.
                recordAbort(site, build, request, stopped, startedAt);
                return;
            }
            builds.failed(build.id(), messageOf(e), clock.instant());
            log.error("{} ({}) could not be published; what was published before it is still being served.",
                    part.key(), build.id(), e);
            // Failed either way on the row - there is one way for a build to end badly. Counted apart,
            // because a build that ran out of time is not put right the way a broken one is: see
            // BuildMetrics.timedOut.
            if (e instanceof SiteBuildTimeoutException) {
                metrics.timedOut(site.id(), trigger, elapsed(startedAt));
            } else {
                metrics.failed(site.id(), trigger, elapsed(startedAt));
            }
            removeWhatTheBuildUploaded(site, build);
        } catch (RuntimeException whileRecording) {
            log.warn("{} ({}) ended badly, and recording that failed too.", part.key(), build.id(),
                    whileRecording);
        }
    }

    /**
     * Removes what a failed build had already put in the object storage.
     * <p>
     * A build uploads before it is recorded as succeeded, so a failure in between leaves a whole part's output
     * behind. Nothing else would ever remove it: the retention only offers prefixes of successful builds, and
     * the bucket expires nothing under the published sites. The prefix is named after the build id, this build
     * never became the published one, and nothing references it - so deleting it is safe whatever the failure
     * was, including a failure before the first object was written.
     */
    private void removeWhatTheBuildUploaded(Site site, DocumentationBuild build) {
        try {
            publication.delete(prefixOf(site, build.id()));
        } catch (RuntimeException e) {
            log.warn("What the failed build {} had already uploaded could not be removed. It is served to "
                     + "nobody; it has to be removed by hand.", build.id(), e);
        }
    }

    /** What to write into the failure reason: an Error often carries no message at all. */
    private static String messageOf(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** What a successful build produced, and where it went. */
    private record Published(BuiltSite generated, PublishedSite published) {
    }

    /**
     * What follows a publication: what it produced, and the sites it superseded. None of it can undo the
     * publication, and none of it is worth failing a build that has already succeeded.
     */
    private void afterPublishing(Site site, SitePart part, DocumentationBuild build, BuildTrigger trigger,
                                 Published result, long startedAt) {
        BuiltSite generated = result.generated();
        try {
            log.info("{} ({}) is published: {} pages, {} bytes, {} of which was the site generator.",
                    part.key(), build.id(), generated.pageCount(), result.published().sizeInBytes(),
                    Duration.ofMillis(generated.docusaurusMillis()));
            metrics.succeeded(site.id(), trigger, elapsed(startedAt), generated);
            // Here rather than beside the build timer: only a build that really generated says anything about
            // what its part costs, and this is the one path a published build takes.
            metrics.partBuilt(part.key(), elapsed(startedAt));
            removePublicationsBeyondRetention(part);
        } catch (RuntimeException e) {
            log.warn("{} ({}) is published, but what follows a publication did not all run. It is served; the "
                     + "next build tidies up after this one.", part.key(), build.id(), e);
        }
    }

    /**
     * Whether the content just written is exactly what is being served.
     * <p>
     * Both halves matter. The digest says the pages would be the same, and the prefix says the files of that
     * publication are still there - a publication whose objects the retention has removed has to be built
     * again however unchanged its content is.
     * <p>
     * <b>A build somebody asked for is never skipped</b> (see the caller): the reason to ask for one by hand is
     * that something outside the content changed - the site template while it is being worked on, most of all -
     * and an operator who forces a publication and is told nothing happened would have no way to get one.
     */
    private boolean isAlreadyPublished(SitePart part, PreparedPart prepared) {
        Optional<DocumentationBuild> published = builds.published(part.key());
        return published
                .filter(build -> build.objectPrefix() != null)
                .map(DocumentationBuild::contentDigest)
                .filter(digest -> digest.equals(prepared.digest()))
                .isPresent();
    }

    /**
     * What a build that was given up on leaves behind, in the order of what it costs to lose it.
     * <p>
     * The terminal state first: it is what stops the row reading as running and lets the workspace be swept.
     * Then the request, so that another instance runs the build within a poll interval instead of the site
     * waiting for its next upload or schedule. The objects last, because that step is the slow one - and it has
     * no fallback: the bucket expires nothing under the published sites, and the retention only ever offers what
     * a successful build published.
     * <p>
     * Each step is guarded on its own. None of them is what makes this correct: an instance that is killed
     * writes none of them, and a build left running is recovered from its row either way. They are here to make
     * the ordinary stop quiet and immediate rather than to be relied on.
     */
    private void recordAbort(Site site, DocumentationBuild build, BuildRequest request,
                             RuntimeException cause, long startedAt) {
        BuildTrigger trigger = request.trigger();
        // Cleared for the duration of the bookkeeping and restored afterwards: an interrupt makes the connection
        // pool refuse to hand out a connection, and these three writes are worth more than the promptness.
        boolean interrupted = Thread.interrupted();
        try {
            log.info("The build {} of the documentation site {} was given up on because this instance is "
                     + "stopping; it has been asked for again.", build.id(), site.id());
            whileStopping("record the build as aborted",
                    () -> builds.aborted(build.id(), cause.getMessage(), clock.instant()));
            // With the publication it belonged to: a deployment landing on a publication must not take that
            // part out of it, or the publication would read as complete while one of its parts is still owed.
            // And with its force, so that a publication somebody asked for by hand is not quietly turned into
            // one the digest may skip by a deployment landing on it.
            whileStopping("ask for the build again",
                    () -> requests.request(PartKey.of(build.site(), build.part()), trigger, clock.instant(),
                            request.publication(), request.forced()));
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
     * Removes what is past the retention of this part, and only after the new publication is the current one -
     * so a reader is never left without a page while the old one is being deleted.
     */
    private void removePublicationsBeyondRetention(SitePart part) {
        List<String> obsolete = builds.prefixesBeyondRetention(part.key(), properties.getRetention());
        for (String prefix : obsolete) {
            try {
                publication.delete(prefix);
                // Recorded, so the retention does not offer this prefix again on every build from now on.
                builds.forgetObjectPrefix(prefix);
            } catch (RuntimeException e) {
                // Nothing is broken by a site that stays: it costs storage until the next build of this part,
                // which is offered the same prefix again because it is only forgotten once it is gone.
                log.warn("The superseded site under {} could not be removed.", prefix, e);
            }
        }
    }

    /**
     * Where a part is published: its own files under the site and the build that produced them - a build id is
     * used once, so nothing that is being read is ever written to - and its shared files under the one prefix
     * every part of the site writes to.
     */
    private static PartPublication whereToPublish(Site site, long buildId) {
        return new PartPublication(prefixOf(site, buildId), SharedAssets.prefixOf(site.id()));
    }

    /**
     * Where the files of one build lie: under the site, and below it the build that produced them.
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
