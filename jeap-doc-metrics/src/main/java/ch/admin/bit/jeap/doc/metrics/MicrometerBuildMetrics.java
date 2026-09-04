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
 * with underscores. A timer already publishes its count, so one meter per event with a
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
    private static final String ENVIRONMENT_TAG = "environment";

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
    private final Map<ModelTags, Timer> modelTimers = new ConcurrentHashMap<>();

    /**
     * How many systems the last build this instance published documented, per site and environment, which the gauge
     * below reads. Held here rather than in the database because it is a property of the last <i>generation</i>
     * rather than of anything recorded - and because a drop in it is the signal an empty architecture
     * repository gives. Written only from {@link #succeeded}: a build that read an empty model and then failed
     * must not report the same drop as one that published an empty site.
     */
    private final Map<SiteEnvironment, Integer> documentedSystems = new ConcurrentHashMap<>();
    private final Map<SiteEnvironment, Gauge> registeredSystemGauges = new ConcurrentHashMap<>();
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
        generated.documentedSystems().forEach((environment, systems) ->
                documentedSystems(site, environment, systems));
    }

    /**
     * The systems gauge of one environment of one site, moved by a build that was published.
     * <p>
     * Keyed by <b>both</b>: sites declare their own environment ids, so two sites that each have a {@code dev}
     * would otherwise share one series and the one built last would win - and this is the gauge that says an
     * architecture repository has lost its data, which no failure counter catches.
     */
    private void documentedSystems(String site, String environment, int systems) {
        if (registry == null) {
            return;
        }
        SiteEnvironment key = new SiteEnvironment(site, environment);
        documentedSystems.put(key, systems);
        registeredSystemGauges.computeIfAbsent(key, id -> Gauge.builder(
                        "jeap.doc.build.model.systems", documentedSystems, gauges -> gauges.getOrDefault(id, 0))
                .description("Systems documented in the last build of this environment this instance published")
                .tag(SITE_TAG, id.site())
                .tag(ENVIRONMENT_TAG, id.environment())
                .register(registry));
    }

    /** What a systems gauge belongs to: a site declares its own environment ids, so one alone is not a key. */
    private record SiteEnvironment(String site, String environment) {
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

    /**
     * How long the stored architecture model of an environment took to read. How much of it there was is
     * reported by {@link #succeeded}, because the gauge it moves is the one that matters: <b>an architecture
     * repository that comes back empty succeeds</b>, so no failure counter catches it and only a drop in the
     * number of systems does - and that drop has to mean a published site, not a build that failed after
     * reading.
     * <p>
     * There is no result to tell apart. A build makes no call to the architecture repository - it reads what
     * the import stored, which either answers or fails the build outright - so every recording here is a read
     * that worked.
     */
    @Override
    public void modelRead(String site, String environment, Duration duration) {
        if (registry == null) {
            return;
        }
        modelTimers.computeIfAbsent(new ModelTags(site, environment),
                        tags -> Timer.builder("jeap.doc.build.model.read")
                                .description("Reading the stored architecture model of one environment")
                                .tag(SITE_TAG, tags.site())
                                .tag(ENVIRONMENT_TAG, tags.environment())
                                .register(registry))
                .record(duration);
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
                                .register(registry))
                .record(duration);
    }

    /** What tells two build timers apart, and therefore what they are cached by. */
    private record BuildTags(String site, String result, String trigger) {
    }

    /** What tells two model-read timers apart. */
    private record ModelTags(String site, String environment) {
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
