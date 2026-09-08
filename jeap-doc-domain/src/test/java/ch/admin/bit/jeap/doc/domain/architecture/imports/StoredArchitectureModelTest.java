package ch.admin.bit.jeap.doc.domain.architecture.imports;

import ch.admin.bit.jeap.doc.domain.ArchitectureImportProperties;
import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportRepository;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelRepository;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUpstream;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessage;
import ch.admin.bit.jeap.doc.domain.architecture.SystemTopology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a build reads the landscape from, and how often it reads it.
 * <p>
 * A build reads the <b>whole</b> landscape of every environment its part carries, and a site of fifty parts
 * over four environments therefore read the same four landscapes two hundred times - about a third of a full
 * publication. Holding them is only safe because of what they are keyed on, and that is what this asserts.
 */
class StoredArchitectureModelTest {

    private static final String ENVIRONMENT = "prod";
    private static final Instant IMPORTED = Instant.parse("2026-09-07T05:45:00Z");

    private CountingModels models;
    private final ImportStates imports = new ImportStates();
    private ArchitectureImportProperties properties;
    private StoredArchitectureModel model;

    @BeforeEach
    void setUp() {
        models = new CountingModels();
        properties = new ArchitectureImportProperties();
        imports.lastSuccessAt = IMPORTED;
        model = new StoredArchitectureModel(new NoUpstream(), models, imports, properties);
    }

    /** The case a pass over fifty parts is made of: the same landscape, over and over. */
    @Test
    void read_whenTheImportHasNotMovedSince_thenTheLandscapeIsReadOnce() {
        for (int build = 0; build < 50; build++) {
            assertThat(model.read(ENVIRONMENT).model().systems()).isEmpty();
        }

        assertThat(models.reads).hasValue(1);
    }

    /**
     * An import moves the last success whether or not it changed the landscape, so everything held is dropped
     * by the import that could have changed it. That is the whole of why a stale answer is impossible.
     */
    @Test
    void read_whenTheArchitectureRepositoryHasBeenReadSince_thenTheLandscapeIsReadAgain() {
        model.read(ENVIRONMENT);

        imports.lastSuccessAt = IMPORTED.plusSeconds(3600);
        model.read(ENVIRONMENT);

        assertThat(models.reads).hasValue(2);
    }

    @Test
    void read_whenHoldingTheLandscapeIsOff_thenEveryBuildReadsItAgain() {
        properties.setCacheLandscape(false);

        model.read(ENVIRONMENT);
        model.read(ENVIRONMENT);

        assertThat(models.reads).hasValue(2);
    }

    /** Two environments are two landscapes; holding one must not answer for the other. */
    @Test
    void read_whenTwoEnvironmentsAreRead_thenEachIsHeldOnItsOwn() {
        model.read(ENVIRONMENT);
        model.read("dev");
        model.read(ENVIRONMENT);
        model.read("dev");

        assertThat(models.reads).hasValue(2);
        assertThat(models.environments).containsExactly(ENVIRONMENT, "dev");
    }

    /**
     * Three parts of a site are built at once, and each of them reads the landscape as it starts. One read for
     * the three of them, because the others wait for the first rather than starting their own.
     */
    @Test
    void read_whenThreeBuildsStartTogether_thenTheLandscapeIsStillReadOnce() throws Exception {
        CountDownLatch allThree = new CountDownLatch(3);
        ExecutorService builds = Executors.newFixedThreadPool(3);
        try {
            for (int build = 0; build < 3; build++) {
                builds.submit(() -> {
                    model.read(ENVIRONMENT);
                    allThree.countDown();
                });
            }
            assertThat(allThree.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            builds.shutdownNow();
        }

        assertThat(models.reads).hasValue(1);
    }

    /** An environment nothing has ever imported is a landscape too - an empty one, and held like any other. */
    @Test
    void read_whenTheEnvironmentHasNeverBeenImported_thenTheEmptyLandscapeIsHeldToo() {
        imports.lastSuccessAt = null;

        model.read(ENVIRONMENT);
        model.read(ENVIRONMENT);

        assertThat(models.reads).hasValue(1);
    }

    /** Counts what a landscape read costs, which is the whole point of holding one. */
    private static final class CountingModels implements ArchitectureModelRepository {

        private final AtomicInteger reads = new AtomicInteger();
        private final List<String> environments = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public ArchitectureSnapshot read(String environment) {
            reads.incrementAndGet();
            environments.add(environment);
            try {
                // Long enough that three builds starting together really overlap in here.
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ArchitectureSnapshot(ArchitectureModel.empty(), IMPORTED);
        }

        @Override
        public List<String> systemSlugsOf(String environment) {
            return List.of();
        }

        @Override
        public void replace(String environment, ArchitectureModel model, Instant importedAt) {
            throw new UnsupportedOperationException("Nothing is imported here.");
        }
    }

    /** The state row the key is read from, with one instant this test moves by hand. */
    private static final class ImportStates implements ArchitectureImportRepository {

        private Instant lastSuccessAt;

        @Override
        public ArchitectureImportState state(String environment, ArchitectureImportKind kind) {
            return new ArchitectureImportState(environment, kind, null, null, true, 0, lastSuccessAt,
                    lastSuccessAt, ImportOutcome.REPLACED, null);
        }

        @Override
        public List<ArchitectureImportState> states() {
            return List.of();
        }

        @Override
        public void save(ArchitectureImportState state) {
            throw new UnsupportedOperationException("Nothing is imported here.");
        }
    }

    private static final class NoUpstream implements ArchitectureModelUpstream {

        @Override
        public Set<String> environments() {
            return Set.of();
        }

        @Override
        public Optional<String> urlOf(String environment) {
            return Optional.empty();
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
}
