package ch.admin.bit.jeap.doc.metrics;

import ch.admin.bit.jeap.doc.domain.governance.GovernanceImportOutcome;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceImportState;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceResourceKind;
import ch.admin.bit.jeap.doc.domain.port.GovernanceImportMetrics;
import ch.admin.bit.jeap.doc.domain.port.GovernanceImportRepository;
import ch.admin.bit.jeap.doc.domain.port.GovernanceUpstream;
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
import java.util.Locale;

/**
 * What the governance import reports about itself.
 * <p>
 * The one to alarm on is the age of the last success, the way {@code MicrometerArchitectureImportMetrics}
 * binds it for the architecture import - the same metrics and status mechanism as the other imports - an
 * import that stops working is not visible from the outside, because the site goes on being published from
 * what was imported before it.
 */
@Component
@RequiredArgsConstructor
public class MicrometerGovernanceImportMetrics implements GovernanceImportMetrics, MeterBinder {

    private static final String IMPORT = "jeap.doc.governance.import";
    private static final String ITEMS = "jeap.doc.governance.import.items";
    private static final String RESOURCES = "jeap.doc.governance.resources";
    private static final String LAST_SUCCESS_AGE = "jeap.doc.governance.import.last.success.age";
    private static final String KIND_TAG = "kind";
    private static final String RESULT_TAG = "result";
    private static final String OUTCOME_TAG = "outcome";

    private final GovernanceImportRepository imports;
    /**
     * Read through a provider because this module does not depend on the one that supplies the port - see
     * {@code MicrometerArchitectureImportMetrics} for the same reasoning - so from here it may be absent, and
     * a binder must not be what stops an instance starting.
     */
    private final ObjectProvider<GovernanceUpstream> upstream;
    private final Clock clock;

    private MeterRegistry registry;

    @Override
    public void bindTo(MeterRegistry registry) {
        this.registry = registry;
        // Both kinds, before either has run - an instance with no Governance Service configured gets no
        // gauge at all, rather than one that reads NaN for ever.
        GovernanceUpstream configured = upstream.getIfUnique();
        if (configured != null && configured.isConfigured()) {
            for (GovernanceResourceKind kind : GovernanceResourceKind.values()) {
                if (kind == GovernanceResourceKind.SYSTEM || kind == GovernanceResourceKind.RULE) {
                    bindGaugesFor(registry, kind);
                }
            }
        }
    }

    @Override
    public void imported(GovernanceResourceKind kind, GovernanceImportOutcome outcome, Duration duration,
                         int items) {
        if (registry == null) {
            return;
        }
        bindGaugesFor(registry, kind);
        Timer.builder(IMPORT)
                .tag(KIND_TAG, tagOf(kind))
                .tag(RESULT_TAG, tagOf(outcome))
                .register(registry)
                .record(duration);
    }

    @Override
    public void items(GovernanceResourceKind kind, String outcome, int count) {
        if (registry == null || count == 0) {
            return;
        }
        Counter.builder(ITEMS)
                .tag(KIND_TAG, tagOf(kind))
                .tag(OUTCOME_TAG, outcome)
                .register(registry)
                .increment(count);
    }

    private void bindGaugesFor(MeterRegistry registry, GovernanceResourceKind kind) {
        Tags tags = Tags.of(KIND_TAG, tagOf(kind));
        if (registry.find(LAST_SUCCESS_AGE).tags(tags).gauge() != null) {
            return;
        }
        Gauge.builder(LAST_SUCCESS_AGE, () -> ageOfLastSuccess(kind))
                .tags(tags)
                .description("How long ago the last successful governance import of this kind was, NaN if "
                             + "there has never been one")
                .baseUnit("seconds")
                .register(registry);
        // NaN and not zero until the kind has succeeded once, for the same reason the architecture import's
        // does: zero is what a Governance Service that lost its data reports, and the two must not read the
        // same.
        Gauge.builder(RESOURCES, () -> itemsOf(kind))
                .tags(tags)
                .description("How many systems or rules are stored, NaN if this kind has never been imported")
                .register(registry);
    }

    private double itemsOf(GovernanceResourceKind kind) {
        GovernanceImportState state = imports.state(kind);
        return state.hasEverSucceeded() ? state.itemCount() : Double.NaN;
    }

    private double ageOfLastSuccess(GovernanceResourceKind kind) {
        GovernanceImportState state = imports.state(kind);
        return state.lastSuccessAt() == null ? Double.NaN
                : Duration.between(state.lastSuccessAt(), clock.instant()).toMillis() / 1000d;
    }

    private static String tagOf(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
