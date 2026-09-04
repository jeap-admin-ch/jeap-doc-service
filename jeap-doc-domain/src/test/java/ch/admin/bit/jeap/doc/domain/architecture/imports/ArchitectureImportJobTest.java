package ch.admin.bit.jeap.doc.domain.architecture.imports;

import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessage;
import ch.admin.bit.jeap.doc.domain.architecture.SystemTopology;
import ch.admin.bit.jeap.doc.domain.ArchitectureImportProperties;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportRepository;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUpstream;
import ch.admin.bit.jeap.doc.domain.port.ExclusiveWork;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * What the job guarantees about its steps, which nothing else asserts.
 * <p>
 * The order they run in, the lock each one takes, and that one of them throwing does not cost the others their
 * turn - all three came out of a review, and all three are the kind of thing an unrelated refactoring undoes
 * without anybody noticing.
 */
class ArchitectureImportJobTest {

    private static final String ENVIRONMENT = "dev";

    private RecordingStep model;
    private RecordingStep openApi;
    private RecordingStep databaseSchema;
    private RecordingLocks locks;
    private InMemoryImports imports;
    private ArchitectureImportProperties properties;
    private ArchitectureImportShutdown shutdown;

    /**
     * The log of the job, because two of its guarantees are levels and nothing else can see them.
     */
    private ListAppender<ILoggingEvent> logged;
    private Level levelBeforeTheTest;

    @BeforeEach
    void setUp() {
        model = new RecordingStep(ArchitectureImportKind.MODEL);
        openApi = new RecordingStep(ArchitectureImportKind.OPENAPI_SPEC);
        databaseSchema = new RecordingStep(ArchitectureImportKind.DATABASE_SCHEMA);
        locks = new RecordingLocks(true);
        imports = new InMemoryImports();
        properties = new ArchitectureImportProperties();
        shutdown = new ArchitectureImportShutdown();
        shutdown.start();
    }

    @BeforeEach
    void captureTheLog() {
        Logger logger = (Logger) LoggerFactory.getLogger(ArchitectureImportJob.class);
        logged = new ListAppender<>();
        logged.start();
        levelBeforeTheTest = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(logged);
    }

    /**
     * A logger is global: a capture left in place accumulates appenders over the class, and the level stays
     * turned down for every test that runs after these in the same JVM.
     */
    @AfterEach
    void restoreTheLogger() {
        Logger logger = (Logger) LoggerFactory.getLogger(ArchitectureImportJob.class);
        logger.detachAppender(logged);
        logger.setLevel(levelBeforeTheTest);
        logged.stop();
    }

    /**
     * The model decides which systems and components exist, and therefore which artifacts are orphans. It has
     * to run first however the context happened to register the beans - which is why the job sorts rather than
     * trusting the injected order.
     */
    @Test
    void importEnvironment_whateverOrderTheStepsWereRegisteredIn_thenTheModelRunsFirst() {
        jobOf(databaseSchema, openApi, model).importEnvironment(ENVIRONMENT);

        assertThat(ranInOrder()).startsWith(ArchitectureImportKind.MODEL);
    }

    @Test
    void importEnvironment_thenEveryKindRuns() {
        jobOf(model, openApi, databaseSchema).importEnvironment(ENVIRONMENT);

        assertThat(ranInOrder()).containsExactlyInAnyOrder(ArchitectureImportKind.MODEL,
                ArchitectureImportKind.OPENAPI_SPEC, ArchitectureImportKind.DATABASE_SCHEMA);
    }

    /** One lock per environment and kind, so two environments never wait for each other. */
    @Test
    void importEnvironment_thenEachStepTakesItsOwnLock() {
        jobOf(model, openApi, databaseSchema).importEnvironment(ENVIRONMENT);

        assertThat(locks.taken).containsExactlyInAnyOrder(
                "architectureImport-dev-MODEL",
                "architectureImport-dev-OPENAPI_SPEC",
                "architectureImport-dev-DATABASE_SCHEMA");
    }

    /**
     * A step is meant to report what went wrong rather than throw, so one that does is a defect in it - but it
     * must still not cost the steps after it their turn, and it must never reach a build.
     */
    @Test
    void importEnvironment_whenAStepThrows_thenTheOnesAfterItStillRun() {
        model.throwing = true;

        jobOf(model, openApi, databaseSchema).importEnvironment(ENVIRONMENT);

        assertThat(ranInOrder()).contains(ArchitectureImportKind.OPENAPI_SPEC,
                ArchitectureImportKind.DATABASE_SCHEMA);
    }

    /** An instance that does not get the lock does nothing: another one is importing into the same database. */
    @Test
    void importEnvironment_whenAnotherInstanceHoldsTheLock_thenNothingRuns() {
        locks = new RecordingLocks(false);

        jobOf(model, openApi, databaseSchema).importEnvironment(ENVIRONMENT);

        assertThat(ranInOrder()).isEmpty();
    }

    @Test
    void importWhatIsMissing_whenAKindHasNeverSucceeded_thenItIsImported() {
        jobOf(model, openApi, databaseSchema).importWhatIsMissing();

        assertThat(ranInOrder()).hasSize(3);
    }

    /**
     * The catch-up at startup is for what has never been imported. A kind that has succeeded before is left to
     * the schedule, or every rolling deployment would refetch every landscape.
     */
    @Test
    void importWhatIsMissing_whenAKindHasSucceededBefore_thenItIsLeftAlone() {
        imports.save(new ArchitectureImportState(ENVIRONMENT, ArchitectureImportKind.MODEL, null, null, true,
                1, Instant.now(), Instant.now(), ImportOutcome.REPLACED, null));

        jobOf(model, openApi, databaseSchema).importWhatIsMissing();

        assertThat(ranInOrder()).doesNotContain(ArchitectureImportKind.MODEL);
    }

    /**
     * Every deployment falls into an import. A stopping instance starts no step it cannot finish - it would
     * only be interrupted in it, and the interrupt is what used to be reported as a failure.
     */
    @Test
    void importEnvironment_whenTheInstanceIsStopping_thenNoStepIsStarted() {
        shutdown.stop();

        jobOf(model, openApi, databaseSchema).importEnvironment(ENVIRONMENT);

        assertThat(ranInOrder()).isEmpty();
    }

    /** The same for the catch-up at startup, which an instance stopped again mid-rollout runs into. */
    @Test
    void importWhatIsMissing_whenTheInstanceIsStopping_thenNoStepIsStarted() {
        shutdown.stop();

        jobOf(model, openApi, databaseSchema).importWhatIsMissing();

        assertThat(ranInOrder()).isEmpty();
    }

    /** A step already running is stopped between two requests, so the ones after it do not start either. */
    @Test
    void importEnvironment_whenTheInstanceStopsDuringAStep_thenTheStepsAfterItAreNotStarted() {
        model.stopping = shutdown;

        jobOf(model, openApi, databaseSchema).importEnvironment(ENVIRONMENT);

        assertThat(ranInOrder()).containsExactly(ArchitectureImportKind.MODEL);
    }

    /**
     * A step interrupted before it could stop by itself throws out of everything it touches at once. It is the
     * ordinary end of an import on a deployment, and nobody has to act on it - so it is reported at INFO and
     * not as the defect an unexpected failure is. <b>The level is the assertion</b>: an operator alarming on
     * ERROR would otherwise see one per deployment, for ever, with nothing to act on.
     */
    @Test
    void importEnvironment_whenAStepThrowsWhileTheInstanceIsStopping_thenItIsNotReportedAsAFailure() {
        model.throwing = true;
        model.stopping = shutdown;

        jobOf(model, openApi, databaseSchema).importEnvironment(ENVIRONMENT);

        assertThat(ranInOrder()).containsExactly(ArchitectureImportKind.MODEL);
        assertThat(logged.list)
                .extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
                .contains(tuple(Level.INFO, "The import of the MODEL of the environment dev was cut short: "
                                            + "this instance is stopping. What is stored goes on being "
                                            + "generated from, and the next schedule imports the rest."))
                .noneMatch(logLine -> logLine.toList().getFirst() == Level.ERROR);
    }

    /** And a step that throws while the instance is running is exactly what an operator has to hear about. */
    @Test
    void importEnvironment_whenAStepThrowsWhileTheInstanceIsRunning_thenItIsReportedAsAFailure() {
        model.throwing = true;

        jobOf(model, openApi, databaseSchema).importEnvironment(ENVIRONMENT);

        assertThat(logged.list)
                .filteredOn(event -> event.getLevel() == Level.ERROR)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("The import of the MODEL of the environment dev failed unexpectedly. The "
                                 + "other kinds are imported all the same, and what is stored goes on being "
                                 + "generated from.");
    }

    /** The deadline a step is given ends with the instance, whatever budget is left on it. */
    @Test
    void importEnvironment_thenTheDeadlineOfAStepEndsWithTheInstance() {
        jobOf(model).importEnvironment(ENVIRONMENT);
        assertThat(model.deadline.hasExpired()).isFalse();

        shutdown.stop();

        assertThat(model.deadline.hasExpired()).isTrue();
        assertThat(model.deadline.isBecauseOfShutdown()).isTrue();
        assertThat(model.deadline.reason()).isEqualTo("this instance is stopping");
    }

    private ArchitectureImportJob jobOf(ArchitectureImportStep... steps) {
        return new ArchitectureImportJob(List.of(steps), new OneEnvironment(), imports, properties, locks,
                shutdown);
    }

    private List<ArchitectureImportKind> ranInOrder() {
        List<ArchitectureImportKind> ran = new ArrayList<>();
        for (RecordingStep step : List.of(model, openApi, databaseSchema)) {
            if (step.ranAt >= 0) {
                ran.add(step.kind());
            }
        }
        ran.sort((one, other) -> Integer.compare(stepOf(one).ranAt, stepOf(other).ranAt));
        return ran;
    }

    private RecordingStep stepOf(ArchitectureImportKind kind) {
        return switch (kind) {
            case MODEL -> model;
            case OPENAPI_SPEC -> openApi;
            case DATABASE_SCHEMA -> databaseSchema;
            // This test registers three steps and is about the order they run in, not about every kind there
            // is. A kind added later joins it by being registered here, not by being defaulted over.
            case MESSAGE_SCHEMA -> throw new IllegalArgumentException("This test registers no " + kind + ".");
        };
    }

    private static int runs;

    private static final class RecordingStep implements ArchitectureImportStep {

        private final ArchitectureImportKind kind;
        private int ranAt = -1;
        private boolean throwing;
        /** Set to have the step stop the instance while it runs, as a deployment does. */
        private ArchitectureImportShutdown stopping;
        private Deadline deadline;

        private RecordingStep(ArchitectureImportKind kind) {
            this.kind = kind;
        }

        @Override
        public ArchitectureImportKind kind() {
            return kind;
        }

        @Override
        public ImportOutcome run(String environment, Deadline deadline) {
            ranAt = runs++;
            this.deadline = deadline;
            if (stopping != null) {
                stopping.stop();
            }
            if (throwing) {
                throw new IllegalStateException("A step that does not keep its contract.");
            }
            return ImportOutcome.REPLACED;
        }
    }

    private static final class RecordingLocks implements ExclusiveWork {

        private final boolean granting;
        private final List<String> taken = new ArrayList<>();

        private RecordingLocks(boolean granting) {
            this.granting = granting;
        }

        @Override
        public <T> Optional<T> underLock(String name, Duration lease, Supplier<T> work) {
            taken.add(name);
            return granting ? Optional.ofNullable(work.get()) : Optional.empty();
        }
    }

    private static final class OneEnvironment implements ArchitectureModelUpstream {

        @Override
        public Set<String> environments() {
            return Set.of(ENVIRONMENT);
        }

        @Override
        public Optional<String> urlOf(String environment) {
            return Optional.of("https://archrepo.example.org");
        }

        @Override
        public List<String> systemNames(String environment) {
            return List.of();
        }

        @Override
        public Optional<SystemTopology> topology(String environment, String system) {
            return Optional.empty();
        }

        @Override
        public Optional<List<DocumentedMessage>> messages(String environment, String system) {
            return Optional.empty();
        }
    }

    private static final class InMemoryImports implements ArchitectureImportRepository {

        private final Map<String, ArchitectureImportState> states = new LinkedHashMap<>();

        @Override
        public ArchitectureImportState state(String environment, ArchitectureImportKind kind) {
            return states.getOrDefault(environment + kind, ArchitectureImportState.none(environment, kind));
        }

        @Override
        public List<ArchitectureImportState> states() {
            return List.copyOf(states.values());
        }

        @Override
        public void save(ArchitectureImportState state) {
            states.put(state.environment() + state.kind(), state);
        }
    }
}
