package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.BuiltSite;
import ch.admin.bit.jeap.doc.domain.port.ContainerMemory;
import ch.admin.bit.jeap.doc.domain.port.DocumentationStatus;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRequestRepository;
import ch.admin.bit.jeap.doc.domain.port.PublishedSite;
import ch.admin.bit.jeap.doc.domain.port.SiteBuildException;
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

import static org.assertj.core.api.Assertions.assertThat;
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

    private static final long GB = 1024L * 1024 * 1024;

    private static final Instant NOW = Instant.parse("2026-08-25T09:00:00Z");
    private static final String SITE = Site.DEFAULT_SITE;

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
    private ContainerMemory containerMemory = ContainerMemory.NONE;
    private DocumentationBuildRunner runner;

    @BeforeEach
    void setUp() {
        sites = new DocumentationSites(new SiteProperties());
        properties = new BuildProperties();
        locks = new RecordingExclusiveWork();
        readiness = new ArchitectureModelReadiness(NoArchitectureRepository.INSTANCE);
        metrics = new RecordingBuildMetrics();
        runner = new DocumentationBuildRunner(requests, builds, sites, siteBuilder, publication,
                properties, metrics, locks, readiness, containerMemory, Clock.fixed(NOW, ZoneOffset.UTC));

        when(builds.start(anyString(), any(), anyString(), any())).thenReturn(build(7L, BuildState.RUNNING));
        when(siteBuilder.generate(anyLong(), any(), any())).thenReturn(new BuiltSite(Path.of("build"), 12, 4096, 900, Map.of()));
        when(publication.publish(anyString(), any())).thenAnswer(invocation ->
                new PublishedSite(invocation.getArgument(0), 30, 4096));
    }

    /**
     * What a build did to the memory of its container, on the line that says it was published.
     * <p>
     * It is the number a container is sized from: the site generator is a child process whose bundler
     * allocates outside any heap this service can see, so nothing the JVM measures about itself says how close
     * a build came to the limit it is killed at.
     */
    @Test
    void runOnce_thenThePublishedLineSaysWhatTheContainerHeld() {
        containerMemory = peakOf(11L * GB, 16L * GB, true);
        setUp();
        pending(SITE);
        ListAppender<ILoggingEvent> logged = captureLog();

        assertThat(runner.runOnce()).isTrue();

        // Rounded, not truncated: 11 of 16 GB is 68.75%, and the page in the browser rounds it too. The two
        // are read side by side by whoever is asking how close a build came to its limit.
        assertThat(published(logged)).contains("peak 11264MB of 16384MB (69%) in the container");
    }

    /**
     * Where the kernel's high-water mark could not be reset and this build stayed below an earlier one, all
     * that is known is that it stayed below it - which is what it says, rather than claiming the earlier
     * build's peak as its own.
     */
    @Test
    void runOnce_whenThePeakIsOnlyAnUpperBound_thenTheLineSaysSo() {
        containerMemory = peakOf(11L * GB, 16L * GB, false);
        setUp();
        pending(SITE);
        ListAppender<ILoggingEvent> logged = captureLog();

        assertThat(runner.runOnce()).isTrue();

        assertThat(published(logged)).contains("peak at most 11264MB of 16384MB");
    }

    /**
     * And on the row, not only in the line: a log line is gone with the log, and comparing a build against the
     * one before it is what the number is for.
     */
    @Test
    void runOnce_thenThePeakIsRecordedOnTheBuild() {
        containerMemory = peakOf(11L * GB, 16L * GB, true);
        setUp();
        pending(SITE);

        assertThat(runner.runOnce()).isTrue();

        ArgumentCaptor<ContainerMemory.Peak> peak = ArgumentCaptor.forClass(ContainerMemory.Peak.class);
        verify(builds).succeeded(eq(7L), anyString(), anyInt(), anyLong(), anyLong(), peak.capture(), any());
        assertThat(peak.getValue()).isEqualTo(new ContainerMemory.Peak(11L * GB, 16L * GB, true));
    }

    /** A container that cannot be read leaves the columns empty rather than claiming a build used nothing. */
    @Test
    void runOnce_whenTheContainerCannotBeRead_thenNoPeakIsRecorded() {
        pending(SITE);

        assertThat(runner.runOnce()).isTrue();

        verify(builds).succeeded(eq(7L), anyString(), anyInt(), anyLong(), anyLong(), isNull(), any());
    }

    /**
     * <b>One reading, reported three times.</b> The kernel's mark only rises, and three things report one
     * build: the file written beside the site, the row, and the line that says it was published. A reading per
     * place gave three different numbers - and the upload between two of them streams the whole site, so the
     * difference was not hypothetical. This test fails on that: the container here reports a gigabyte more
     * every time it is asked.
     */
    @Test
    void runOnce_thenTheFileTheRowAndTheLineAllReportTheSamePeak() {
        containerMemory = aPeakThatRises(11L * GB, 16L * GB);
        setUp();
        pending(SITE);
        ListAppender<ILoggingEvent> logged = captureLog();

        assertThat(runner.runOnce()).isTrue();

        ArgumentCaptor<DocumentationStatus> beside = ArgumentCaptor.forClass(DocumentationStatus.class);
        verify(siteBuilder).describeRun(any(), beside.capture());
        ArgumentCaptor<ContainerMemory.Peak> onTheRow = ArgumentCaptor.forClass(ContainerMemory.Peak.class);
        verify(builds).succeeded(eq(7L), anyString(), anyInt(), anyLong(), anyLong(), onTheRow.capture(),
                any());

        assertThat(beside.getValue().memoryPeakBytes()).isEqualTo(11L * GB);
        assertThat(onTheRow.getValue().usedBytes()).isEqualTo(11L * GB);
        assertThat(published(logged)).contains("peak 11264MB of 16384MB");
    }

    /** Off Linux there is nothing to read, and the line is the line it always was. */
    @Test
    void runOnce_whenTheContainerCannotBeRead_thenThePublishedLineIsUnchanged() {
        pending(SITE);
        ListAppender<ILoggingEvent> logged = captureLog();

        assertThat(runner.runOnce()).isTrue();

        assertThat(published(logged)).endsWith("of which was the site generator.").doesNotContain("peak");
    }

    /** A build killed for want of memory exits with a number; how close it came belongs beside that number. */
    @Test
    void runOnce_whenTheBuildFails_thenTheReasonCarriesWhatTheContainerHeld() {
        containerMemory = peakOf(16L * GB, 16L * GB, true);
        setUp();
        pending(SITE);
        when(siteBuilder.generate(anyLong(), any(), any()))
                .thenThrow(new SiteBuildException("The site generator exited with 137."));

        assertThat(runner.runOnce()).isTrue();

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(builds).failed(eq(7L), reason.capture(), any(), any());
        assertThat(reason.getValue())
                .startsWith("The site generator exited with 137.")
                .contains(". peak 16384MB of 16384MB (100%) in the container");
    }

    @Test
    void runOnce_whenNothingIsPending_thenNothingHappens() {
        when(requests.pending()).thenReturn(List.of());

        assertThat(runner.runOnce()).isFalse();

        verify(siteBuilder, never()).generate(anyLong(), any(), any());
    }

    /**
     * The order the whole design rests on: the lock first, then the request, then the inputs.
     */
    @Test
    void runOnce_thenTheLockIsTakenBeforeTheRequestIsClaimedAndTheRequestBeforeAnythingIsRead() {
        pending(SITE);

        assertThat(runner.runOnce()).isTrue();

        assertThat(locks.taken).containsExactly(DocumentationBuildRunner.LOCK_PREFIX + SITE);
        InOrder order = inOrder(requests, siteBuilder, publication, builds);
        order.verify(requests).claim(SITE);
        order.verify(siteBuilder).generate(anyLong(), any(), any());
        order.verify(publication).publish(anyString(), any());
        order.verify(builds).succeeded(eq(7L), anyString(), anyInt(), anyLong(), anyLong(), any(), any());
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

        verify(requests, never()).claim(SITE);
        verify(siteBuilder, never()).generate(anyLong(), any(), any());
    }

    @Test
    void runOnce_whenTheRequestWasClaimedByAnotherInstanceMeanwhile_thenNothingIsBuilt() {
        when(requests.pending()).thenReturn(List.of(new BuildRequest(SITE, NOW, BuildTrigger.UPLOAD)));
        when(requests.claim(SITE)).thenReturn(Optional.empty());

        assertThat(runner.runOnce()).isFalse();

        verify(siteBuilder, never()).generate(anyLong(), any(), any());
    }

    /**
     * A build is a process that wants a core: three pending sites must not become three of them in one container.
     */
    @Test
    void runOnce_whenSeveralSitesArePending_thenOnlyOneIsBuiltPerTick() {
        SiteProperties configured = new SiteProperties();
        configured.setSites(new java.util.LinkedHashMap<>(java.util.Map.of(
                Site.DEFAULT_SITE, new SiteProperties.Site(),
                "governance", new SiteProperties.Site())));
        sites = new DocumentationSites(configured);
        runner = new DocumentationBuildRunner(requests, builds, sites, siteBuilder, publication,
                properties, metrics, locks, readiness, containerMemory, Clock.fixed(NOW, ZoneOffset.UTC));
        when(requests.pending()).thenReturn(List.of(
                new BuildRequest(Site.DEFAULT_SITE, NOW, BuildTrigger.UPLOAD),
                new BuildRequest("governance", NOW, BuildTrigger.SCHEDULE)));
        when(requests.claim(anyString())).thenReturn(Optional.of(BuildTrigger.UPLOAD));

        assertThat(runner.runOnce()).isTrue();

        verify(siteBuilder).generate(anyLong(), any(), any());
    }

    /**
     * An instance not knowing a site is not evidence that no instance does: during a rolling deployment that
     * <b>adds</b> a site, half the instances have it and half do not, and the ones that do not would otherwise
     * delete the requests the others are serving. A claimed request is gone, so the build would simply never
     * run and nothing would say why.
     */
    @Test
    void runOnce_whenTheSiteIsUnknownAndTheRequestIsRecent_thenItIsLeftForAnInstanceThatKnowsIt() {
        when(requests.pending()).thenReturn(List.of(new BuildRequest("gone", NOW, BuildTrigger.UPLOAD)));
        when(requests.pendingSince("gone")).thenReturn(Optional.of(NOW.minus(Duration.ofSeconds(30))));

        assertThat(runner.runOnce()).isFalse();

        verify(requests, never()).claim("gone");
        verify(siteBuilder, never()).generate(anyLong(), any(), any());
    }

    /**
     * A site that really is gone must not leave its request growing the age gauge for ever, so it is dropped
     * once no instance has served it for long enough that no deployment could still be in progress.
     */
    @Test
    void runOnce_whenTheSiteIsUnknownAndNobodyHasServedTheRequest_thenItIsDropped() {
        when(requests.pending()).thenReturn(List.of(new BuildRequest("gone", NOW, BuildTrigger.UPLOAD)));
        when(requests.pendingSince("gone")).thenReturn(Optional.of(NOW.minus(Duration.ofHours(24))));

        assertThat(runner.runOnce()).isFalse();

        verify(requests).claim("gone");
        verify(siteBuilder, never()).generate(anyLong(), any(), any());
    }

    /**
     * A site removed from the configuration while a build of it was running would otherwise leave a row that is
     * RUNNING for ever: warned about on every tick, and pinning a workspace the sweep may then never remove.
     */
    @Test
    void runOnce_whenASiteIsGoneButLeftARunningBuild_thenThatBuildIsGivenUpOnUnderItsLock() {
        when(requests.pending()).thenReturn(List.of());
        when(builds.sitesWithRunningBuilds()).thenReturn(Set.of("gone"));
        when(builds.abandonRunning(eq("gone"), any()))
                .thenReturn(List.of(build(3L, BuildState.RUNNING).abandonedAt(NOW)));
        when(requests.pendingSince("gone")).thenReturn(Optional.empty());

        assertThat(runner.runOnce()).isFalse();

        verify(builds).abandonRunning(eq("gone"), any());
        verify(builds, never()).start(anyString(), any(), anyString(), any());
        // Under the lock, because the sites are per-instance configuration: an instance that still has this one
        // is entitled to be building it, and marking a live build as abandoned would be false evidence.
        assertThat(locks.taken).containsExactly(DocumentationBuildRunner.LOCK_PREFIX + "gone");
        assertThat(metrics.abandoned).containsExactly("gone:1");
    }

    @Test
    void runOnce_whenTheSiteGeneratorFails_thenTheBuildFailsAndWhatIsPublishedStaysPublished() {
        pending(SITE);
        when(siteBuilder.generate(anyLong(), any(), any())).thenThrow(new SiteBuildException("exited with 1"));

        assertThat(runner.runOnce()).isTrue();

        verify(builds).failed(eq(7L), eq("exited with 1"), any(), any());
        assertThat(metrics.results).containsExactly("failed:" + SITE + ":UPLOAD");
        verify(builds, never()).succeeded(anyLong(), anyString(), anyInt(), anyLong(), anyLong(), any(), any());
        verify(publication, never()).publish(anyString(), any());
        verify(siteBuilder).discard(7L);
    }

    @Test
    void runOnce_whenPublishingFails_thenTheBuildFailsAndTheWorkspaceIsStillRemoved() {
        pending(SITE);
        when(publication.publish(anyString(), any())).thenThrow(new IllegalStateException("the bucket said no"));

        assertThat(runner.runOnce()).isTrue();

        verify(builds).failed(eq(7L), eq("the bucket said no"), any(), any());
        verify(siteBuilder).discard(7L);
        assertThat(metrics.results).containsExactly("failed:" + SITE + ":UPLOAD");
    }

    @Test
    void runOnce_thenABuildOfThisSiteThatLostItsLeaseIsGivenUpOnFirst() {
        pending(SITE);
        abandons(SITE, build(3L, BuildState.RUNNING, BuildTrigger.UPLOAD));

        runner.runOnce();

        InOrder order = inOrder(builds);
        order.verify(builds).abandonRunning(eq(SITE), any());
        order.verify(builds).start(eq(SITE), any(), anyString(), any());
    }

    /**
     * The recovery this whole arrangement exists for: nothing asks for the build any more - the request was
     * claimed when it started - so the row that is still running is what says one is owed.
     */
    @Test
    void runOnce_whenABuildWasLeftRunningAndNothingAsksForIt_thenTheSiteIsBuiltAsARecovery() {
        when(requests.pending()).thenReturn(List.of());
        when(requests.claim(SITE)).thenReturn(Optional.empty());
        when(builds.sitesWithRunningBuilds()).thenReturn(Set.of(SITE));
        abandons(SITE, build(3L, BuildState.RUNNING, BuildTrigger.UPLOAD));

        assertThat(runner.runOnce()).isTrue();

        verify(builds).start(eq(SITE), eq(BuildTrigger.RECOVERY), anyString(), any());
        verify(builds).succeeded(eq(7L), anyString(), anyInt(), anyLong(), anyLong(), any(), any());
    }

    /**
     * One automatic attempt is a crashed instance; two in a row is a build that kills whatever runs it, and
     * repeating it would be a crash loop rather than a recovery.
     */
    @Test
    void runOnce_whenTheLostBuildWasItselfARecovery_thenItIsNotRunAgain() {
        when(requests.pending()).thenReturn(List.of());
        when(requests.claim(SITE)).thenReturn(Optional.empty());
        when(builds.sitesWithRunningBuilds()).thenReturn(Set.of(SITE));
        abandons(SITE, build(3L, BuildState.RUNNING, BuildTrigger.RECOVERY));

        assertThat(runner.runOnce()).isFalse();

        verify(builds).abandonRunning(eq(SITE), any());
        verify(builds, never()).start(anyString(), any(), anyString(), any());
    }

    /**
     * A request beats a recovery: it is the newer fact, and it carries what actually asked.
     */
    @Test
    void runOnce_whenTheSiteIsBothRequestedAndWasLeftRunning_thenTheRequestIsTheTrigger() {
        pending(SITE);
        when(builds.sitesWithRunningBuilds()).thenReturn(Set.of(SITE));
        abandons(SITE, build(3L, BuildState.RUNNING, BuildTrigger.UPLOAD));

        assertThat(runner.runOnce()).isTrue();

        verify(builds).start(eq(SITE), eq(BuildTrigger.UPLOAD), anyString(), any());
    }

    /**
     * A site that is named twice - once by its request and once by its leftover row - is still one site, and one
     * build per tick.
     */
    @Test
    void runOnce_whenASiteIsBothRequestedAndRunning_thenItIsConsideredOnce() {
        pending(SITE);
        when(builds.sitesWithRunningBuilds()).thenReturn(Set.of(SITE));

        assertThat(runner.runOnce()).isTrue();

        verify(builds, times(1)).start(eq(SITE), any(), anyString(), any());
    }

    /**
     * Nothing pending and nothing left running is the ordinary tick, and it must not take a lock: it happens
     * every poll interval on every instance.
     */
    @Test
    void runOnce_whenNothingIsPendingAndNothingWasLeftRunning_thenNoLockIsTaken() {
        when(requests.pending()).thenReturn(List.of());
        when(builds.sitesWithRunningBuilds()).thenReturn(Set.of());

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

        verify(publication).publish(eq(SITE + "/7"), any());
    }

    /**
     * The retention runs after the new site is the published one, so a reader is never left without a site while
     * the old one is being deleted.
     */
    @Test
    void runOnce_thenSitesBeyondTheRetentionAreRemovedAfterTheNewOneIsPublished() {
        pending(SITE);
        when(builds.prefixesBeyondRetention(SITE, properties.getRetention()))
                .thenReturn(List.of(SITE + "/3", SITE + "/4"));

        runner.runOnce();

        InOrder order = inOrder(builds, publication);
        order.verify(builds).succeeded(eq(7L), anyString(), anyInt(), anyLong(), anyLong(), any(), any());
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
        when(builds.prefixesBeyondRetention(anyString(), anyInt()))
                .thenThrow(new IllegalStateException("the database went away"));

        assertThat(runner.runOnce()).isTrue();

        verify(builds).succeeded(eq(7L), anyString(), anyInt(), anyLong(), anyLong(), any(), any());
        verify(builds, never()).failed(anyLong(), anyString(), any(), any());
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
        when(builds.prefixesBeyondRetention(anyString(), anyInt()))
                .thenAnswer(invocation -> {
                    runner.stopAcceptingBuilds();
                    throw new IllegalStateException("the database went away");
                });

        assertThat(runner.runOnce()).isTrue();

        verify(builds).succeeded(eq(7L), anyString(), anyInt(), anyLong(), anyLong(), any(), any());
        verify(builds, never()).aborted(anyLong(), anyString(), any());
        verify(publication, never()).delete(SITE + "/7");
    }

    @Test
    void runOnce_whenRemovingAnObsoleteSiteFails_thenTheBuildStillCounts() {
        pending(SITE);
        when(builds.prefixesBeyondRetention(anyString(), anyInt())).thenReturn(List.of(SITE + "/3"));
        doThrow(new IllegalStateException("no")).when(publication).delete(SITE + "/3");

        assertThat(runner.runOnce()).isTrue();

        verify(builds).succeeded(eq(7L), anyString(), anyInt(), anyLong(), anyLong(), any(), any());
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

    private void pending(String site) {
        when(requests.pending()).thenReturn(List.of(new BuildRequest(site, NOW, BuildTrigger.UPLOAD)));
        when(requests.claim(site)).thenReturn(Optional.of(BuildTrigger.UPLOAD));
    }

    private void abandons(String site, DocumentationBuild... running) {
        when(builds.abandonRunning(eq(site), any()))
                .thenReturn(java.util.Arrays.stream(running).map(build -> build.abandonedAt(NOW)).toList());
    }

    private static DocumentationBuild build(long id, BuildState state) {
        return build(id, state, BuildTrigger.UPLOAD);
    }

    private static DocumentationBuild build(long id, BuildState state, BuildTrigger trigger) {
        return new DocumentationBuild(id, SITE, trigger, state, NOW, null, "test", null, 0, 0, 0,
                null, null);
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
    }

    private static class RecordingExclusiveWork implements ch.admin.bit.jeap.doc.domain.port.ExclusiveWork {

        private final List<String> taken = new ArrayList<>();
        private final List<Duration> leases = new ArrayList<>();
        private boolean refuse;

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

    /** A container that reports a gigabyte more every time it is asked, as the kernel's rising mark does. */
    private static ContainerMemory aPeakThatRises(long firstUsed, long limitBytes) {
        return () -> {
            java.util.concurrent.atomic.AtomicLong used = new java.util.concurrent.atomic.AtomicLong(firstUsed);
            return () -> Optional.of(new ContainerMemory.Peak(used.getAndAdd(GB), limitBytes, true));
        };
    }

    private static ContainerMemory peakOf(long usedBytes, long limitBytes, boolean exact) {
        return () -> () -> Optional.of(new ContainerMemory.Peak(usedBytes, limitBytes, exact));
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
