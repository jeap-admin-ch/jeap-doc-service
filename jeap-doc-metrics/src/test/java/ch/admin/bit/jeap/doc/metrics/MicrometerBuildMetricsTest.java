package ch.admin.bit.jeap.doc.metrics;

import ch.admin.bit.jeap.doc.domain.BuildProperties;
import ch.admin.bit.jeap.doc.domain.BuildState;
import ch.admin.bit.jeap.doc.domain.BuildTrigger;
import ch.admin.bit.jeap.doc.domain.DocumentationBuild;
import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.PartKey;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SiteProperties;
import ch.admin.bit.jeap.doc.domain.SystemSitePartition;
import ch.admin.bit.jeap.doc.domain.port.CompletedPublication;
import ch.admin.bit.jeap.doc.domain.port.PublicationTotals;
import ch.admin.bit.jeap.doc.domain.port.BuiltSite;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRequestRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * What the doc service reports about its builds.
 * <p>
 * Two things are asserted here that nothing else can: that a build the instance gave up on is <b>not</b> a
 * failure - the alarm is on failures, and a deployment landing on a build must not page anybody - and that the
 * gauges are read from the database rather than from this instance's memory, so they read the same on every
 * instance and survive a restart.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MicrometerBuildMetricsTest {

    private static final Instant NOW = Instant.parse("2026-08-27T09:00:00Z");
    private static final String SITE = Site.DEFAULT_SITE;

    @Mock
    private DocumentationBuildRepository builds;
    @Mock
    private DocumentationBuildRequestRepository requests;

    private final BuildProperties buildProperties = new BuildProperties();

    private SimpleMeterRegistry registry;
    private MicrometerBuildMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        when(builds.publishedTotalsOf(anyString())).thenReturn(PublicationTotals.none());
        when(builds.lastSuccessAt(anyString())).thenReturn(Optional.empty());
        when(builds.oldestPublicationAt(anyString())).thenReturn(Optional.empty());
        when(requests.pendingSince(anyString())).thenReturn(Optional.empty());
        when(requests.pendingCount(anyString())).thenReturn(0);
        metrics = buildMetrics(registry);
    }

    private MicrometerBuildMetrics buildMetrics(MeterRegistry into) {
        DocumentationSites sites = new DocumentationSites(new SiteProperties());
        MicrometerBuildMetrics bound = new MicrometerBuildMetrics(buildProperties, builds, requests, sites,
                new SystemSitePartition(new NoArchitectureModel()), Clock.fixed(NOW, ZoneOffset.UTC));
        bound.bindTo(into);
        return bound;
    }

    /**
     * <b>The wall clock of a full publication</b>, which no instance knows on its own: its parts are built on
     * several of them. Read from the rows for that reason, and NaN until one has completed - never 0, which
     * would read as a publication that took no time at all.
     */
    @Test
    void bindTo_thenTheLastCompletedPublicationIsAGaugeReadFromTheRows() {
        when(builds.lastCompletedPublicationOf(SITE)).thenReturn(Optional.of(new CompletedPublication(
                "a-publication", NOW, NOW.plusSeconds(500), 52)));

        assertThat(registry.get("jeap.doc.publication.seconds").tag("site", SITE).gauge().value())
                .isEqualTo(500.0);
        assertThat(registry.get("jeap.doc.publication.parts").tag("site", SITE).gauge().value())
                .isEqualTo(52.0);
    }

    @Test
    void bindTo_whenNoPublicationHasCompleted_thenTheGaugesAreNotANumber() {
        when(builds.lastCompletedPublicationOf(SITE)).thenReturn(Optional.empty());

        assertThat(registry.get("jeap.doc.publication.seconds").tag("site", SITE).gauge().value()).isNaN();
        assertThat(registry.get("jeap.doc.publication.parts").tag("site", SITE).gauge().value()).isNaN();
    }

    /**
     * <b>Which part is the slow one</b> - a question the build rows could answer and no dashboard could. The
     * run this came from had one part of fifty-two take three quarters of an hour, and nothing in Prometheus
     * said which.
     */
    @Test
    void partBuilt_thenOneTimerPerPartCarriesWhatThatPartCost() {
        metrics.partBuilt(PartKey.of(SITE, "system-orders"), Duration.ofSeconds(200));
        metrics.partBuilt(PartKey.of(SITE, "system-orders"), Duration.ofSeconds(220));
        metrics.partBuilt(PartKey.of(SITE, "system-transparenza"), Duration.ofMinutes(46));

        assertThat(registry.get("jeap.doc.build.part").tag("site", SITE).tag("part", "system-orders")
                .timer().count()).isEqualTo(2);
        assertThat(registry.get("jeap.doc.build.part").tag("site", SITE).tag("part", "system-transparenza")
                .timer().totalTime(java.util.concurrent.TimeUnit.MINUTES)).isEqualTo(46.0);
        assertThat(registry.get("jeap.doc.build.part").timers())
                .describedAs("one series per part, and no more").hasSize(2);
    }

    /**
     * A build the digest skipped takes a second or two. Averaged into the per-part timer it would make a part
     * that costs three quarters of an hour look cheap - the skips are `jeap.doc.build.skipped`'s business.
     */
    @Test
    void skipped_thenNoPartTimerIsTouched() {
        metrics.skipped(SITE, BuildTrigger.IMPORT);

        assertThat(registry.find("jeap.doc.build.part").timer()).isNull();
    }

    /** Both are counted as a part is settled, so a long run says while it runs that the fleet is unwell. */
    @Test
    void contendedAndBroken_thenBothCountersFollowThem() {
        metrics.contended();
        metrics.contended();
        metrics.broken();

        assertThat(registry.get("jeap.doc.build.contended").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("jeap.doc.build.broken").counter().count()).isEqualTo(1.0);
    }

    /**
     * A healthy fleet reports a zero, and the series is there to report it.
     * <p>
     * Created on first use, either counter was absent on a healthy fleet - and a counter that appears only once
     * something has gone wrong cannot be told from one nobody is exporting: {@code rate()} over it answers
     * nothing rather than zero.
     */
    @Test
    void bindTo_whenNothingHasGoneWrong_thenBothCountersAreThereAndReadZero() {
        assertThat(registry.get("jeap.doc.build.contended").counter().count()).isZero();
        assertThat(registry.get("jeap.doc.build.broken").counter().count()).isZero();
    }

    @Test
    void slotsBusy_thenTheGaugeFollowsIt() {
        assertThat(registry.get("jeap.doc.build.slots").gauge().value())
                .describedAs("the configured slots, from the properties").isEqualTo(3.0);

        metrics.slotsBusy(2);

        assertThat(registry.get("jeap.doc.build.slots.busy").gauge().value()).isEqualTo(2.0);
    }

    @Test
    void succeeded_thenTheTimerSaysSoAndTheGeneratorsShareIsItsOwnStep() {
        metrics.succeeded(SITE, BuildTrigger.IMPORT, Duration.ofSeconds(90),
                new BuiltSite(Path.of("build"), 12, 4096, 60_000, Map.of()));

        assertThat(registry.get("jeap.doc.build").tag("result", "succeeded").tag("trigger", "import")
                .timer().count()).isOne();
        assertThat(registry.get("jeap.doc.build.step").tag("step", "docusaurus").timer().count()).isOne();
    }

    /**
     * The systems gauge is the drop an empty architecture repository shows up as, and it has to mean a site
     * that was published with fewer systems - not a build that read an empty model and then failed.
     */
    @Test
    void succeeded_thenTheSystemsGaugeCarriesWhatThePublishedBuildDocumented() {
        metrics.succeeded(SITE, BuildTrigger.IMPORT, Duration.ofSeconds(90),
                new BuiltSite(Path.of("build"), 12, 4096, 60_000, Map.of("dev", 7, "prod", 5)));

        assertThat(registry.get("jeap.doc.build.model.systems").tag("environment", "dev").gauge().value())
                .isEqualTo(7.0);
        assertThat(registry.get("jeap.doc.build.model.systems").tag("environment", "prod").gauge().value())
                .isEqualTo(5.0);
    }

    /**
     * <b>Two sites may each declare a {@code dev}</b> - an environment id is a site's own. Keyed by the
     * environment alone, the two shared one series and whichever built last won, on the gauge that says an
     * architecture repository has lost its data. Micrometer matches a tag subset, so an assertion naming only
     * the environment passes either way; these name both.
     */
    @Test
    void succeeded_whenTwoSitesEachHaveADevEnvironment_thenTheyAreTwoSeries() {
        metrics.succeeded(SITE, BuildTrigger.IMPORT, Duration.ofSeconds(90),
                new BuiltSite(Path.of("build"), 12, 4096, 60_000, Map.of("dev", 7)));

        metrics.succeeded("governance", BuildTrigger.IMPORT, Duration.ofSeconds(90),
                new BuiltSite(Path.of("build"), 3, 512, 20_000, Map.of("dev", 2)));

        assertThat(registry.get("jeap.doc.build.model.systems").tag("site", SITE).tag("environment", "dev")
                .gauge().value()).isEqualTo(7.0);
        assertThat(registry.get("jeap.doc.build.model.systems").tag("site", "governance")
                .tag("environment", "dev").gauge().value()).isEqualTo(2.0);
    }

    /**
     * A build is bounded by a budget of minutes, and Micrometer's default histogram range ends near thirty
     * seconds - so every real build would land in the overflow bucket, answering nothing while multiplying this
     * meter's series by sixty-seven per tag combination.
     */
    @Test
    void succeeded_thenTheTimerPublishesNoHistogramBuckets() {
        RecordingHistogramConfig recording = new RecordingHistogramConfig();
        MicrometerBuildMetrics boundMetrics = buildMetrics(recording);

        boundMetrics.succeeded(SITE, BuildTrigger.IMPORT, Duration.ofMinutes(4),
                new BuiltSite(Path.of("build"), 12, 4096, 60_000, Map.of()));

        assertThat(recording.publishesHistogram("jeap.doc.build")).isFalse();
    }

    @Test
    void modelRead_thenItIsTimedAndTheSystemsGaugeIsLeftAlone() {
        metrics.succeeded(SITE, BuildTrigger.IMPORT, Duration.ofSeconds(90),
                new BuiltSite(Path.of("build"), 12, 4096, 60_000, Map.of("dev", 7)));

        metrics.modelRead(SITE, "dev", Duration.ofMillis(300));
        metrics.failed(SITE, BuildTrigger.IMPORT, Duration.ofSeconds(5));

        assertThat(registry.get("jeap.doc.build.model.read").tag("environment", "dev")
                .timer().count()).isOne();
        assertThat(registry.get("jeap.doc.build.model.systems").tag("environment", "dev").gauge().value())
                .describedAs("a build that read the model and then failed does not move the gauge")
                .isEqualTo(7.0);
    }

    /**
     * The distinction the whole abort path exists for: a deployment landing on a build is not a defect, and the
     * alarm counts `result="failed"`.
     */
    @Test
    void aborted_thenItIsItsOwnResultAndNotAFailure() {
        metrics.aborted(SITE, BuildTrigger.UPLOAD, Duration.ofSeconds(2));

        assertThat(registry.get("jeap.doc.build").tag("result", "aborted").timer().count()).isOne();
        assertThat(registry.find("jeap.doc.build").tag("result", "failed").timer()).isNull();
    }

    @Test
    void failed_thenItIsCountedAsAFailure() {
        metrics.failed(SITE, BuildTrigger.UPLOAD, Duration.ofSeconds(2));

        assertThat(registry.get("jeap.doc.build").tag("result", "failed").timer().count()).isOne();
    }

    /**
     * The distinction this result exists for: a build that ran out of time is a defect like a failure, but not
     * the same one and not fixed the same way, so it is counted apart rather than folded into the failures.
     */
    @Test
    void timedOut_thenItIsItsOwnResultRatherThanAFailure() {
        metrics.timedOut(SITE, BuildTrigger.IMPORT, Duration.ofMinutes(15));

        assertThat(registry.get("jeap.doc.build").tag("result", "timed_out").tag("trigger", "import")
                .timer().count()).isOne();
        assertThat(registry.find("jeap.doc.build").tag("result", "failed").timer()).isNull();
    }

    /**
     * The budget beside the durations, so that <i>how close a build came to it</i> is a query rather than a
     * number copied into a rule - which is the copy that goes stale, silently, the day the budget is raised.
     */
    @Test
    void bindTo_thenTheBudgetOfABuildIsPublished() {
        buildProperties.setTimeout(Duration.ofMinutes(20));
        SimpleMeterRegistry fresh = new SimpleMeterRegistry();

        buildMetrics(fresh);

        assertThat(fresh.get("jeap.doc.build.timeout").gauge().value()).isEqualTo(1200.0);
    }

    @Test
    void abandoned_thenTheCounterCarriesHowMany() {
        metrics.abandoned(SITE, 2);

        assertThat(registry.get("jeap.doc.build.abandoned").counter().count()).isEqualTo(2.0);
    }

    /**
     * Read from the database on every scrape, not written by whichever instance ran the build - otherwise they
     * read 0 on every other instance and 0 again after a restart.
     */
    @Test
    void pagesAndBytes_thenTheyComeFromThePublishedPartsRatherThanFromThisInstance() {
        when(builds.publishedTotalsOf(SITE)).thenReturn(new PublicationTotals(3, 120, 65_536));

        assertThat(registry.get("jeap.doc.build.pages").tag("site", SITE).gauge().value()).isEqualTo(120.0);
        assertThat(registry.get("jeap.doc.build.bytes").tag("site", SITE).gauge().value()).isEqualTo(65_536.0);
    }

    @Test
    void pagesAndBytes_whenNothingIsPublishedYet_thenZeroRatherThanAFailure() {
        assertThat(registry.get("jeap.doc.build.pages").tag("site", SITE).gauge().value()).isZero();
        assertThat(registry.get("jeap.doc.build.bytes").tag("site", SITE).gauge().value()).isZero();
    }

    /**
     * An age rather than a timestamp, measured entirely by this service's clock: `time() - <timestamp>` would
     * subtract the scraper's clock from this one and report the difference as staleness.
     */
    @Test
    void lastSuccessAge_thenItIsAnAgeMeasuredByTheServicesOwnClock() {
        when(builds.lastSuccessAt(SITE)).thenReturn(Optional.of(NOW.minus(Duration.ofMinutes(30))));

        assertThat(registry.get("jeap.doc.build.last.success.age").tag("site", SITE).gauge().value())
                .isEqualTo(1800.0);
    }

    /**
     * The freshness signal, and it is not the last publication.
     * <p>
     * A part whose content has not moved is not generated at all, so a site nobody changes goes days without
     * a publication - correctly - and its last success ages without bound. Alarming on that pages somebody
     * about a site that is exactly right, which is what the published DocumentationSiteIsStale rule did.
     */
    @Test
    void lastCheckAge_thenASkippedBuildKeepsTheSiteReadingFresh() {
        when(builds.lastSuccessAt(SITE)).thenReturn(Optional.of(NOW.minus(Duration.ofDays(3))));
        when(builds.lastCheckAt(SITE)).thenReturn(Optional.of(NOW.minus(Duration.ofMinutes(20))));

        assertThat(registry.get("jeap.doc.build.last.check.age").tag("site", SITE).gauge().value())
                .isEqualTo(1200.0);
        assertThat(registry.get("jeap.doc.build.last.success.age").tag("site", SITE).gauge().value())
                .describedAs("and the last publication is still its own answer")
                .isEqualTo(259200.0);
    }

    /** NaN and not zero, for the same reason as the publication age: zero reads as "a moment ago". */
    @Test
    void lastCheckAge_whenNoPartWasEverChecked_thenNaN() {
        assertThat(registry.get("jeap.doc.build.last.check.age").tag("site", SITE).gauge().value()).isNaN();
    }

    @Test
    void requestAge_whenNothingIsPending_thenZero() {
        assertThat(registry.get("jeap.doc.build.request.age").tag("site", SITE).gauge().value()).isZero();
    }

    /**
     * How many builds one run set off - the question an architecture import raises: it asks for every part of
     * the site, and how many of them is what this holds.
     * <p>
     * A gauge, because the question is about a run: it holds what the last one asked for, so a graph of it
     * reads as one step per run. Tagged by what asked, so an import and an upload are told apart.
     */
    @Test
    void triggered_thenTheGaugeHoldsWhatTheLastRunOfThatTriggerAskedFor() {
        metrics.triggered(SITE, BuildTrigger.IMPORT, 3);
        metrics.triggered(SITE, BuildTrigger.UPLOAD, 1);

        assertThat(registry.get("jeap.doc.build.triggered").tag("site", SITE).tag("trigger", "import")
                .gauge().value()).isEqualTo(3.0);
        assertThat(registry.get("jeap.doc.build.triggered").tag("site", SITE).tag("trigger", "upload")
                .gauge().value()).isEqualTo(1.0);

        // The next run of the same trigger replaces it - including the run that found nothing to do, which is
        // the reading that says an import changed nothing at all.
        metrics.triggered(SITE, BuildTrigger.IMPORT, 0);
        assertThat(registry.get("jeap.doc.build.triggered").tag("site", SITE).tag("trigger", "import")
                .gauge().value()).isZero();
    }

    /** The number that says the split is doing what it is for: a build asked for and not needed. */
    @Test
    void skipped_thenItIsCountedApartFromTheBuildsThatRan() {
        metrics.skipped(SITE, BuildTrigger.IMPORT);
        metrics.succeeded(SITE, BuildTrigger.IMPORT, Duration.ofSeconds(30),
                new ch.admin.bit.jeap.doc.domain.port.BuiltSite(java.nio.file.Path.of("build"), 1, 1, 1,
                        java.util.Map.of()));

        assertThat(registry.get("jeap.doc.build.skipped").tag("site", SITE).counter().count()).isEqualTo(1.0);
        assertThat(registry.get("jeap.doc.build").tag("site", SITE).tag("result", "succeeded").timer().count())
                .isEqualTo(1);
    }

    /** An instance with no architecture repository, so that every site has one part: its shell. */
    private static final class NoArchitectureModel
            implements ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource {

        @Override
        public boolean isConfiguredFor(String environment) {
            return false;
        }

        @Override
        public Optional<String> sourceUrlOf(String environment) {
            return Optional.empty();
        }

        @Override
        public Optional<java.time.Instant> lastSuccessfulImportAt(String environment) {
            return Optional.empty();
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
}
