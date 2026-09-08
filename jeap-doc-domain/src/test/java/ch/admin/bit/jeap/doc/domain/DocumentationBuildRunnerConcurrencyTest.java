package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.BuiltSite;
import ch.admin.bit.jeap.doc.domain.port.ExclusiveWork;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import ch.admin.bit.jeap.doc.domain.port.DocumentationStatus;
import ch.admin.bit.jeap.doc.domain.port.PartPublication;
import ch.admin.bit.jeap.doc.domain.port.PreparedPart;
import ch.admin.bit.jeap.doc.domain.port.PublishedSite;
import ch.admin.bit.jeap.doc.domain.port.SiteBuilder;
import ch.admin.bit.jeap.doc.domain.port.SitePublicationStorage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

/**
 * Two instances working one queue of parts.
 * <p>
 * The lock is per part and refuses rather than waits, so what decides whether a fleet of instances is used is
 * what an instance does with a part it could not lock: <b>it takes the next one</b>. This is the test of that,
 * and of the invariant underneath it - a part is built once, however many instances reach for it.
 * <p>
 * The lock table and the standing requests are in memory here. That the real ones are a row in PostgreSQL is
 * {@code ShedLockExclusiveWorkIT}'s business; what is under test is the runner.
 */
class DocumentationBuildRunnerConcurrencyTest {

    private static final Instant NOW = Instant.parse("2026-09-07T09:00:00Z");
    private static final String SITE = Site.DEFAULT_SITE;
    private static final int PARTS = 12;

    private final StandingBuildRequests requests = new StandingBuildRequests();
    private final OneAtATimeExclusiveWork locks = new OneAtATimeExclusiveWork();
    private final AtomicLong buildIds = new AtomicLong();

    /**
     * Both instances build, and every part is built exactly once.
     * <p>
     * Deterministic although it is a race: with twelve parts owed and four slots between the two instances,
     * an instance that lost a lock always has another part to take - and the latch makes each build wait until
     * the other instance has started one, so neither can drain the queue before the other has begun.
     */
    @Test
    void whenTwoInstancesPassOverOneQueue_thenBothBuildAndNoPartIsBuiltTwice() throws Exception {
        // One publication of twelve parts, which is what an import of a landscape leaves behind.
        Publication publication = Publication.askedAt(NOW);
        for (int part = 0; part < PARTS; part++) {
            requests.request(PartKey.of(SITE, "system-" + part), BuildTrigger.IMPORT, NOW, publication, false);
        }
        CountDownLatch bothStarted = new CountDownLatch(2);
        RecordingSiteBuilder onOne = new RecordingSiteBuilder(bothStarted);
        RecordingSiteBuilder onAnother = new RecordingSiteBuilder(bothStarted);
        DocumentationBuildRunner one = runnerWith(onOne);
        DocumentationBuildRunner another = runnerWith(onAnother);

        ExecutorService instances = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = instances.submit(one::runOnce);
            Future<Boolean> second = instances.submit(another::runOnce);
            assertThat(first.get(30, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            instances.shutdownNow();
        }

        assertThat(onOne.built).describedAs("the first instance should have built something").isNotEmpty();
        assertThat(onAnother.built).describedAs("the second instance should have built something").isNotEmpty();
        List<String> everything = new ArrayList<>(onOne.built);
        everything.addAll(onAnother.built);
        assertThat(everything).describedAs("every part exactly once, and all of them").hasSize(PARTS);
        assertThat(everything).doesNotHaveDuplicates();
        assertThat(requests.pending()).describedAs("nothing should still be owed").isEmpty();
    }

    /**
     * <b>Two builds at once, and neither is logged as the other.</b>
     * <p>
     * The fields naming a build are in the MDC, which is thread-local, and the slots of a pass share a thread
     * pool - so a scope that is not closed labels the next build on that thread with the part before it, and a
     * trace looked up by part would answer with somebody else's. The latch holds both instances' first build
     * open at the same time, so the two contexts really do overlap in time; the assertion is that every build
     * saw its own part and its own build id and nothing of its neighbour's.
     */
    @Test
    void whenTwoInstancesBuildAtOnce_thenNeitherBuildIsLoggedAsTheOther() throws Exception {
        Publication publication = Publication.askedAt(NOW);
        for (int part = 0; part < PARTS; part++) {
            requests.request(PartKey.of(SITE, "system-" + part), BuildTrigger.IMPORT, NOW, publication, false);
        }
        CountDownLatch bothStarted = new CountDownLatch(2);
        RecordingSiteBuilder onOne = new RecordingSiteBuilder(bothStarted);
        RecordingSiteBuilder onAnother = new RecordingSiteBuilder(bothStarted);
        DocumentationBuildRunner one = runnerWith(onOne);
        DocumentationBuildRunner another = runnerWith(onAnother);

        ExecutorService instances = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = instances.submit(one::runOnce);
            Future<Boolean> second = instances.submit(another::runOnce);
            assertThat(first.get(30, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            instances.shutdownNow();
        }

        Map<String, Map<String, String>> contexts = new LinkedHashMap<>(onOne.logContexts);
        contexts.putAll(onAnother.logContexts);
        assertThat(contexts).describedAs("one context per part, and all of them").hasSize(PARTS);
        assertThat(contexts).allSatisfy((part, context) -> assertThat(context)
                .containsEntry(BuildLogContext.SITE, SITE)
                .describedAs("the part it was building, and not the one before it on this thread")
                .containsEntry(BuildLogContext.PART, part)
                .containsKey(BuildLogContext.BUILD_ID));
        assertThat(contexts.values()).extracting(context -> context.get(BuildLogContext.BUILD_ID))
                .describedAs("a build of its own for every part")
                .doesNotHaveDuplicates();
    }

    /**
     * A build that throws an {@code Error} is a build with an outcome, and the pass keeps its books.
     * <p>
     * The task used to let one through: {@code buildOrReport} caught {@code RuntimeException}, so an
     * {@code Error} - the likely failure in a feature whose whole subject is memory pressure - arrived at the
     * pass as an {@code ExecutionException} on the branch that says it cannot happen. That branch gave up
     * every slot although one build had ended, so the parts still running were dropped from the pass's
     * bookkeeping without being settled, the busy-slots gauge under-reported for the rest of the pass, and
     * their outcome was counted nowhere. The bound itself held - the pool is of a fixed size - but nothing
     * that pass reported about itself was true.
     */
    @Test
    void whenABuildThrowsAnError_thenItIsCountedAsBrokenAndThePassKeepsItsBooks() {
        Publication publication = Publication.askedAt(NOW);
        for (int part = 0; part < PARTS; part++) {
            requests.request(PartKey.of(SITE, "system-" + part), BuildTrigger.IMPORT, NOW, publication, false);
        }
        ThrowingSiteBuilder builder = new ThrowingSiteBuilder();
        RecordingBuildMetrics metrics = new RecordingBuildMetrics();

        runnerWith(builder, metrics).runOnce();

        assertThat(builder.attempted).describedAs("every part once, and all of them").hasSize(PARTS);
        assertThat(builder.attempted).doesNotHaveDuplicates();
        // Half of them threw, and every one of those is an outcome the pass counted rather than a slot it lost.
        assertThat(metrics.broken).hasValue(PARTS / 2);
        assertThat(metrics.partsBuilt).describedAs("the other half really built").hasSize(PARTS / 2);
        assertThat(metrics.contended).hasValue(0);
        assertThat(builder.peak.get()).describedAs("never more builds at once than the two slots")
                .isLessThanOrEqualTo(2);
    }

    /**
     * <b>A request that arrives while a pass is running is served by that pass, even for a part it has already
     * built.</b>
     * <p>
     * Measured on 2026-09-07: the hourly import wrote 52 requests at 10:45:26Z, the running pass had already
     * settled all 52 parts, and nothing touched them until it ended at 11:50:57Z - sixty-five minutes, the
     * last hour of it with no build running at all. Offering a part once per pass is what keeps a pass from
     * spinning on the parts other instances hold; it was never meant to hold a part for the length of a pass
     * that has finished with it.
     * <p>
     * One slot, so the drain runs on this thread and the request can be written from inside the build - which
     * is exactly when it arrives in the case that matters, and needs no second thread to be deterministic.
     */
    @Test
    void whenAPartIsAskedForAgainWhileThePassIsBuildingIt_thenThePassBuildsItASecondTime() {
        PartKey part = PartKey.of(SITE, "system-0");
        requests.request(part, BuildTrigger.UPLOAD, NOW, null, false);
        // Once, and from inside the first build. The clock is fixed here, so a request written by every build
        // would look newer than every offer and this pass would never end - which the real clock is what
        // stops: offeredAt moves on, and requestedAt is the instant the request was first asked with.
        AtomicInteger asks = new AtomicInteger();
        CountingSiteBuilder builder = new CountingSiteBuilder(prepared -> {
            if (asks.incrementAndGet() == 1) {
                requests.request(part, BuildTrigger.UPLOAD, NOW.plusSeconds(1), null, false);
            }
        });

        boolean built = runnerWith(builder, new RecordingBuildMetrics(), locks, 1).runOnce();

        assertThat(built).isTrue();
        assertThat(builder.generated).describedAs("the same pass built it again for the second request")
                .hasSize(2);
        assertThat(requests.pendingSince(SITE)).describedAs("and nothing is left waiting for the next pass")
                .isEmpty();
    }

    /**
     * <b>The guard: a part another instance holds stays out of the pass, newer request or not.</b> Reaching
     * for it again is the spin the once-per-pass rule exists to prevent - the other instance's build is what
     * serves that request.
     * <p>
     * The lock double refuses only five times, so a broken rule fails this assertion instead of looping for
     * ever.
     */
    @Test
    void whenAPartHeldByAnotherInstanceIsAskedForAgain_thenThisPassDoesNotGoBackToIt() {
        PartKey part = PartKey.of(SITE, "system-0");
        requests.request(part, BuildTrigger.IMPORT, NOW, null, false);
        AtomicInteger attempts = new AtomicInteger();
        ExclusiveWork heldElsewhere = new ExclusiveWork() {
            @Override
            public <T> Optional<T> underLock(String name, Duration lease, Supplier<T> work) {
                if (attempts.incrementAndGet() > 5) {
                    return Optional.ofNullable(work.get());
                }
                // What the other instance does with the part, and what then arrives: it claims the standing
                // request and starts building, and a new ask lands afterwards. That leaves a pending row
                // newer than this pass's offer, which is the only shape in which the guard is tested at all.
                requests.claim(part);
                requests.request(part, BuildTrigger.UPLOAD, NOW.plusSeconds(1), null, false);
                return Optional.empty();
            }
        };
        CountingSiteBuilder builder = new CountingSiteBuilder(prepared -> {
        });

        boolean built = runnerWith(builder, new RecordingBuildMetrics(), heldElsewhere, 1).runOnce();

        assertThat(built).isFalse();
        assertThat(attempts).describedAs("reached for once, and not again within this pass").hasValue(1);
        assertThat(builder.generated).isEmpty();
        assertThat(requests.pendingSince(SITE))
                .describedAs("the request stays standing, for the other instance's build or the next pass")
                .contains(NOW.plusSeconds(1));
    }

    /** Counts the builds it ran, and does whatever the test wants done while one of them runs. */
    /**
     * The workspaces are swept once for the pass, and not once for every build in it.
     * <p>
     * The sweep was a step of {@code publish}, so once a pass kept several slots busy they all walked the
     * workspace root at the same time: each of them removed trees the others were walking, and each reported
     * what another had just removed as a workspace it had failed to remove. On ApplicationPlatform dev that
     * was every removal of a day - 37 attempts, 37 stack traces - on a service that logs no other warning of
     * its own. Housekeeping over a directory this instance owns belongs to the pass.
     */
    @Test
    void whenAPassBuildsSeveralParts_thenTheWorkspacesAreSweptOnceForThePass() {
        Publication publication = Publication.askedAt(NOW);
        for (int part = 0; part < PARTS; part++) {
            requests.request(PartKey.of(SITE, "system-" + part), BuildTrigger.IMPORT, NOW, publication, false);
        }
        CountingSiteBuilder builder = new CountingSiteBuilder(prepared -> {
            // Nothing to do while it builds: this test is about how often the sweep runs.
        });

        assertThat(runnerWith(builder).runOnce()).isTrue();

        assertThat(builder.generated).describedAs("every part should have been built").hasSize(PARTS);
        assertThat(builder.sweeps.get()).describedAs("one sweep for the pass, not one per build").isEqualTo(1);
    }

    private static final class CountingSiteBuilder implements SiteBuilder {

        private final List<String> generated = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger sweeps = new AtomicInteger();
        private final java.util.function.Consumer<PreparedPart> whileBuilding;

        private CountingSiteBuilder(java.util.function.Consumer<PreparedPart> whileBuilding) {
            this.whileBuilding = whileBuilding;
        }

        @Override
        public PreparedPart prepare(long buildId, Site site, SitePart part, Instant generatedAt) {
            // A digest of the build rather than of the part: two builds of one part in a pass must both
            // really generate, or this test would pass on a skip.
            return new PreparedPart(buildId, part, Path.of("workspace"), "digest-" + buildId);
        }

        @Override
        public BuiltSite generate(PreparedPart prepared) {
            generated.add(prepared.part().id());
            whileBuilding.accept(prepared);
            return new BuiltSite(Path.of("build"), 1, 1, 1, Map.of());
        }

        @Override
        public void describeRun(BuiltSite generated, DocumentationStatus status) {
            // What the run cost is not what this test is about.
        }

        @Override
        public void abortCurrentBuild() {
            // Nothing is stopping here.
        }

        @Override
        public void discard(long buildId) {
            // No workspace was written.
        }

        @Override
        public int sweepWorkspaces(Set<Long> runningBuildIds) {
            sweeps.incrementAndGet();
            return 0;
        }
    }

    private DocumentationBuildRunner runnerWith(SiteBuilder siteBuilder) {
        return runnerWith(siteBuilder, new RecordingBuildMetrics());
    }

    private DocumentationBuildRunner runnerWith(SiteBuilder siteBuilder, RecordingBuildMetrics metrics) {
        return runnerWith(siteBuilder, metrics, locks, 2);
    }

    private DocumentationBuildRunner runnerWith(SiteBuilder siteBuilder, RecordingBuildMetrics metrics,
                                                ExclusiveWork exclusiveWork, int slots) {
        BuildProperties properties = new BuildProperties();
        properties.setMaxConcurrentParts(slots);
        return new DocumentationBuildRunner(requests, aBuildRepository(), new DocumentationSites(
                new SiteProperties()), new SystemSitePartition(new NoArchitectureModel()), siteBuilder,
                aPublicationStorage(), properties, metrics, exclusiveWork,
                new ArchitectureModelReadiness(new NoArchitectureModel()), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /**
     * Just enough of the build record for a pass to run: an identifier per build, nothing published before, and
     * no leftover runs. What is written to it is asserted by {@link DocumentationBuildRunnerTest}.
     */
    private DocumentationBuildRepository aBuildRepository() {
        return new NoBuildHistory() {
            @Override
            public DocumentationBuild start(PartKey part, BuildTrigger trigger, String instance,
                                            Instant startedAt, Publication publication) {
                return new DocumentationBuild(buildIds.incrementAndGet(), part.site(), part.part(), trigger,
                        BuildState.RUNNING, startedAt, null, instance, null, 0, 0, 0, null, null);
            }
        };
    }

    private static SitePublicationStorage aPublicationStorage() {
        return new SitePublicationStorage() {
            @Override
            public PublishedSite publish(PartPublication where, Path directory) {
                return new PublishedSite(where.prefix(), 1, 1);
            }

            @Override
            public void delete(String prefix) {
                // Nothing was stored.
            }

            @Override
            public Optional<ch.admin.bit.jeap.doc.domain.port.StoredObject> open(String prefix, String path) {
                return Optional.empty();
            }

            @Override
            public boolean exists(String prefix, String path) {
                return false;
            }
        };
    }

    /**
     * Throws an {@code Error} out of every second build, and records how many builds were running at once
     * while each of them ran.
     */
    private static final class ThrowingSiteBuilder implements SiteBuilder {

        private final List<String> attempted = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger running = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();

        @Override
        public PreparedPart prepare(long buildId, Site site, SitePart part, Instant generatedAt) {
            return new PreparedPart(buildId, part, Path.of("workspace"), "digest-of-" + part.id());
        }


        /**
         * Every second part throws at once while the others take their time, so that a slot freed on the
         * strength of the throw is freed while a build is demonstrably still holding one.
         */
        @Override
        public BuiltSite generate(PreparedPart prepared) {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                String id = prepared.part().id();
                attempted.add(id);
                if (Integer.parseInt(id.substring("system-".length())) % 2 == 0) {
                    // What running out of memory looks like from here, which is the case this is about.
                    throw new OutOfMemoryError("the site generator could not be started");
                }
                Thread.sleep(200);
                return new BuiltSite(Path.of("build"), 1, 1, 1, Map.of());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                running.decrementAndGet();
            }
        }

        @Override
        public void describeRun(BuiltSite generated, DocumentationStatus status) {
            // What the run cost is not what this test is about.
        }

        @Override
        public void abortCurrentBuild() {
            // Nothing is stopping here.
        }

        @Override
        public void discard(long buildId) {
            // No workspace was made.
        }

        @Override
        public int sweepWorkspaces(Set<Long> runningBuildIds) {
            return 0;
        }
    }

    /** Records which parts this instance built, and holds each build until the other instance has begun. */
    private static final class RecordingSiteBuilder implements SiteBuilder {

        private final List<String> built = Collections.synchronizedList(new ArrayList<>());

        /** The log context each build saw, by the part it was building - see the MDC case above. */
        private final Map<String, Map<String, String>> logContexts =
                Collections.synchronizedMap(new LinkedHashMap<>());

        private final CountDownLatch bothStarted;
        private boolean counted;

        private RecordingSiteBuilder(CountDownLatch bothStarted) {
            this.bothStarted = bothStarted;
        }

        @Override
        public PreparedPart prepare(long buildId, Site site, SitePart part, Instant generatedAt) {
            return new PreparedPart(buildId, part, Path.of("workspace"), "digest-of-" + part.id());
        }


        @Override
        public BuiltSite generate(PreparedPart prepared) {
            firstBuildWaitsForTheOtherInstance();
            built.add(prepared.part().id());
            Map<String, String> context = org.slf4j.MDC.getCopyOfContextMap();
            logContexts.put(prepared.part().id(), context == null ? Map.of() : context);
            return new BuiltSite(Path.of("build"), 1, 1, 1, Map.of());
        }

        /**
         * Only the first: after that the queue may be drained as fast as the instance can, which is the point
         * of a pass.
         */
        private synchronized void firstBuildWaitsForTheOtherInstance() {
            if (counted) {
                return;
            }
            counted = true;
            bothStarted.countDown();
            try {
                bothStarted.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void describeRun(BuiltSite generated, DocumentationStatus status) {
            // What the run cost is not what this test is about.
        }

        @Override
        public void abortCurrentBuild() {
            // Nothing is stopping here.
        }

        @Override
        public void discard(long buildId) {
            // No workspace was made.
        }

        @Override
        public int sweepWorkspaces(Set<Long> runningBuildIds) {
            return 0;
        }
    }
}
