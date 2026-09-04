package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.BuiltSite;
import ch.admin.bit.jeap.doc.domain.port.ContainerMemory;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRequestRepository;
import ch.admin.bit.jeap.doc.domain.port.SiteBuildException;
import ch.admin.bit.jeap.doc.domain.port.SiteBuilder;
import ch.admin.bit.jeap.doc.domain.port.SitePublicationStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.scheduling.concurrent.ExecutorConfigurationSupport;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a stopping instance leaves behind, with a site generator that blocks until it is given up on - which is
 * what a Docusaurus build looks like from here.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentationBuildShutdownTest {

    private static final Instant NOW = Instant.parse("2026-08-27T09:00:00Z");
    private static final String SITE = Site.DEFAULT_SITE;

    @Mock
    private DocumentationBuildRequestRepository requests;
    @Mock
    private DocumentationBuildRepository builds;
    @Mock
    private SitePublicationStorage publication;

    private BlockingSiteBuilder siteBuilder;
    private BuildProperties properties;
    private DocumentationBuildRunner runner;
    private DocumentationBuildShutdown shutdown;
    private ExecutorService scheduler;
    private RecordingBuildMetrics metrics;

    @BeforeEach
    void setUp() {
        siteBuilder = new BlockingSiteBuilder();
        properties = new BuildProperties();
        properties.setShutdownTimeout(Duration.ofSeconds(5));
        DocumentationSites sites = new DocumentationSites(new SiteProperties());
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        metrics = new RecordingBuildMetrics();
        runner = new DocumentationBuildRunner(requests, builds, sites, siteBuilder, publication,
                properties, metrics, alwaysGranting(), alwaysReady(), ContainerMemory.NONE, clock);
        shutdown = new DocumentationBuildShutdown(runner, siteBuilder, properties);
        shutdown.start();
        scheduler = Executors.newSingleThreadExecutor();

        when(requests.pending()).thenReturn(List.of(new BuildRequest(SITE, NOW, BuildTrigger.UPLOAD)));
        when(requests.claim(SITE)).thenReturn(Optional.of(BuildTrigger.UPLOAD));
        when(builds.abandonRunning(anyString(), any())).thenReturn(List.of());
        when(builds.start(anyString(), any(), anyString(), any())).thenReturn(
                new DocumentationBuild(7L, SITE, BuildTrigger.UPLOAD, BuildState.RUNNING, NOW, null, "test",
                        null, 0, 0, 0, null, null));
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    /**
     * The phase is a number whose wrongness is silent: below the scheduler's, its stop goes first and waits for
     * the very build this is trying to cut short, so the whole budget is gone before this runs at all.
     */
    @Test
    void phase_isAboveTheTaskSchedulersOwn() {
        assertThat(shutdown.getPhase()).isGreaterThan(ExecutorConfigurationSupport.DEFAULT_PHASE);
    }

    @Test
    void stop_whenABuildIsRunning_thenItIsAbortedAskedForAgainAndItsObjectsRemoved() throws Exception {
        Future<Boolean> tick = scheduler.submit(runner::runOnce);
        assertThat(siteBuilder.started.await(5, TimeUnit.SECONDS)).isTrue();

        shutdown.stop();

        assertThat(tick.get(5, TimeUnit.SECONDS)).isTrue();
        verify(builds).aborted(eq(7L), anyString(), any());
        verify(builds, never()).failed(anyLong(), anyString(), any(), any());
        verify(builds, never()).succeeded(anyLong(), anyString(), anyInt(), anyLong(), anyLong(), any(), any());
        // The distinction the abort path exists for: the alarm counts failures, and a deployment landing on a
        // build must not page anybody.
        assertThat(metrics.results).containsExactly("aborted:" + SITE + ":UPLOAD");
        // Asked for again, so the next instance to poll runs it rather than the site waiting for its schedule.
        verify(requests).request(eq(SITE), eq(BuildTrigger.UPLOAD), any());
        verify(publication).delete("default/7");
        verify(siteBuilder.discarded).accept(7L);
    }

    /**
     * The writes are what makes a stop quiet, not what makes it correct. A database that is already away must
     * cost the shutdown nothing beyond a warning - the build is recovered from its row either way.
     */
    @Test
    void stop_whenEveryWriteFails_thenItStillReturnsInsideItsBudget() throws Exception {
        when(builds.aborted(anyLong(), anyString(), any())).thenThrow(new IllegalStateException("no connection"));
        when(requests.request(anyString(), any(), any())).thenThrow(new IllegalStateException("no connection"));
        doThrowOnDelete();

        Future<Boolean> tick = scheduler.submit(runner::runOnce);
        assertThat(siteBuilder.started.await(5, TimeUnit.SECONDS)).isTrue();

        long startedAt = System.nanoTime();
        shutdown.stop();
        Duration took = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(tick.get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(took).isLessThan(properties.getShutdownTimeout());
        // Every step was attempted, none of them stopped the next.
        verify(builds).aborted(eq(7L), anyString(), any());
        verify(requests).request(eq(SITE), any(), any());
        verify(publication).delete("default/7");
    }

    /**
     * A build that will not end must not hold the shutdown past its budget: overrunning the phase timeout is
     * what lets the context destroy the connection pool while the build thread is still writing.
     */
    @Test
    void stop_whenTheBuildDoesNotEnd_thenItGivesUpAtItsBudget() throws Exception {
        properties.setShutdownTimeout(Duration.ofMillis(500));
        siteBuilder.ignoreAbort = true;
        Future<Boolean> tick = scheduler.submit(runner::runOnce);
        assertThat(siteBuilder.started.await(5, TimeUnit.SECONDS)).isTrue();

        long startedAt = System.nanoTime();
        shutdown.stop();
        Duration took = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(took).isLessThan(Duration.ofSeconds(4));
        siteBuilder.release();
        tick.get(5, TimeUnit.SECONDS);
    }

    @Test
    void runOnce_whenTheInstanceIsStopping_thenNoBuildIsStarted() {
        shutdown.stop();

        assertThat(runner.runOnce()).isFalse();

        verify(builds, never()).start(anyString(), any(), anyString(), any());
    }

    @Test
    void stop_whenNothingIsBuilding_thenItReturnsAtOnce() {
        long startedAt = System.nanoTime();

        shutdown.stop();

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(2));
        assertThat(shutdown.isRunning()).isFalse();
    }

    @Test
    void stop_whenItIsCalledTwice_thenTheSecondDoesNothing() {
        shutdown.stop();
        shutdown.stop();

        assertThat(siteBuilder.aborts).isEqualTo(1);
    }

    /**
     * An interrupt makes the connection pool refuse to hand out a connection, so the bookkeeping clears it and
     * puts it back afterwards. Without that, a stop that arrived as an interrupt would write none of the three
     * things it exists to write - and the flag has to be restored, or whoever owns the thread loses it.
     * <p>
     * <b>The interrupt is delivered by the abort</b>, not raced against it. Interrupting from here and then
     * calling {@code stop()} leaves the ordering to the scheduler: an interrupt that lands before the stop has
     * begun unblocks the generator while this instance is <i>not</i> stopping, which is a build that failed and
     * is recorded as one - correctly, and not what this test is about. Sending it from inside
     * {@code abortCurrentBuild} puts it exactly where a real stop puts it.
     */
    @Test
    void stop_whenTheBuildThreadIsInterrupted_thenTheBookkeepingStillRunsAndTheInterruptIsPutBack() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean interruptedAtTheEnd = new java.util.concurrent.atomic.AtomicBoolean();
        Thread ticking = new Thread(() -> {
            runner.runOnce();
            interruptedAtTheEnd.set(Thread.currentThread().isInterrupted());
        });
        ticking.start();
        assertThat(siteBuilder.started.await(5, TimeUnit.SECONDS)).isTrue();
        siteBuilder.interruptOnAbort = ticking;

        shutdown.stop();
        ticking.join(10_000);

        verify(builds).aborted(eq(7L), anyString(), any());
        verify(requests).request(eq(SITE), eq(BuildTrigger.UPLOAD), any());
        verify(publication).delete("default/7");
        assertThat(interruptedAtTheEnd).isTrue();
    }

    private void doThrowOnDelete() {
        org.mockito.Mockito.doThrow(new IllegalStateException("the bucket said no"))
                .when(publication).delete(anyString());
    }

    /** What is under test is the runner, not the lock table - so every attempt succeeds. */
    private static ch.admin.bit.jeap.doc.domain.port.ExclusiveWork alwaysGranting() {
        return new ch.admin.bit.jeap.doc.domain.port.ExclusiveWork() {
            @Override
            public <T> Optional<T> underLock(String name, Duration lease, java.util.function.Supplier<T> work) {
                return Optional.ofNullable(work.get());
            }
        };
    }

    /**
     * A site generator that blocks until it is given up on, the way a real one blocks until Docusaurus is done.
     */
    private static class BlockingSiteBuilder implements SiteBuilder {

        @Override
        public void describeRun(ch.admin.bit.jeap.doc.domain.port.BuiltSite generated,
                                ch.admin.bit.jeap.doc.domain.port.DocumentationStatus status) {
            // What the run cost is not what this test is about.
        }

        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch aborted = new CountDownLatch(1);
        @SuppressWarnings("unchecked")
        private final java.util.function.Consumer<Long> discarded = mock(java.util.function.Consumer.class);

        private volatile boolean ignoreAbort;
        private volatile int aborts;

        /** Interrupted when the build is given up on, which is where a real stop delivers an interrupt. */
        private volatile Thread interruptOnAbort;

        @Override
        public BuiltSite generate(long buildId, Site site, java.time.Instant generatedAt) {
            started.countDown();
            try {
                if (!aborted.await(30, TimeUnit.SECONDS)) {
                    return new BuiltSite(Path.of("build"), 1, 1, 1, Map.of());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new SiteBuildException("The site generator was given up on: this instance is stopping.");
        }

        @Override
        public void abortCurrentBuild() {
            aborts++;
            if (interruptOnAbort != null) {
                interruptOnAbort.interrupt();
            }
            if (!ignoreAbort) {
                aborted.countDown();
            }
        }

        void release() {
            aborted.countDown();
        }

        @Override
        public void discard(long buildId) {
            discarded.accept(buildId);
        }

        @Override
        public int sweepWorkspaces(java.util.Set<Long> runningBuildIds) {
            return 0;
        }
    }

    /** A readiness that never holds a site back; what does is ArchitectureModelReadinessTest's business. */
    private static ArchitectureModelReadiness alwaysReady() {
        return new ArchitectureModelReadiness(new ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource() {

            @Override
            public boolean isConfiguredFor(String environment) {
                return false;
            }

            @Override
            public java.util.Optional<String> sourceUrlOf(String environment) {
                return java.util.Optional.empty();
            }

            @Override
            public java.util.Optional<java.time.Instant> lastSuccessfulImportAt(String environment) {
                return java.util.Optional.empty();
            }

            @Override
            public ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureSnapshot read(String environment) {
                return ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureSnapshot.empty();
            }
        });
    }
}
