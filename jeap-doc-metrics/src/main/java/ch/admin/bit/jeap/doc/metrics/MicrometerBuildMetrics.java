package ch.admin.bit.jeap.doc.metrics;

import ch.admin.bit.jeap.doc.domain.BuildProperties;
import ch.admin.bit.jeap.doc.domain.BuildTrigger;
import ch.admin.bit.jeap.doc.domain.DocumentationBuild;
import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.PartKey;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.port.BuildMetrics;
import ch.admin.bit.jeap.doc.domain.port.CompletedPublication;

import ch.admin.bit.jeap.doc.domain.port.BuiltSite;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import ch.admin.bit.jeap.doc.domain.port.PublicationTotals;
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
import java.util.Optional;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
    private static final String PART_TAG = "part";
    private static final String ENVIRONMENT_TAG = "environment";

    private final BuildProperties properties;
    private final DocumentationBuildRepository builds;
    private final DocumentationBuildRequestRepository requests;
    private final DocumentationSites sites;
    private final ch.admin.bit.jeap.doc.domain.SitePartition partition;
    private final Clock clock;

    /**
     * The meters in use, by the tags that tell them apart - resolved once per combination rather than rebuilt on
     * every build. A build is measured once every few minutes, so this is for the shape rather than for the cost:
     * the meters of this class are built the same way the upload meters are.
     */
    private final Map<BuildTags, Timer> buildTimers = new ConcurrentHashMap<>();
    private final Map<StepTags, Timer> stepTimers = new ConcurrentHashMap<>();
    private final Map<PartTags, Timer> partTimers = new ConcurrentHashMap<>();
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
    private final Map<TriggerTags, Counter> skippedCounters = new ConcurrentHashMap<>();
    private final Map<TriggerTags, Integer> partsTriggered = new ConcurrentHashMap<>();
    private final Map<TriggerTags, Gauge> registeredTriggerGauges = new ConcurrentHashMap<>();

    /**
     * The aggregates the gauges read, one read per site per scrape rather than one per gauge.
     * <p>
     * <b>Four of the gauges below are two questions.</b> Pages and bytes are one aggregate over the
     * publications of a site; the wall clock and the part count of a publication are another; and the parts a
     * site has cannot be counted without reading the architecture model, which the partition says is not for a
     * request thread. Each was being read once per gauge, so a scrape cost twice what it had to.
     * <p>
     * The window is deliberately shorter than a scrape interval - what it collapses is the several gauges of
     * <b>one</b> scrape, never two scrapes into one answer.
     */
    private static final Duration AGGREGATE_TTL = Duration.ofSeconds(10);

    /** Registered once in bindTo rather than on first use - see there for why. */
    private Counter contended;
    private Counter broken;

    // Assigned in bindTo rather than here: a field initializer runs before the constructor has the repository
    // to read through, and the gauges that use these do not exist until bindTo has registered them.
    private Memo<PublicationTotals> publishedTotals;
    private Memo<Optional<CompletedPublication>> lastPublication;

    /** How many builds this instance is running, pushed by the runner - see {@link #slotsBusy(int)}. */
    private final AtomicInteger slotsBusy = new AtomicInteger();

    private MeterRegistry registry;

    /**
     * Registered as a binder rather than from a constructor: Spring Boot applies its meter filters before it
     * binds them, and a meter registered earlier makes the Prometheus registry warn about it on every start.
     */
    @Override
    public void bindTo(MeterRegistry meterRegistry) {
        this.registry = meterRegistry;
        this.publishedTotals = new Memo<>(AGGREGATE_TTL, builds::publishedTotalsOf);
        this.lastPublication = new Memo<>(AGGREGATE_TTL, builds::lastCompletedPublicationOf);
        // Untagged, because one container has one build timeout - it is configuration rather than measurement,
        // and it is published so that a rule and a dashboard can express *how close a build came to its budget*
        // without carrying a copy of the number. A copy is what goes stale on the day the budget is raised, and
        // it goes stale silently, in the one rule that was meant to warn about this.
        Gauge.builder("jeap.doc.build.timeout", () -> (double) properties.getTimeout().toSeconds())
                .description("How long a build may take before it is given up on")
                .baseUnit("seconds")
                .register(meterRegistry);
        // Untagged, both: how many builds an instance may run and is running belong to the instance and not to
        // a site - it builds whatever is owed, of whichever site. Together, and averaged over a range, they are
        // the one question no other meter here answers: were the slots this container was sized for used?
        Gauge.builder("jeap.doc.build.slots", () -> (double) properties.getMaxConcurrentParts())
                .description("Builds this instance may run at once")
                .register(meterRegistry);
        Gauge.builder("jeap.doc.build.slots.busy", slotsBusy::get)
                .description("Builds this instance is running right now")
                .register(meterRegistry);
        // Registered here rather than on first use, both: a counter that appears only once something has gone
        // wrong cannot be told from a counter nobody is exporting.
        contended = Counter.builder("jeap.doc.build.contended")
                .description("Parts left to another instance, which held their lock")
                .register(meterRegistry);
        broken = Counter.builder("jeap.doc.build.broken")
                .description("Parts whose build threw where its own error handling should have covered it")
                .register(meterRegistry);
        for (Site site : sites.all()) {
            String id = site.id();
            Gauge.builder("jeap.doc.build.last.success.age", () -> ageOrNaN(builds.lastSuccessAt(id)))
                    .description("Seconds since this documentation site was last published, NaN while it never has been")
                    .baseUnit("seconds")
                    .tag(SITE_TAG, id)
                    .register(meterRegistry);
            // The freshness signal, and the one to alarm on. A part whose content has not moved is not
            // generated at all, so a site nobody changes goes days without a publication and its last success
            // ages without bound - correctly. What says the service is still going through this site's parts
            // is a build that ended in either of the outcomes meaning "this part is up to date".
            Gauge.builder("jeap.doc.build.last.check.age", () -> ageOrNaN(builds.lastCheckAt(id)))
                    .description("Seconds since a part of this documentation site was last published or found "
                                 + "already current, NaN while none ever was")
                    .baseUnit("seconds")
                    .tag(SITE_TAG, id)
                    .register(meterRegistry);
            Gauge.builder("jeap.doc.build.request.age", () -> ageOf(requests.pendingSince(id)))
                    .description("Seconds the oldest pending build request of this site has been waiting, 0 if none")
                    .baseUnit("seconds")
                    .tag(SITE_TAG, id)
                    .register(meterRegistry);
            // A site is published as several builds now, so how many of its parts are owed one is what says
            // whether it is keeping up - and the age of its *oldest* published part is what says a part has
            // quietly stopped being rebuilt, which the newest publication cannot.
            // The site captured rather than found again, and memoized: partsOf reads the architecture model,
            // and its own contract says that is not for a request thread - a scrape is one.
            Memo<Integer> parts = new Memo<>(AGGREGATE_TTL, ignored -> partition.partsOf(site).size());
            Gauge.builder("jeap.doc.parts", () -> parts.of(id))
                    .description("Parts this documentation site is published as")
                    .tag(SITE_TAG, id)
                    .register(meterRegistry);
            Gauge.builder("jeap.doc.parts.pending", () -> requests.pendingCount(id))
                    .description("Parts of this site that are owed a build right now")
                    .tag(SITE_TAG, id)
                    .register(meterRegistry);
            Gauge.builder("jeap.doc.part.age", () -> ageOf(builds.oldestPublicationAt(id)))
                    .description("Seconds since the oldest published part of this site was built, 0 if none")
                    .baseUnit("seconds")
                    .tag(SITE_TAG, id)
                    .register(meterRegistry);
            // Read from the database, like the two ages above and for the same reason: an in-memory value
            // reads 0 on every instance that did not itself run the last build, and 0 again after a restart -
            // so the drop it is watched for would be reported by a deployment and by the wrong pod.
            Gauge.builder("jeap.doc.build.pages", () -> publishedValue(id,
                            ch.admin.bit.jeap.doc.domain.port.PublicationTotals::pages))
                    .description("Pages this documentation site is published with, across its parts")
                    .tag(SITE_TAG, id)
                    .register(meterRegistry);
            Gauge.builder("jeap.doc.build.bytes", () -> publishedValue(id,
                            ch.admin.bit.jeap.doc.domain.port.PublicationTotals::bytes))
                    .description("Size of this documentation site as published, across its parts")
                    .baseUnit("bytes")
                    .tag(SITE_TAG, id)
                    .register(meterRegistry);
            // The wall clock of a whole publication, which no instance knows on its own: its parts are built
            // on several of them. Read from the database for that reason, and NaN until one has completed -
            // never 0, which would read as a publication that took no time.
            Gauge.builder("jeap.doc.publication.seconds",
                            () -> publicationValue(id, publication -> publication.duration().toMillis() / 1000.0))
                    .description("Wall clock of the last completed full publication of this site, NaN while none has completed")
                    .baseUnit("seconds")
                    .tag(SITE_TAG, id)
                    .register(meterRegistry);
            Gauge.builder("jeap.doc.publication.parts", () -> publicationValue(id, CompletedPublication::parts))
                    .description("Parts the last completed full publication of this site went through")
                    .tag(SITE_TAG, id)
                    .register(meterRegistry);
        }
    }

    /**
     * What the last completed publication of a site says, or NaN where none has completed.
     * <p>
     * One query per scrape, like the pages and bytes gauges beside it. A publication is what an operator waits
     * for, and reading it from the rows is what makes it the same number on every instance and after a restart.
     */
    private double publicationValue(String site,
                                    java.util.function.ToDoubleFunction<CompletedPublication> of) {
        return lastPublication.of(site)
                .map(publication -> of.applyAsDouble(publication))
                .orElse(Double.NaN);
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
     * A build that ran past its budget. Its own result rather than a failure, although it is a defect just as
     * much: it is not put right the way a broken build is, and its duration is the timeout every time, so
     * counted among the failures it would drag their mean towards the budget - see {@link BuildMetrics#timedOut}.
     */
    @Override
    public void timedOut(String site, BuildTrigger trigger, Duration duration) {
        recordBuild(site, trigger, "timed_out", duration);
    }

    @Override
    public void contended() {
        if (registry != null) {
            contended.increment();
        }
    }

    @Override
    public void broken() {
        if (registry != null) {
            broken.increment();
        }
    }

    /**
     * A gauge pushed by the runner rather than read from it: the runner already depends on this port, so the
     * adapter cannot depend back on the runner to ask.
     */
    @Override
    public void slotsBusy(int busy) {
        slotsBusy.set(busy);
    }

    /**
     * What building one part cost, per part - <b>a meter of its own rather than a tag on the build timer</b>.
     * <p>
     * The tag would have multiplied that timer by the parts of the site, on top of its result and trigger, and
     * a part is named after a system in the imported architecture model: the number of label values is the
     * upstream's to decide, which is the shape of every cardinality accident. One series per part is the whole
     * cost here, and it is what says <i>which</i> part is the slow one - a question the build rows could answer
     * and no dashboard could.
     * <p>
     * A ceiling, if one is ever wanted, belongs in a {@code MeterFilter} with {@code maximumAllowableTags} on
     * {@code part} rather than in a smaller tag set.
     */
    @Override
    public void partBuilt(PartKey part, Duration duration) {
        if (registry == null) {
            return;
        }
        partTimers.computeIfAbsent(new PartTags(part.site(), part.part()),
                        tags -> Timer.builder("jeap.doc.build.part")
                                .description("Builds of this part in which the site generator really ran")
                                .tag(SITE_TAG, tags.site())
                                .tag(PART_TAG, tags.part())
                                .register(registry))
                .record(duration);
    }

    /** What a per-part timer belongs to. A part identifier is unique within its site and not beyond it. */
    private record PartTags(String site, String part) {
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
     * A build that was asked for and had nothing to publish, because the content of its part hashed to what is
     * already being served.
     * <p>
     * <b>The number that says whether publishing a site in parts is doing what it is for.</b> A landscape
     * where nothing changed should count skips and no builds; builds without skips mean either that everything
     * really is changing or that the digest of the content is not stable. Counted rather than timed: what it
     * cost is the content of one part, and the timer that answers <i>what did a build cost</i> must not be
     * diluted by runs that built nothing.
     * <p>
     * Tagged with the trigger, because that is the question a skip raises: skips from the hourly import are
     * the split working as intended, while skips from uploads are an upload that changed nothing.
     */
    @Override
    public void skipped(String site, BuildTrigger trigger) {
        if (registry == null) {
            return;
        }
        skippedCounters.computeIfAbsent(new TriggerTags(site, trigger.name().toLowerCase(Locale.ROOT)),
                        tags -> Counter.builder("jeap.doc.build.skipped")
                                .description("Builds not run because the content of the part was already "
                                             + "published")
                                .tag(SITE_TAG, tags.site())
                                .tag(TRIGGER_TAG, tags.trigger())
                                .register(registry))
                .increment();
    }

    /**
     * How many parts one run of a trigger asked to be built - <b>a gauge, because the question is about a
     * run</b>: after an architecture import of an environment, how many builds did it set off?
     * <p>
     * It holds what the last run of that trigger asked for, so a graph of it reads as one step per run: zero
     * on the hours where a landscape did not move, three on the hour where three systems did. What it does not
     * answer is the total over a window - that is the count of {@code jeap.doc.build} itself, which counts the
     * builds that actually ran.
     */
    @Override
    public void triggered(String site, BuildTrigger trigger, int parts) {
        if (registry == null) {
            return;
        }
        TriggerTags tags = new TriggerTags(site, trigger.name().toLowerCase(Locale.ROOT));
        partsTriggered.put(tags, parts);
        registeredTriggerGauges.computeIfAbsent(tags, id -> Gauge.builder("jeap.doc.build.triggered",
                        partsTriggered, gauges -> gauges.getOrDefault(id, 0))
                .description("Parts the last run of this trigger asked to be built")
                .tag(SITE_TAG, id.site())
                .tag(TRIGGER_TAG, id.trigger())
                .register(registry));
    }

    /** What a triggered gauge belongs to: one site, and what asked. */
    private record TriggerTags(String site, String trigger) {
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

    /**
     * What the site adds up to as it is published, or zero while nothing has been. Across its parts: a site is
     * published as several builds, so the pages of one of them are not the pages of the documentation.
     */
    private double publishedValue(String site,
                                  java.util.function.ToLongFunction<PublicationTotals> value) {
        return value.applyAsLong(publishedTotals.of(site));
    }

    /**
     * One answer per site, held for a short window.
     * <p>
     * <b>Not a cache</b>, and it must not become one: the window is shorter than a scrape interval, so what it
     * spares is the second and third gauge of one scrape asking the same aggregate. A longer window would make
     * a scrape report the numbers of the one before it, which is exactly what a gauge may not do.
     */
    private static final class Memo<T> {

        private final long ttlNanos;
        private final java.util.function.Function<String, T> read;
        private final Map<String, Held<T>> held = new ConcurrentHashMap<>();

        private Memo(Duration ttl, java.util.function.Function<String, T> read) {
            this.ttlNanos = ttl.toNanos();
            this.read = read;
        }

        private T of(String site) {
            long now = System.nanoTime();
            Held<T> current = held.get(site);
            if (current != null && now - current.readAtNanos() < ttlNanos) {
                return current.value();
            }
            // Outside any lock: two scrapes racing here read twice, which is what was happening anyway. What
            // must not happen is a read while something holds a lock every gauge of every site waits on.
            T value = read.apply(site);
            held.put(site, new Held<>(value, now));
            return value;
        }

        private record Held<T>(T value, long readAtNanos) {
        }
    }

    /**
     * An age, or zero when there is nothing to measure - which for a pending request is the right answer, and
     * for a site that has never been published is not: see {@link #ageOrNaN}.
     */
    private double ageOf(java.util.Optional<Instant> since) {
        return since.map(instant -> (double) Duration.between(instant, clock.instant()).toSeconds()).orElse(0.0);
    }

    /**
     * An age, and {@code NaN} where there is nothing to measure yet.
     * <p>
     * Not zero: zero reads as <i>a moment ago</i>, so a site whose generation has been broken since the
     * instance was deployed would look healthier than any other and the staleness alarm would never fire -
     * which is the one case it is there for.
     */
    private double ageOrNaN(java.util.Optional<Instant> since) {
        return since.map(instant -> (double) Duration.between(instant, clock.instant()).toSeconds())
                .orElse(Double.NaN);
    }


}
