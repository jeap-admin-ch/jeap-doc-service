package ch.admin.bit.jeap.doc.metrics;

import ch.admin.bit.jeap.doc.domain.governance.GovernanceImportOutcome;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceImportState;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceResourceKind;
import ch.admin.bit.jeap.doc.domain.port.GovernanceImportRepository;
import ch.admin.bit.jeap.doc.domain.port.GovernanceIndexFetch;
import ch.admin.bit.jeap.doc.domain.port.GovernanceResourceFetch;
import ch.admin.bit.jeap.doc.domain.port.GovernanceUpstream;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the governance import reports about itself.
 * <p>
 * The meter names are asserted as <b>literals</b>, the same reason {@code MicrometerArchitectureImportMetricsTest}
 * does: a dashboard or an alert is written against these strings, and a test that read them off the constants
 * would stay green while a rename broke it.
 */
class MicrometerGovernanceImportMetricsTest {

    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");

    private SimpleMeterRegistry registry;
    private InMemoryImports imports;
    private MicrometerGovernanceImportMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        imports = new InMemoryImports();
        metrics = new MicrometerGovernanceImportMetrics(imports, providerOf(new Configured()),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void bindTo_thenBothKindsReportBeforeEitherHasRun() {
        metrics.bindTo(registry);

        assertThat(registry.find("jeap.doc.governance.import.last.success.age").gauges()).hasSize(2);
        assertThat(registry.find("jeap.doc.governance.import.last.success.age").tag("kind", "rule").gauge()
                .value()).describedAs("never imported reads NaN, not zero").isNaN();
    }

    @Test
    void resources_whenTheKindHasNeverSucceeded_thenNaNRatherThanZero() {
        metrics.bindTo(registry);

        assertThat(registry.find("jeap.doc.governance.resources").tag("kind", "system").gauge().value()).isNaN();
    }

    @Test
    void resources_whenTheKindHasSucceeded_thenWhatIsStored() {
        imports.save(new GovernanceImportState(GovernanceResourceKind.SYSTEM, "\"idx\"", true, 42, NOW, NOW,
                GovernanceImportOutcome.CHANGED, null));

        metrics.bindTo(registry);

        assertThat(registry.find("jeap.doc.governance.resources").tag("kind", "system").gauge().value())
                .isEqualTo(42d);
    }

    /** An instance with no Governance Service configured binds nothing rather than a pair of permanent NaNs. */
    @Test
    void bindTo_whenNoGovernanceServiceIsConfigured_thenNothingIsBoundAndNothingFails() {
        MicrometerGovernanceImportMetrics unconfigured = new MicrometerGovernanceImportMetrics(imports,
                providerOf(new Unconfigured()), Clock.fixed(NOW, ZoneOffset.UTC));

        unconfigured.bindTo(registry);

        assertThat(registry.find("jeap.doc.governance.import.last.success.age").gauges()).isEmpty();
    }

    @Test
    void bindTo_whenNoUpstreamBeanExistsAtAll_thenNothingIsBoundAndNothingFails() {
        MicrometerGovernanceImportMetrics withoutUpstream = new MicrometerGovernanceImportMetrics(imports,
                providerOf(null), Clock.fixed(NOW, ZoneOffset.UTC));

        withoutUpstream.bindTo(registry);

        assertThat(registry.find("jeap.doc.governance.import.last.success.age").gauges()).isEmpty();
    }

    @Test
    void lastSuccessAge_thenTheAgeOfTheLastSuccessInSeconds() {
        imports.save(new GovernanceImportState(GovernanceResourceKind.RULE, "\"idx\"", true, 3, NOW,
                NOW.minusSeconds(600), GovernanceImportOutcome.CHANGED, null));

        metrics.bindTo(registry);

        assertThat(registry.find("jeap.doc.governance.import.last.success.age").tag("kind", "rule").gauge()
                .value()).isEqualTo(600d);
    }

    @Test
    void imported_thenTheRunIsTimedAndTaggedWithWhatItDid() {
        metrics.bindTo(registry);

        metrics.imported(GovernanceResourceKind.SYSTEM, GovernanceImportOutcome.FAILED,
                java.time.Duration.ofSeconds(3), 0);

        assertThat(registry.find("jeap.doc.governance.import").tag("kind", "system").tag("result", "failed")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void items_thenOneCounterPerOutcomeOfTheRun() {
        metrics.bindTo(registry);

        metrics.items(GovernanceResourceKind.RULE, "stored", 3);
        metrics.items(GovernanceResourceKind.RULE, "unchanged", 40);

        assertThat(registry.find("jeap.doc.governance.import.items").tag("outcome", "stored").counter().count())
                .isEqualTo(3d);
        assertThat(registry.find("jeap.doc.governance.import.items").tag("outcome", "unchanged").counter()
                .count()).isEqualTo(40d);
    }

    private static <T> ObjectProvider<T> providerOf(T value) {
        return new ObjectProvider<>() {

            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }
        };
    }

    private static class Configured implements GovernanceUpstream {

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public Optional<GovernanceIndexFetch> systemsIndex(String knownIndexEtag) {
            throw new UnsupportedOperationException("this test binds meters and fetches nothing");
        }

        @Override
        public Optional<GovernanceIndexFetch> rulesIndex(String knownIndexEtag) {
            throw new UnsupportedOperationException("this test binds meters and fetches nothing");
        }

        @Override
        public GovernanceResourceFetch system(String systemName, String knownEtag) {
            throw new UnsupportedOperationException("this test binds meters and fetches nothing");
        }

        @Override
        public GovernanceResourceFetch rule(String ruleId, String knownEtag) {
            throw new UnsupportedOperationException("this test binds meters and fetches nothing");
        }
    }

    private static final class Unconfigured extends Configured {

        @Override
        public boolean isConfigured() {
            return false;
        }
    }

    private static final class InMemoryImports implements GovernanceImportRepository {

        private final Map<GovernanceResourceKind, GovernanceImportState> states = new LinkedHashMap<>();

        @Override
        public GovernanceImportState state(GovernanceResourceKind kind) {
            return states.getOrDefault(kind, GovernanceImportState.none(kind));
        }

        @Override
        public void save(GovernanceImportState state) {
            states.put(state.kind(), state);
        }
    }
}
