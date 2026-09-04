package ch.admin.bit.jeap.doc.metrics;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportJob;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportState;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ImportOutcome;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportMetrics;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportRepository;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUpstream;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * What the import of the architecture repository reports about itself.
 * <p>
 * The one to alarm on is the age of the last success: an import that stops working is not visible from the
 * outside, because the site goes on being published from what was imported before it.
 */
@Component
@RequiredArgsConstructor
public class MicrometerArchitectureImportMetrics implements ArchitectureImportMetrics, MeterBinder {

    private static final String IMPORT = "jeap.doc.architecture.import";
    private static final String ITEMS = "jeap.doc.architecture.import.items";
    private static final String ARTIFACTS = "jeap.doc.architecture.artifacts";
    private static final String LAST_SUCCESS_AGE = "jeap.doc.architecture.import.last.success.age";
    private static final String ENVIRONMENT_TAG = "environment";
    private static final String KIND_TAG = "kind";
    private static final String RESULT_TAG = "result";
    private static final String OUTCOME_TAG = "outcome";

    private final ArchitectureImportRepository imports;
    private final ObjectProvider<ArchitectureModelUpstream> upstreams;
    private final Clock clock;

    private MeterRegistry registry;

    @Override
    public void bindTo(MeterRegistry registry) {
        this.registry = registry;
        // Every configured environment and every kind, before any of them has run. Binding only what has a
        // state row leaves a kind that is new in a deployment - or an environment whose first import has not
        // finished - reporting nothing at all on this instance, and an alert written with absent() is then
        // false as soon as any other environment or kind reports. A pair that has never succeeded reads NaN,
        // which is what the alert is written to catch.
        for (String environment : configuredEnvironments()) {
            for (ArchitectureImportKind kind : ArchitectureImportKind.values()) {
                bindGaugesFor(registry, environment, kind);
            }
        }
        // And whatever else already has a row, so an environment taken out of the configuration goes on being
        // reported for as long as its rows are there.
        imports.states().forEach(state -> bindGaugesFor(registry, state.environment(), state.kind()));
    }

    /**
     * The environments an architecture repository is configured for.
     * <p>
     * Read through a provider because this module does not depend on the one that supplies the port, so from
     * here it may be absent - and a binder must not be what stops an instance starting. It is the upstream
     * rather than {@code ArchitectureImportJob} because the job is built from the steps, and the steps are
     * built with this binder - naming it here would be a cycle.
     */
    private List<String> configuredEnvironments() {
        // getIfUnique, as ArchRepoConfiguration decided for the same situation: getIfAvailable throws where a
        // context holds two candidates, which would make a binder that must not stop the startup the thing
        // that stops it.
        ArchitectureModelUpstream upstream = upstreams.getIfUnique();
        return upstream == null ? List.of() : upstream.environments().stream().sorted().toList();
    }

    @Override
    public void imported(String environment, ArchitectureImportKind kind, ImportOutcome outcome,
                         Duration duration, int items) {
        if (registry == null) {
            return;
        }
        bindGaugesFor(registry, environment, kind);
        Timer.builder(IMPORT)
                .tag(ENVIRONMENT_TAG, environment)
                .tag(KIND_TAG, tagOf(kind))
                .tag(RESULT_TAG, tagOf(outcome))
                .register(registry)
                .record(duration);
    }

    @Override
    public void items(String environment, ArchitectureImportKind kind, String outcome, int count) {
        if (registry == null || count == 0) {
            return;
        }
        Counter.builder(ITEMS)
                .tag(ENVIRONMENT_TAG, environment)
                .tag(KIND_TAG, tagOf(kind))
                .tag(OUTCOME_TAG, outcome)
                .register(registry)
                .increment(count);
    }


    /**
     * The two gauges of one environment and kind, read from the state row so that they survive a restart and
     * read the same on every instance.
     */
    private void bindGaugesFor(MeterRegistry registry, String environment, ArchitectureImportKind kind) {
        Tags tags = Tags.of(ENVIRONMENT_TAG, environment, KIND_TAG, tagOf(kind));
        if (registry.find(LAST_SUCCESS_AGE).tags(tags).gauge() != null) {
            return;
        }
        Gauge.builder(LAST_SUCCESS_AGE, () -> ageOfLastSuccess(environment, kind))
                .tags(tags)
                .description("How long ago the last successful import was, NaN if there has never been one")
                .baseUnit("seconds")
                .register(registry);
        // NaN and not zero until the kind has succeeded once, for the same reason as the gauge above: every
        // configured pair is bound before any of them has run, and zero is what an environment whose
        // architecture repository lost its data reports - which is the one thing this gauge is watched for.
        Gauge.builder(ARTIFACTS, () -> itemsOf(environment, kind))
                .tags(tags)
                .description("How many things of this kind are stored, NaN if the kind has never been imported")
                .register(registry);
    }

    /**
     * How many things of a kind are stored. NaN while the kind has never been imported successfully: zero is
     * what an environment whose architecture repository lost its data reports, and the two must not read the
     * same.
     */
    private double itemsOf(String environment, ArchitectureImportKind kind) {
        ArchitectureImportState state = imports.state(environment, kind);
        return state.hasEverSucceeded() ? state.itemCount() : Double.NaN;
    }

    /**
     * An age, not a timestamp: {@code time() - <timestamp>} would subtract this service's clock from the
     * scraper's and show the difference as a false alarm. NaN while there has never been a success, which a
     * bare comparison would miss and which an alert therefore has to ask about.
     */
    private double ageOfLastSuccess(String environment, ArchitectureImportKind kind) {
        ArchitectureImportState state = imports.state(environment, kind);
        return state.lastSuccessAt() == null ? Double.NaN
                : Duration.between(state.lastSuccessAt(), clock.instant()).toMillis() / 1000d;
    }

    private static String tagOf(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
