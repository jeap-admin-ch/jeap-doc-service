package ch.admin.bit.jeap.doc.metrics;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportState;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ImportOutcome;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportRepository;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUnavailableException;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUpstream;
import ch.admin.bit.jeap.doc.domain.architecture.SystemTopology;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessage;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the architecture import reports about itself.
 * <p>
 * The meter names and tag values are asserted as <b>literals</b>: the alert rules in
 * {@code docs/observability.md} and {@code docs/architecture-import.md} are written against these strings, and
 * a test that read them off the constants would stay green while a rename broke every dashboard.
 */
class MicrometerArchitectureImportMetricsTest {

    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");

    private SimpleMeterRegistry registry;
    private InMemoryImports imports;
    private MicrometerArchitectureImportMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        imports = new InMemoryImports();
        metrics = new MicrometerArchitectureImportMetrics(imports, providerOf(new TwoEnvironments()),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /**
     * <b>The blind spot this closes.</b> Binding only what has a state row leaves an import kind that is new in
     * a deployment reporting nothing at all, and the documented alert is written with {@code absent(...)} -
     * which is false as soon as any other environment or kind reports. A pair that has never succeeded has to
     * read NaN, which is what the alert asks about.
     */
    @Test
    void bindTo_thenEveryConfiguredEnvironmentAndKindReportsBeforeAnyOfThemHasRun() {
        metrics.bindTo(registry);

        assertThat(registry.find("jeap.doc.architecture.import.last.success.age").gauges())
                .describedAs("two environments times the four kinds").hasSize(8);
        assertThat(registry.find("jeap.doc.architecture.import.last.success.age")
                .tag("environment", "prod").tag("kind", "message_schema").gauge().value())
                .describedAs("never imported reads NaN, not zero").isNaN();
    }

    /**
     * The stored count has to say "not known" until the kind has succeeded, exactly as the age does. Zero is
     * what an environment whose architecture repository lost its data reports, and that is the one thing this
     * gauge is watched for - a plateau of zeros on every fresh deployment would drown it.
     */
    @Test
    void artifacts_whenTheKindHasNeverSucceeded_thenNaNRatherThanZero() {
        metrics.bindTo(registry);

        assertThat(registry.find("jeap.doc.architecture.artifacts")
                .tag("environment", "prod").tag("kind", "message_schema").gauge().value()).isNaN();
    }

    @Test
    void artifacts_whenTheKindHasSucceeded_thenWhatIsStored() {
        imports.save(new ArchitectureImportState("dev", ArchitectureImportKind.MESSAGE_SCHEMA, null, null,
                true, 42, NOW, NOW, ImportOutcome.REPLACED, null));

        metrics.bindTo(registry);

        assertThat(registry.find("jeap.doc.architecture.artifacts")
                .tag("environment", "dev").tag("kind", "message_schema").gauge().value()).isEqualTo(42d);
    }

    /** An environment with no configured architecture repository binds nothing rather than a row of zeros. */
    @Test
    void bindTo_whenTheArchitectureRepositoryIsConfiguredForNoEnvironment_thenNothingIsBound() {
        MicrometerArchitectureImportMetrics withoutEnvironments = new MicrometerArchitectureImportMetrics(
                imports, providerOf(new NoEnvironments()), Clock.fixed(NOW, ZoneOffset.UTC));

        withoutEnvironments.bindTo(registry);

        assertThat(registry.find("jeap.doc.architecture.import.last.success.age").gauges()).isEmpty();
    }

    @Test
    void bindTo_thenAnEnvironmentWithRowsButNoConfigurationIsStillReported() {
        imports.save(new ArchitectureImportState("gone", ArchitectureImportKind.MODEL, null, null, true, 3,
                NOW, NOW, ImportOutcome.REPLACED, null));

        metrics.bindTo(registry);

        assertThat(registry.find("jeap.doc.architecture.import.last.success.age")
                .tag("environment", "gone").tag("kind", "model").gauge()).isNotNull();
    }

    @Test
    void bindTo_whenNoArchitectureRepositoryIsConfigured_thenNothingIsBoundAndNothingFails() {
        MicrometerArchitectureImportMetrics withoutUpstream =
                new MicrometerArchitectureImportMetrics(imports, providerOf(null),
                        Clock.fixed(NOW, ZoneOffset.UTC));

        withoutUpstream.bindTo(registry);

        assertThat(registry.find("jeap.doc.architecture.import.last.success.age").gauges()).isEmpty();
    }

    @Test
    void lastSuccessAge_thenTheAgeOfTheLastSuccessInSeconds() {
        imports.save(new ArchitectureImportState("dev", ArchitectureImportKind.MODEL, null, null, true, 3,
                NOW, NOW.minusSeconds(600), ImportOutcome.REPLACED, null));

        metrics.bindTo(registry);

        assertThat(registry.find("jeap.doc.architecture.import.last.success.age")
                .tag("environment", "dev").tag("kind", "model").gauge().value()).isEqualTo(600d);
    }

    /**
     * A run that stopped at its deadline is neither a success nor a failure, and the age gauge has to go on
     * rising through it - a replication that keeps truncating is exactly what this is meant to make visible.
     */
    @Test
    void lastSuccessAge_whenTheLastRunWasPartial_thenItGoesOnRising() {
        imports.save(new ArchitectureImportState("dev", ArchitectureImportKind.MESSAGE_SCHEMA, null, null,
                false, 40, NOW, NOW.minusSeconds(7200), ImportOutcome.PARTIAL, null));

        metrics.bindTo(registry);

        assertThat(registry.find("jeap.doc.architecture.import.last.success.age")
                .tag("environment", "dev").tag("kind", "message_schema").gauge().value()).isEqualTo(7200d);
    }

    @Test
    void imported_thenTheRunIsTimedAndTaggedWithWhatItDid() {
        metrics.bindTo(registry);

        metrics.imported("dev", ArchitectureImportKind.MESSAGE_SCHEMA, ImportOutcome.PARTIAL,
                Duration.ofSeconds(90), 40);

        assertThat(registry.find("jeap.doc.architecture.import").tag("environment", "dev")
                .tag("kind", "message_schema").tag("result", "partial").timer().count()).isEqualTo(1);
    }

    /**
     * A timer of a run bounded by a budget of minutes carries no percentile histogram: Micrometer's default
     * range ends at about thirty seconds, so every real run would land in the overflow bucket and answer
     * nothing - while multiplying the series by sixty-seven per tag combination.
     */
    @Test
    void imported_thenTheTimerPublishesNoHistogramBuckets() {
        RecordingHistogramConfig recording = new RecordingHistogramConfig();
        MicrometerArchitectureImportMetrics boundMetrics = new MicrometerArchitectureImportMetrics(imports,
                providerOf(new TwoEnvironments()), Clock.fixed(NOW, ZoneOffset.UTC));
        boundMetrics.bindTo(recording);

        boundMetrics.imported("dev", ArchitectureImportKind.MODEL, ImportOutcome.REPLACED,
                Duration.ofMinutes(4), 9);

        assertThat(recording.publishesHistogram("jeap.doc.architecture.import")).isFalse();
    }

    @Test
    void items_thenOneCounterPerOutcomeOfTheRun() {
        metrics.bindTo(registry);

        metrics.items("dev", ArchitectureImportKind.MESSAGE_SCHEMA, "stored", 3);
        metrics.items("dev", ArchitectureImportKind.MESSAGE_SCHEMA, "unchanged", 40);

        assertThat(registry.find("jeap.doc.architecture.import.items").tag("outcome", "stored")
                .counter().count()).isEqualTo(3d);
        assertThat(registry.find("jeap.doc.architecture.import.items").tag("outcome", "unchanged")
                .counter().count()).isEqualTo(40d);
    }

    /** The tag values of the four kinds and the four outcomes, which dashboards and alerts are written on. */
    @Test
    void tags_thenTheKindsAndOutcomesAreSpelledAsTheDocumentationSaysTheyAre() {
        metrics.bindTo(registry);
        for (ArchitectureImportKind kind : ArchitectureImportKind.values()) {
            metrics.imported("dev", kind, ImportOutcome.REPLACED, Duration.ofSeconds(1), 1);
        }
        for (ImportOutcome outcome : List.of(ImportOutcome.REPLACED, ImportOutcome.UNCHANGED,
                ImportOutcome.PARTIAL, ImportOutcome.FAILED)) {
            metrics.imported("ref", ArchitectureImportKind.MODEL, outcome, Duration.ofSeconds(1), 1);
        }

        assertThat(tagValuesOf("jeap.doc.architecture.import", "kind"))
                .containsExactlyInAnyOrder("model", "openapi_spec", "database_schema", "message_schema");
        assertThat(tagValuesOf("jeap.doc.architecture.import", "result"))
                .contains("replaced", "unchanged", "partial", "failed");
    }



    private Set<String> tagValuesOf(String meter, String tag) {
        return registry.find(meter).meters().stream()
                .map(Meter::getId)
                .map(id -> id.getTag(tag))
                .collect(java.util.stream.Collectors.toSet());
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

    /** An architecture repository on the classpath that no environment is configured for. */
    private static final class NoEnvironments extends TwoEnvironments {

        @Override
        public Set<String> environments() {
            return Set.of();
        }
    }

    /** An architecture repository configured for two environments and nothing else. */
    private static class TwoEnvironments implements ArchitectureModelUpstream {

        @Override
        public Set<String> environments() {
            return Set.of("dev", "prod");
        }

        @Override
        public Optional<String> urlOf(String environment) {
            return Optional.of("https://archrepo/" + environment);
        }

        @Override
        public List<String> systemNames(String environment) {
            throw new ArchitectureModelUnavailableException("this test reads no model");
        }

        @Override
        public Optional<SystemTopology> topology(String environment, String system) {
            throw new ArchitectureModelUnavailableException("this test reads no model");
        }

        @Override
        public Optional<List<DocumentedMessage>> messages(String environment, String system) {
            throw new ArchitectureModelUnavailableException("this test reads no model");
        }
    }

    private static final class InMemoryImports implements ArchitectureImportRepository {

        private final Map<String, ArchitectureImportState> states = new LinkedHashMap<>();

        @Override
        public ArchitectureImportState state(String environment, ArchitectureImportKind kind) {
            return states.getOrDefault(environment + "-" + kind,
                    ArchitectureImportState.none(environment, kind));
        }

        @Override
        public List<ArchitectureImportState> states() {
            return new ArrayList<>(states.values());
        }

        @Override
        public void save(ArchitectureImportState state) {
            states.put(state.environment() + "-" + state.kind(), state);
        }
    }
}
