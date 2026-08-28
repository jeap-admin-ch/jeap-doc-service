package ch.admin.bit.jeap.doc.metrics;

import ch.admin.bit.jeap.doc.domain.BuildTrigger;
import ch.admin.bit.jeap.doc.domain.DocumentationBuild;
import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.port.BuildMetrics;

import ch.admin.bit.jeap.doc.domain.port.BuiltSite;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRequestRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the documentation generator reports about itself.
 * <p>
 * The names follow the platform's convention - {@code jeap.<domain>.<thing>}, dotted, which Prometheus renders
 * with underscores. A timer with a histogram already publishes its count, so one meter per event with a
 * {@code result} tag says how often, how long and how often it failed, and there is no counter beside it.
 * <p>
 * The staleness signals are <b>ages</b> rather than timestamps, and they are read from the database: an age is
 * measured entirely by this service's clock, where {@code time() - <timestamp>} would subtract this clock from
 * the scraper's and show the difference as a false alert. Reading them from the database is what makes them
 * survive a restart and read the same on every instance - it is the shape the governance service's scheduled
 * jobs already use.
 */
@Component
@RequiredArgsConstructor
public class MicrometerBuildMetrics implements BuildMetrics, MeterBinder {

    private static final String BUILD = "jeap.doc.build";
    private static final String STEP = "jeap.doc.build.step";
    private static final String SITE_TAG = "site";
    private static final String RESULT_TAG = "result";
    private static final String TRIGGER_TAG = "trigger";

    private final DocumentationBuildRepository builds;
    private final DocumentationBuildRequestRepository requests;
    private final DocumentationSites sites;
    private final Clock clock;

    /**
     * The meters in use, by the tags that tell them apart - resolved once per combination rather than rebuilt on
     * every build. A build is measured once every few minutes, so this is for the shape rather than for the cost:
     * the meters of this class are built the same way the upload meters are.
     */
    private final Map<BuildTags, Timer> buildTimers = new ConcurrentHashMap<>();
    private final Map<StepTags, Timer> stepTimers = new ConcurrentHashMap<>();
    private final Map<String, Counter> abandonedCounters = new ConcurrentHashMap<>();

    private MeterRegistry registry;

    /**
     * Registered as a binder rather than from a constructor: Spring Boot applies its meter filters before it
     * binds them, and a meter registered earlier makes the Prometheus registry warn about it on every start.
     */
    @Override
    public void bindTo(MeterRegistry meterRegistry) {
        this.registry = meterRegistry;
        for (Site site : sites.all()) {
            String id = site.id();
            Gauge.builder("jeap.doc.build.last.success.age", () -> lastSuccessAge(id))
                    .description("Seconds since this documentation site was last published, NaN while it never has been")
                    .baseUnit("seconds")
                    .tag(SITE_TAG, id)
                    .register(meterRegistry);
            Gauge.builder("jeap.doc.build.request.age", () -> ageOf(requests.pendingSince(id)))
                    .description("Seconds the oldest pending build request of this site has been waiting, 0 if none")
                    .baseUnit("seconds")
                    .tag(SITE_TAG, id)
                    .register(meterRegistry);
            // Read from the database, like the two ages above and for the same reason: an in-memory value
            // reads 0 on every instance that did not itself run the last build, and 0 again after a restart -
            // so the drop it is watched for would be reported by a deployment and by the wrong pod.
            Gauge.builder("jeap.doc.build.pages", () -> publishedValue(id, DocumentationBuild::pageCount))
                    .description("Pages the last successful build of this site produced")
                    .tag(SITE_TAG, id)
                    .register(meterRegistry);
            Gauge.builder("jeap.doc.build.bytes", () -> publishedValue(id, DocumentationBuild::sizeInBytes))
                    .description("Size of the site last published for this documentation site")
                    .baseUnit("bytes")
                    .tag(SITE_TAG, id)
                    .register(meterRegistry);
        }
    }

    @Override
    public void succeeded(String site, BuildTrigger trigger, Duration duration, BuiltSite generated) {
        recordBuild(site, trigger, "succeeded", duration);
        step(site, "docusaurus", Duration.ofMillis(generated.docusaurusMillis()));
    }

    @Override
    public void failed(String site, BuildTrigger trigger, Duration duration) {
        recordBuild(site, trigger, "failed", duration);
    }

    /**
     * A build the instance gave up on because it was stopping. Deliberately its own result rather than a
     * failure: the alarm is on {@code result="failed"}, and a deployment landing on a build is not a defect.
     */
    @Override
    public void aborted(String site, BuildTrigger trigger, Duration duration) {
        recordBuild(site, trigger, "aborted", duration);
    }

    /**
     * One is a deployment that happened to land mid-build; a stream of them is a build that is being killed, and
     * the memory the container is given is the first thing to look at.
     */
    @Override
    public void abandoned(String site, int count) {
        if (registry == null) {
            return;
        }
        abandonedCounters.computeIfAbsent(site, id -> Counter.builder("jeap.doc.build.abandoned")
                        .description("Builds given up on because the instance running them stopped")
                        .tag(SITE_TAG, id)
                        .register(registry))
                .increment(count);
    }

    private void step(String site, String step, Duration duration) {
        if (registry == null) {
            return;
        }
        stepTimers.computeIfAbsent(new StepTags(site, step), tags -> Timer.builder(STEP)
                        .description("How long one step of a documentation build took")
                        .tag(SITE_TAG, tags.site())
                        .tag("step", tags.step())
                        .register(registry))
                .record(duration);
    }

    private void recordBuild(String site, BuildTrigger trigger, String result, Duration duration) {
        if (registry == null) {
            return;
        }
        buildTimers.computeIfAbsent(new BuildTags(site, result, trigger.name().toLowerCase(java.util.Locale.ROOT)),
                        tags -> Timer.builder(BUILD)
                                .description("Documentation builds: how many, how long, and how they ended")
                                .tag(SITE_TAG, tags.site())
                                .tag(RESULT_TAG, tags.result())
                                .tag(TRIGGER_TAG, tags.trigger())
                                .publishPercentileHistogram()
                                .register(registry))
                .record(duration);
    }

    /** What tells two build timers apart, and therefore what they are cached by. */
    private record BuildTags(String site, String result, String trigger) {
    }

    /** The same, for the timers of the steps within a build. */
    private record StepTags(String site, String step) {
    }

    /** What the published build of a site says, or zero while nothing has been published. */
    private double publishedValue(String site, java.util.function.ToLongFunction<DocumentationBuild> value) {
        return builds.published(site).map(build -> (double) value.applyAsLong(build)).orElse(0.0);
    }

    /**
     * An age, or zero when there is nothing to measure - which for a pending request is the right answer, and
     * for a site that has never been published is not: see {@link #lastSuccessAge}.
     */
    private double ageOf(java.util.Optional<Instant> since) {
        return since.map(instant -> (double) Duration.between(instant, clock.instant()).toSeconds()).orElse(0.0);
    }

    /**
     * How long ago this site was last published, and {@code NaN} while it never has been.
     * <p>
     * Not zero: zero reads as <i>published a moment ago</i>, so a site whose generation has been broken since
     * the instance was deployed would look healthier than any other and the staleness alarm would never fire -
     * which is the one case it is there for.
     */
    private double lastSuccessAge(String site) {
        return builds.lastSuccessAt(site)
                .map(instant -> (double) Duration.between(instant, clock.instant()).toSeconds())
                .orElse(Double.NaN);
    }


}
