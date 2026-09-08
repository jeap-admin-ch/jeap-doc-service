package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.BuiltSite;
import ch.admin.bit.jeap.doc.domain.port.DocumentationStatus;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRequestRepository;
import ch.admin.bit.jeap.doc.domain.port.PartPublication;
import ch.admin.bit.jeap.doc.domain.port.PreparedPart;
import ch.admin.bit.jeap.doc.domain.port.PublishedSite;
import ch.admin.bit.jeap.doc.domain.port.SiteBuildException;
import ch.admin.bit.jeap.doc.domain.port.SiteBuildTimeoutException;
import ch.admin.bit.jeap.doc.domain.port.SiteBuilder;
import ch.admin.bit.jeap.doc.domain.port.SitePublicationStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentationBuildRunnerTest {


    private static final Instant NOW = Instant.parse("2026-08-25T09:00:00Z");
    private static final String SITE = Site.DEFAULT_SITE;
    /** Every site has a shell part, and a site with no architecture repository has only that one. */
    private static final PartKey SHELL = PartKey.shellOf(SITE);

    @Mock
    private DocumentationBuildRequestRepository requests;
    @Mock
    private DocumentationBuildRepository builds;
    @Mock
    private SiteBuilder siteBuilder;
    @Mock
    private SitePublicationStorage publication;

    private DocumentationSites sites;
    private BuildProperties properties;
    private RecordingExclusiveWork locks;
    private RecordingBuildMetrics metrics;
    private ArchitectureModelReadiness readiness;
    /** What the container did while a build ran - set by the tests that are about the line it produces. */
    private DocumentationBuildRunner runner;

    @BeforeEach
    void setUp() {
        sites = new DocumentationSites(new SiteProperties());
        properties = new BuildProperties();
        locks = new RecordingExclusiveWork();
        readiness = new ArchitectureModelReadiness(NoArchitectureRepository.INSTANCE);
        metrics = new RecordingBuildMetrics();
        runner = new DocumentationBuildRunner(requests, builds, sites,
                new SystemSitePartition(NoArchitectureRepository.INSTANCE), siteBuilder, publication,
                properties, metrics, locks, readiness, Clock.fixed(NOW, ZoneOffset.UTC));

        when(builds.start(any(), any(), anyString(), any(), any())).thenReturn(build(7L, BuildState.RUNNING));
        when(siteBuilder.prepare(anyLong(), any(), any(), any())).thenAnswer(invocation -> new PreparedPart(
                invocation.getArgument(0), invocation.getArgument(2), Path.of("workspace"), "digest-of-now"));
        when(siteBuilder.generate(any())).thenReturn(new BuiltSite(Path.of("build"), 12, 4096, 900, Map.of()));
        when(publication.publish(any(), any())).thenAnswer(invocation -> {
            PartPublication where = invocation.getArgument(0);
            return new PublishedSite(where == null ? null : where.prefix(), 30, 4096);
        });
    }

    /**
     * The reason on the row is the generator's own message, and nothing else. It used to carry a clause about
     * what the container held; the per-build peak is gone - what the container does is a series to query.
     */
    @Test
    void runOnce_whenTheBuildFails_thenTheReasonIsWhatTheGeneratorSaid() {
        pending(SITE);
        when(siteBuilder.generate(any()))
                .thenThrow(new SiteBuildException("The site generator exited with 137."));

        assertThat(runner.runOnce()).isTrue();

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(builds).failed(eq(7L), reason.capture(), any());
        assertThat(reason.getValue()).isEqualTo("The site generator exited with 137.");
    }

    @Test
    void runOnce_whenNothingIsPending_thenNothingHappens() {
        when(requests.pending()).thenReturn(List.of());

        assertThat(runner.runOnce()).isFalse();

        verify(siteBuilder, never()).generate(any());
    }

    /**
     * The order the whole design rests on: the lock first, then the request, then the inputs.
     */
    @Test
    void runOnce_thenTheLockIsTakenBeforeTheRequestIsClaimedAndTheRequestBeforeAnythingIsRead() {
        pending(SITE);

        assertThat(runner.runOnce()).isTrue();

        assertThat(locks.taken).containsExactly(DocumentationBuildRunner.LOCK_PREFIX + SHELL);
        InOrder order = inOrder(requests, siteBuilder, publication, builds);
        order.verify(requests).claim(SHELL);
        order.verify(siteBuilder).generate(any());
        order.verify(publication).publish(any(), any());
        order.verify(builds).succeeded(eq(7L), anyString(), anyInt(), anyLong(), anyLong(), anyString(), any());
    }

    /**
     * Claiming first and then finding the lock held would throw the request away, and nobody would ask again
     * until the next upload or the next schedule.
     */
    @Test
    void runOnce_whenAnotherInstanceHoldsTheLock_thenTheRequestStaysPending() {
        pending(SITE);
        locks.setRefuse(true);

        assertThat(runner.runOnce()).isFalse();

        verify(requests, never()).claim(SHELL);
        verify(siteBuilder, never()).generate(any());
    }

    @Test
    void runOnce_whenTheRequestWasClaimedByAnotherInstanceMeanwhile_thenNothingIsBuilt() {
        when(requests.pending()).thenReturn(List.of(new BuildRequest(SHELL, NOW, BuildTrigger.UPLOAD, null, false)));
        when(requests.claim(SHELL)).thenReturn(Optional.empty());

        assertThat(runner.runOnce()).isFalse();

        verify(siteBuilder, never()).generate(any());
    }

    /**
     * Two sites configured on this instance, so that a tick has more than one part to choose from. Both have
     * one part - their shell - because neither reads an architecture model here.
     */
    private void withTwoSitesConfigured() {
        SiteProperties configured = new SiteProperties();
        configured.setSites(new java.util.LinkedHashMap<>(java.util.Map.of(
                Site.DEFAULT_SITE, new SiteProperties.Site(),
                "governance", new SiteProperties.Site())));
        sites = new DocumentationSites(configured);
        runner = new DocumentationBuildRunner(requests, builds, sites,
                new SystemSitePartition(NoArchitectureRepository.INSTANCE), siteBuilder, publication,
                properties, metrics, locks, readiness, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /**
     * An instance not knowing a site is not evidence that no instance does: during a rolling deployment that
     * <b>adds</b> a site, half the instances have it and half do not, and the ones that do not would otherwise
     * delete the requests the others are serving. A claimed request is gone, so the build would simply never
     * run and nothing would say why.
     */
    @Test
    void runOnce_whenTheSiteIsUnknownAndTheRequestIsRecent_thenItIsLeftForAnInstanceThatKnowsIt() {
        when(requests.pending())
                .thenReturn(List.of(new BuildRequest(PartKey.shellOf("gone"), NOW, BuildTrigger.UPLOAD, null, false)));
        when(requests.pendingSince("gone")).thenReturn(Optional.of(NOW.minus(Duration.ofSeconds(30))));

        assertThat(runner.runOnce()).isFalse();

        verify(requests, never()).claim(PartKey.shellOf("gone"));
        verify(siteBuilder, never()).generate(any());
    }

    /**
     * A site that really is gone must not leave its request growing the age gauge for ever, so it is dropped
     * once no instance has served it for long enough that no deployment could still be in progress.
     */
    @Test
    void runOnce_whenTheSiteIsUnknownAndNobodyHasServedTheRequest_thenItIsDropped() {
        when(requests.pending())
                .thenReturn(List.of(new BuildRequest(PartKey.shellOf("gone"), NOW, BuildTrigger.UPLOAD, null, false)));
        when(requests.pendingSince("gone")).thenReturn(Optional.of(NOW.minus(Duration.ofHours(24))));

        assertThat(runner.runOnce()).isFalse();

        verify(requests).claim(PartKey.shellOf("gone"));
        verify(siteBuilder, never()).generate(any());
    }

    /**
     * A site removed from the configuration while a build of it was running would otherwise leave a row that is
     * RUNNING for ever: warned about on every tick, and pinning a workspace the sweep may then never remove.
     */
    @Test
    void runOnce_whenASiteIsGoneButLeftARunningBuild_thenThatBuildIsGivenUpOnUnderItsLock() {
        when(requests.pending()).thenReturn(List.of());
        when(builds.partsWithRunningBuilds()).thenReturn(Set.of(PartKey.shellOf("gone")));
        when(builds.abandonRunning(eq(PartKey.shellOf("gone")), any()))
                .thenReturn(List.of(build(3L, BuildState.RUNNING).abandonedAt(NOW)));
        when(requests.pendingSince("gone")).thenReturn(Optional.empty());

        assertThat(runner.runOnce()).isFalse();

        verify(builds).abandonRunning(eq(PartKey.shellOf("gone")), any());
        verify(builds, never()).start(any(), any(), anyString(), any(), any());
        // Under the lock, because the sites are per-instance configuration: an instance that still has this one
        // is entitled to be building it, and marking a live build as abandoned would be false evidence.
        assertThat(locks.taken).containsExactly(DocumentationBuildRunner.LOCK_PREFIX + PartKey.shellOf("gone"));
        assertThat(metrics.abandoned).containsExactly("gone:1");
    }

    @Test
    void runOnce_whenTheSiteGeneratorFails_thenTheBuildFailsAndWhatIsPublishedStaysPublished() {
        pending(SITE);
        when(siteBuilder.generate(any())).thenThrow(new SiteBuildException("exited with 1"));

        assertThat(runner.runOnce()).isTrue();

        verify(builds).failed(eq(7L), eq("exited with 1"), any());
        assertThat(metrics.results).containsExactly("failed:" + SITE + ":UPLOAD");
        verify(builds, never()).succeeded(anyLong(), anyString(), anyInt(), anyLong(), anyLong(),
                anyString(), any());
        verify(publication, never()).publish(any(), any());
        verify(siteBuilder).discard(7L);
    }

    /**
     * A build that ran out of time is failed on the row like any other - there is one way for a build to end
     * badly - but counted apart, because it is not put right the way a broken build is and because its
     * duration is the budget every time.
     */
    @Test
    void runOnce_whenTheSiteGeneratorTimesOut_thenTheBuildIsFailedOnTheRowAndCountedAsATimeout() {
        pending(SITE);
        when(siteBuilder.generate(any()))
                .thenThrow(new SiteBuildTimeoutException("did not finish within PT15M"));

        assertThat(runner.runOnce()).isTrue();

        verify(builds).failed(eq(7L), eq("did not finish within PT15M"), any());
        assertThat(metrics.results).containsExactly("timed-out:" + SITE + ":UPLOAD");
        verify(siteBuilder).discard(7L);
    }

    @Test
    void runOnce_whenPublishingFails_thenTheBuildFailsAndTheWorkspaceIsStillRemoved() {
        pending(SITE);
        when(publication.publish(any(), any())).thenThrow(new IllegalStateException("the bucket said no"));

        assertThat(runner.runOnce()).isTrue();

        verify(builds).failed(eq(7L), eq("the bucket said no"), any());
        verify(siteBuilder).discard(7L);
        assertThat(metrics.results).containsExactly("failed:" + SITE + ":UPLOAD");
    }

    @Test
    void runOnce_thenABuildOfThisSiteThatLostItsLeaseIsGivenUpOnFirst() {
        pending(SITE);
        abandons(SITE, build(3L, BuildState.RUNNING, BuildTrigger.UPLOAD));

        runner.runOnce();

        InOrder order = inOrder(builds);
        order.verify(builds).abandonRunning(eq(SHELL), any());
        order.verify(builds).start(eq(SHELL), any(), anyString(), any(), any());
    }

    /**
     * The recovery this whole arrangement exists for: nothing asks for the build any more - the request was
     * claimed when it started - so the row that is still running is what says one is owed.
     */
    @Test
    void runOnce_whenABuildWasLeftRunningAndNothingAsksForIt_thenTheSiteIsBuiltAsARecovery() {
        when(requests.pending()).thenReturn(List.of());
        when(requests.claim(SHELL)).thenReturn(Optional.empty());
        when(builds.partsWithRunningBuilds()).thenReturn(Set.of(SHELL));
        abandons(SITE, build(3L, BuildState.RUNNING, BuildTrigger.UPLOAD));

        assertThat(runner.runOnce()).isTrue();

        verify(builds).start(eq(SHELL), eq(BuildTrigger.RECOVERY), anyString(), any(), any());
        verify(builds).succeeded(eq(7L), anyString(), anyInt(), anyLong(), anyLong(), anyString(), any());
    }

    /**
     * One automatic attempt is a crashed instance; two in a row is a build that kills whatever runs it, and
     * repeating it would be a crash loop rather than a recovery.
     */
    @Test
    void runOnce_whenTheLostBuildWasItselfARecovery_thenItIsNotRunAgain() {
        when(requests.pending()).thenReturn(List.of());
        when(requests.claim(SHELL)).thenReturn(Optional.empty());
        when(builds.partsWithRunningBuilds()).thenReturn(Set.of(SHELL));
        abandons(SITE, build(3L, BuildState.RUNNING, BuildTrigger.RECOVERY));

        assertThat(runner.runOnce()).isFalse();

        verify(builds).abandonRunning(eq(SHELL), any());
        verify(builds, never()).start(any(), any(), anyString(), any(), any());
    }

    /**
     * A request beats a recovery: it is the newer fact, and it carries what actually asked.
     */
    @Test
    void runOnce_whenTheSiteIsBothRequestedAndWasLeftRunning_thenTheRequestIsTheTrigger() {
        pending(SITE);
        when(builds.partsWithRunningBuilds()).thenReturn(Set.of(SHELL));
        abandons(SITE, build(3L, BuildState.RUNNING, BuildTrigger.UPLOAD));

        assertThat(runner.runOnce()).isTrue();

        verify(builds).start(eq(SHELL), eq(BuildTrigger.UPLOAD), anyString(), any(), any());
    }

    /**
     * A site that is named twice - once by its request and once by its leftover row - is still one site, and one
     * build per tick.
     */
    @Test
    void runOnce_whenASiteIsBothRequestedAndRunning_thenItIsConsideredOnce() {
        pending(SITE);
        when(builds.partsWithRunningBuilds()).thenReturn(Set.of(SHELL));

        assertThat(runner.runOnce()).isTrue();

        verify(builds, times(1)).start(eq(SHELL), any(), anyString(), any(), any());
    }

    /**
     * Nothing pending and nothing left running is the ordinary tick, and it must not take a lock: it happens
     * every poll interval on every instance.
     */
    @Test
    void runOnce_whenNothingIsPendingAndNothingWasLeftRunning_thenNoLockIsTaken() {
        when(requests.pending()).thenReturn(List.of());
        when(builds.partsWithRunningBuilds()).thenReturn(Set.of());

        assertThat(runner.runOnce()).isFalse();

        assertThat(locks.taken).isEmpty();
    }

    @Test
    void runOnce_thenTheWorkspacesOfBuildsThatAreNoLongerRunningAreSweptFirst() {
        pending(SITE);
        when(builds.runningIds()).thenReturn(Set.of(7L));

        runner.runOnce();

        verify(siteBuilder).sweepWorkspaces(Set.of(7L));
    }

    @Test
    void runOnce_thenTheSiteIsPublishedUnderTheBuildThatProducedIt() {
        pending(SITE);

        runner.runOnce();

        verify(publication).publish(any(), any());
    }

    /**
     * The retention runs after the new site is the published one, so a reader is never left without a site while
     * the old one is being deleted.
     */
    @Test
    void runOnce_thenSitesBeyondTheRetentionAreRemovedAfterTheNewOneIsPublished() {
        pending(SITE);
        when(builds.prefixesBeyondRetention(SHELL, properties.getRetention()))
                .thenReturn(List.of(SITE + "/3", SITE + "/4"));

        runner.runOnce();

        InOrder order = inOrder(builds, publication);
        order.verify(builds).succeeded(eq(7L), anyString(), anyInt(), anyLong(), anyLong(), anyString(), any());
        order.verify(publication).delete(SITE + "/3");
        order.verify(publication).delete(SITE + "/4");
    }

    /**
     * Once a build is the published site, nothing that runs after it may take that back. A database hiccup
     * while measuring it or clearing away what it superseded used to rewrite the row as FAILED - and while
     * stopping, as ABORTED with the published objects deleted.
     */
    @Test
    void runOnce_whenTheHousekeepingAfterAPublicationFails_thenTheBuildStaysPublished() {
        pending(SITE);
        when(builds.prefixesBeyondRetention(any(), anyInt()))
                .thenThrow(new IllegalStateException("the database went away"));

        assertThat(runner.runOnce()).isTrue();

        verify(builds).succeeded(eq(7L), anyString(), anyInt(), anyLong(), anyLong(), anyString(), any());
        verify(builds, never()).failed(anyLong(), anyString(), any());
        verify(builds, never()).aborted(anyLong(), anyString(), any());
        verify(publication, never()).delete(SITE + "/7");
    }

    /**
     * The same while the instance is stopping: the abort path deletes the build's objects, and a build that is
     * already the published site must never reach it.
     */
    @Test
    void runOnce_whenTheHousekeepingFailsWhileStopping_thenThePublishedSiteIsNotDeleted() {
        pending(SITE);
        when(builds.prefixesBeyondRetention(any(), anyInt()))
                .thenAnswer(invocation -> {
                    runner.stopAcceptingBuilds();
                    throw new IllegalStateException("the database went away");
                });

        assertThat(runner.runOnce()).isTrue();

        verify(builds).succeeded(eq(7L), anyString(), anyInt(), anyLong(), anyLong(), anyString(), any());
        verify(builds, never()).aborted(anyLong(), anyString(), any());
        verify(publication, never()).delete(SITE + "/7");
    }

    @Test
    void runOnce_whenRemovingAnObsoleteSiteFails_thenTheBuildStillCounts() {
        pending(SITE);
        when(builds.prefixesBeyondRetention(any(), anyInt())).thenReturn(List.of(SITE + "/3"));
        doThrow(new IllegalStateException("no")).when(publication).delete(SITE + "/3");

        assertThat(runner.runOnce()).isTrue();

        verify(builds).succeeded(eq(7L), anyString(), anyInt(), anyLong(), anyLong(), anyString(), any());
    }

    /**
     * Several parts of a site are built at once, up to the configured bound: a full round of a large landscape
     * is a great many builds, and one after another they pay the fixed cost of a Docusaurus start each while
     * the cores of the container sit idle.
     */
    @Test
    void runOnce_whenSeveralPartsAreOwedABuild_thenTheyAreBuiltTogetherUpToTheBound() {
        withTwoSitesConfigured();
        pendingTwoParts();
        properties.setMaxConcurrentParts(3);

        assertThat(runner.runOnce()).isTrue();

        // Both of them, in one tick, and each under its own lock - two parts never wait for each other.
        verify(builds).start(eq(SHELL), any(), anyString(), any(), any());
        verify(builds).start(eq(PartKey.shellOf("governance")), any(), anyString(), any(), any());
        assertThat(locks.taken).containsExactlyInAnyOrder(
                DocumentationBuildRunner.LOCK_PREFIX + SHELL,
                DocumentationBuildRunner.LOCK_PREFIX + PartKey.shellOf("governance"));
    }

    /**
     * The bound is a memory decision: every concurrent build holds a Docusaurus run in the same container. At
     * one, an instance builds one part at a time - and still builds the next one straight away, because
     * waiting a poll interval between them is idle time and not a memory saving.
     */
    @Test
    void runOnce_whenOnlyOnePartMayBeBuiltAtATime_thenTheyAreBuiltOneAfterAnother() {
        withTwoSitesConfigured();
        pendingTwoParts();
        properties.setMaxConcurrentParts(1);
        Concurrency concurrency = recordConcurrentBuilds();

        assertThat(runner.runOnce()).isTrue();

        verify(builds, times(2)).start(any(), any(), anyString(), any(), any());
        assertThat(concurrency.peak()).isOne();
    }

    /**
     * <b>A slot is refilled as soon as its build is done</b>, rather than when the whole batch is.
     * <p>
     * The parts of a landscape are systems and their sizes differ by a lot, so a batch that waits for its
     * slowest part leaves the other slots empty for most of it. The first part here does not finish until four
     * others have started, which can only happen if the slots freed by the quick ones were filled again.
     */
    @Test
    void runOnce_whenAPartIsSlow_thenTheSlotsFreedByTheQuickOnesAreFilledAgain() throws Exception {
        pendingParts(SHELL, part("alpha"), part("beta"), part("gamma"), part("delta"));
        properties.setMaxConcurrentParts(3);
        CountDownLatch fourMoreStarted = new CountDownLatch(4);
        blockTheFirstBuildUntil(fourMoreStarted);

        assertThat(runner.runOnce()).isTrue();

        // Not a timeout: the latch was counted down by four builds that started while the first one was still
        // running, and with three slots that is only possible if a freed slot was refilled.
        assertThat(fourMoreStarted.getCount()).isZero();
        verify(builds, times(5)).start(any(), any(), anyString(), any(), any());
    }

    /**
     * Holds the first build in the site generator until the latch is counted down by the four that follow it,
     * and counts it down from each of those. The wait is bounded so that a regression fails the test instead
     * of hanging it.
     */
    private void blockTheFirstBuildUntil(CountDownLatch fourMoreStarted) {
        AtomicBoolean first = new AtomicBoolean(true);
        when(siteBuilder.generate(any())).thenAnswer(invocation -> {
            if (first.compareAndSet(true, false)) {
                fourMoreStarted.await(20, TimeUnit.SECONDS);
            } else {
                fourMoreStarted.countDown();
            }
            return new BuiltSite(Path.of("build"), 12, 4096, 900, Map.of());
        });
    }

    /** How many builds this instance had running at once, at the most. */
    private Concurrency recordConcurrentBuilds() {
        Concurrency concurrency = new Concurrency();
        when(siteBuilder.generate(any())).thenAnswer(invocation -> {
            concurrency.entered();
            try {
                Thread.sleep(20);
                return new BuiltSite(Path.of("build"), 12, 4096, 900, Map.of());
            } finally {
                concurrency.left();
            }
        });
        return concurrency;
    }

    private static final class Concurrency {

        private final AtomicInteger running = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();

        void entered() {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
        }

        void left() {
            running.decrementAndGet();
        }

        int peak() {
            return peak.get();
        }
    }

    /**
     * <b>Whether the slots were used is the one thing no other meter says.</b> Every build of a pass can
     * succeed, in a perfectly normal time, while most of the capacity the container was sized for stood empty.
     */
    @Test
    void runOnce_thenTheBusySlotsAreReportedWhileItRunsAndGivenBackAtTheEnd() {
        withTwoSitesConfigured();
        pendingTwoParts();

        assertThat(runner.runOnce()).isTrue();

        assertThat(metrics.slotsBusy).describedAs("the slots have to be given back at the end of a pass")
                .last().isEqualTo(0);
        assertThat(metrics.slotsBusy).describedAs("and they were used while it ran").contains(1);
        assertThat(metrics.contended).describedAs("nothing was held elsewhere").hasValue(0);
        assertThat(metrics.broken).hasValue(0);
    }

    /** At one slot the numbers have to mean the same thing: the slot is busy while its build runs. */
    @Test
    void runOnce_whenOnlyOnePartMayBeBuiltAtATime_thenTheSlotIsStillReportedAsBusy() {
        withTwoSitesConfigured();
        pendingTwoParts();
        properties.setMaxConcurrentParts(1);

        assertThat(runner.runOnce()).isTrue();

        assertThat(metrics.slotsBusy).containsExactly(1, 0, 1, 0, 0);
    }

    /**
     * A published build says what its part cost; a build the digest skipped says nothing about it. Averaging
     * the skips in is what would make a part that costs three quarters of an hour look cheap.
     */
    @Test
    void runOnce_whenTheContentIsAlreadyPublished_thenNothingIsReportedAboutWhatThePartCost() {
        pending(SITE);
        alreadyPublished("digest-of-now");

        assertThat(runner.runOnce()).isTrue();

        assertThat(metrics.results).containsExactly("skipped:" + SITE + ":UPLOAD");
        assertThat(metrics.partsBuilt).isEmpty();
    }

    /**
     * A build somebody asked for by hand is never skipped, and the reason it is the <b>request</b> that says so
     * rather than its trigger is this: two asks for one part are one row, and that row keeps the trigger that
     * asked first. Reading the trigger meant a forced publication was skipped for exactly the parts the hourly
     * import had already asked for - most of them, most of the hour.
     */
    @Test
    void runOnce_whenTheRequestWasForcedAndTheContentIsPublished_thenItIsBuiltAnyway() {
        // What an import asked for, then forced: the trigger is still the import's, the force is the
        // operator's.
        BuildRequest forced = new BuildRequest(SHELL, NOW, BuildTrigger.IMPORT, null, true);
        when(requests.pending()).thenReturn(List.of(forced));
        when(requests.claim(SHELL)).thenReturn(Optional.of(forced));
        alreadyPublished("digest-of-now");

        assertThat(runner.runOnce()).isTrue();

        assertThat(metrics.results).containsExactly("succeeded:" + SITE + ":IMPORT");
        assertThat(metrics.partsBuilt).containsExactly(SITE + "/" + SitePart.SHELL);
    }

    @Test
    void runOnce_whenAPartIsPublished_thenWhatItCostIsReportedForThatPart() {
        pending(PartKey.of(SITE, "system-orders"));

        assertThat(runner.runOnce()).isTrue();

        assertThat(metrics.partsBuilt).containsExactly(SITE + "/system-orders");
    }

    /**
     * The build inherits the publication of the request it claimed, which is what carries the identifier from
     * the ask onto the rows the wall clock is read off.
     */
    @Test
    void runOnce_whenTheRequestIsPartOfAPublication_thenTheBuildIsToo() {
        Publication publication = Publication.askedAt(NOW);
        BuildRequest request = new BuildRequest(SHELL, NOW, BuildTrigger.MANUAL, publication, true);
        when(requests.pending()).thenReturn(List.of(request));
        when(requests.claim(SHELL)).thenReturn(Optional.of(request));

        assertThat(runner.runOnce()).isTrue();

        verify(builds).start(eq(SHELL), any(), anyString(), any(), eq(publication));
    }

    /** A pass that lost every part to another instance says so, rather than looking like a pass with no work. */
    @Test
    void runOnce_whenEveryPartIsBeingBuiltElsewhere_thenThePassReportsTheContention() {
        withTwoSitesConfigured();
        pendingTwoParts();
        locks.setRefuse(true);

        assertThat(runner.runOnce()).isFalse();

        assertThat(metrics.contended).describedAs("both parts, counted as each was settled").hasValue(2);
    }

    @Test
    void runOnce_thenTheLockIsLeasedForTheConfiguredLeaseAndNotForTheBuild() {
        pending(SITE);
        properties.setTimeout(Duration.ofMinutes(20));
        properties.setLockLease(Duration.ofMinutes(2));

        runner.runOnce();

        // Two minutes for a build that may take twenty: the lock is extended while the build runs, so the
        // lease sizes how long a killed instance blocks its site rather than how long a build may take. That it
        // is released the moment the build is over is the adapter's doing, and is asserted there.
        assertThat(locks.leases).containsExactly(Duration.ofMinutes(2));
    }

    /**
     * Two parts of one site owed a build at once, which is what a reconcile pass leaves behind.
     * <p>
     * The site of these tests has no architecture repository, so its partition produces one part - the shell.
     * A second part is therefore made up here: what the runner does with several is about the tick, not about
     * the axis.
     */
    private void pendingTwoParts() {
        BuildRequest shell = new BuildRequest(SHELL, NOW, BuildTrigger.IMPORT, null, false);
        BuildRequest second = new BuildRequest(PartKey.shellOf("governance"), NOW, BuildTrigger.IMPORT, null,
                false);
        when(requests.pending()).thenReturn(List.of(shell, second));
        when(requests.claim(any())).thenAnswer(invocation ->
                Optional.of(SHELL.equals(invocation.getArgument(0)) ? shell : second));
    }

    private void pending(String site) {
        pending(PartKey.shellOf(site));
    }

    /**
     * What is being served for the shell part: a build that succeeded, with the digest given. A build whose
     * content hashes to it is the skip path.
     */
    private void alreadyPublished(String digest) {
        DocumentationBuild published = new DocumentationBuild(3L, SITE, SitePart.SHELL, BuildTrigger.IMPORT,
                BuildState.SUCCEEDED, NOW, NOW, "test", SITE + "/3", 12, 4096, 900, null, digest);
        when(builds.published(SHELL)).thenReturn(Optional.of(published));
    }

    /** A part of the default site named after a system, which the partition resolves without a model. */
    private static PartKey part(String system) {
        return PartKey.of(SITE, "system-" + system);
    }

    private void pendingParts(PartKey... parts) {
        List<BuildRequest> pending = java.util.Arrays.stream(parts)
                .map(part -> new BuildRequest(part, NOW, BuildTrigger.IMPORT, null, false))
                .toList();
        when(requests.pending()).thenReturn(pending);
        when(requests.claim(any())).thenAnswer(invocation -> pending.stream()
                .filter(request -> request.part().equals(invocation.getArgument(0)))
                .findFirst());
    }

    private void pending(PartKey part) {
        BuildRequest request = new BuildRequest(part, NOW, BuildTrigger.UPLOAD, null, false);
        when(requests.pending()).thenReturn(List.of(request));
        when(requests.claim(part)).thenReturn(Optional.of(request));
    }

    private void abandons(String site, DocumentationBuild... running) {
        when(builds.abandonRunning(eq(PartKey.shellOf(site)), any()))
                .thenReturn(java.util.Arrays.stream(running).map(build -> build.abandonedAt(NOW)).toList());
    }

    private static DocumentationBuild build(long id, BuildState state) {
        return build(id, state, BuildTrigger.UPLOAD);
    }

    private static DocumentationBuild build(long id, BuildState state, BuildTrigger trigger) {
        return new DocumentationBuild(id, SITE, SitePart.SHELL, trigger, state, NOW, null, "test", null, 0, 0, 0, null, null);
    }

    /**
     * What the runner's use of the lock looks like from the domain's side. Refusing is exactly what a site
     * another instance is building looks like.
     */
    /**
     * An instance with no architecture repository, so that a site is never held back for want of a model. What
     * holding one back does is {@link ArchitectureModelReadinessTest}'s business.
     */
    private enum NoArchitectureRepository implements ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource {

        INSTANCE;

        @Override
        public boolean isConfiguredFor(String environment) {
            return false;
        }

        @Override
        public java.util.Optional<String> sourceUrlOf(String environment) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<Instant> lastSuccessfulImportAt(String environment) {
            return java.util.Optional.empty();
        }

        @Override
        public ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureSnapshot read(String environment) {
            return ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureSnapshot.empty();
        }

        @Override
        public java.util.List<String> systemSlugsOf(String environment) {
            return java.util.List.of();
        }
    }

    private static class RecordingExclusiveWork implements ch.admin.bit.jeap.doc.domain.port.ExclusiveWork {

        // Synchronized: a pass takes the locks of the parts it builds at once, from its own threads.
        private final List<String> taken = java.util.Collections.synchronizedList(new ArrayList<>());
        private final List<Duration> leases = java.util.Collections.synchronizedList(new ArrayList<>());
        private volatile boolean refuse;

        void setRefuse(boolean refuse) {
            this.refuse = refuse;
        }

        @Override
        public <T> Optional<T> underLock(String name, Duration lease, java.util.function.Supplier<T> work) {
            leases.add(lease);
            if (refuse) {
                return Optional.empty();
            }
            taken.add(name);
            return Optional.ofNullable(work.get());
        }
    }

    private static String published(ListAppender<ILoggingEvent> logged) {
        return logged.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(line -> line.contains("is published:"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("nothing was published"));
    }

    private static ListAppender<ILoggingEvent> captureLog() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(DocumentationBuildRunner.class);
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        return appender;
    }
}
