package ch.admin.bit.jeap.doc.metrics;

import ch.admin.bit.jeap.doc.domain.BuildState;
import ch.admin.bit.jeap.doc.domain.BuildTrigger;
import ch.admin.bit.jeap.doc.domain.DocumentationBuild;
import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SiteProperties;
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

    private SimpleMeterRegistry registry;
    private MicrometerBuildMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        when(builds.published(anyString())).thenReturn(Optional.empty());
        when(builds.lastSuccessAt(anyString())).thenReturn(Optional.empty());
        when(requests.pendingSince(anyString())).thenReturn(Optional.empty());
        metrics = buildMetrics(registry);
    }

    private MicrometerBuildMetrics buildMetrics(MeterRegistry into) {
        MicrometerBuildMetrics bound = new MicrometerBuildMetrics(builds, requests,
                new DocumentationSites(new SiteProperties()), Clock.fixed(NOW, ZoneOffset.UTC));
        bound.bindTo(into);
        return bound;
    }

    @Test
    void succeeded_thenTheTimerSaysSoAndTheGeneratorsShareIsItsOwnStep() {
        metrics.succeeded(SITE, BuildTrigger.SCHEDULE, Duration.ofSeconds(90),
                new BuiltSite(Path.of("build"), 12, 4096, 60_000, Map.of()));

        assertThat(registry.get("jeap.doc.build").tag("result", "succeeded").tag("trigger", "schedule")
                .timer().count()).isOne();
        assertThat(registry.get("jeap.doc.build.step").tag("step", "docusaurus").timer().count()).isOne();
    }

    /**
     * The systems gauge is the drop an empty architecture repository shows up as, and it has to mean a site
     * that was published with fewer systems - not a build that read an empty model and then failed.
     */
    @Test
    void succeeded_thenTheSystemsGaugeCarriesWhatThePublishedBuildDocumented() {
        metrics.succeeded(SITE, BuildTrigger.SCHEDULE, Duration.ofSeconds(90),
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
        metrics.succeeded(SITE, BuildTrigger.SCHEDULE, Duration.ofSeconds(90),
                new BuiltSite(Path.of("build"), 12, 4096, 60_000, Map.of("dev", 7)));

        metrics.succeeded("governance", BuildTrigger.SCHEDULE, Duration.ofSeconds(90),
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

        boundMetrics.succeeded(SITE, BuildTrigger.SCHEDULE, Duration.ofMinutes(4),
                new BuiltSite(Path.of("build"), 12, 4096, 60_000, Map.of()));

        assertThat(recording.publishesHistogram("jeap.doc.build")).isFalse();
    }

    @Test
    void modelRead_thenItIsTimedAndTheSystemsGaugeIsLeftAlone() {
        metrics.succeeded(SITE, BuildTrigger.SCHEDULE, Duration.ofSeconds(90),
                new BuiltSite(Path.of("build"), 12, 4096, 60_000, Map.of("dev", 7)));

        metrics.modelRead(SITE, "dev", Duration.ofMillis(300));
        metrics.failed(SITE, BuildTrigger.SCHEDULE, Duration.ofSeconds(5));

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
    void pagesAndBytes_thenTheyComeFromThePublishedBuildRatherThanFromThisInstance() {
        when(builds.published(SITE)).thenReturn(Optional.of(published(120, 65_536)));

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

    @Test
    void requestAge_whenNothingIsPending_thenZero() {
        assertThat(registry.get("jeap.doc.build.request.age").tag("site", SITE).gauge().value()).isZero();
    }

    private static DocumentationBuild published(int pageCount, long sizeInBytes) {
        return new DocumentationBuild(7L, SITE, BuildTrigger.SCHEDULE, BuildState.SUCCEEDED, NOW, NOW,
                "doc-service-1", SITE + "/7", pageCount, sizeInBytes, 1000, null, null);
    }
}
