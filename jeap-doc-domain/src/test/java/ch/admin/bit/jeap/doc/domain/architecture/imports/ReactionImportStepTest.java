package ch.admin.bit.jeap.doc.domain.architecture.imports;

import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.ComponentType;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessage;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.architecture.MessageKind;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportMetrics;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportRepository;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUnavailableException;
import ch.admin.bit.jeap.doc.domain.port.Fetched;
import ch.admin.bit.jeap.doc.domain.port.GraphFetch;
import ch.admin.bit.jeap.doc.domain.port.ReactionGraphRepository;
import ch.admin.bit.jeap.doc.domain.port.ReactionGraphUpstream;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind.COMPONENT_REACTIONS;
import static ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind.MESSAGE_REACTIONS;
import static ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind.SYSTEM_REACTIONS;
import static ch.admin.bit.jeap.doc.domain.architecture.imports.ReactionGraphRef.NO_VARIANT;
import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

/**
 * The replication of the reaction graphs: only what moved is fetched, only what the model can place is stored,
 * and a run that stops early keeps what it got.
 */
class ReactionImportStepTest {

    private static final String ENVIRONMENT = "dev";
    private static final Instant NOW = Instant.parse("2026-09-09T08:00:00Z");

    private FakeObserver observer;
    private InMemoryGraphs graphs;
    private InMemoryImports imports;
    private StoredModel models;
    private SteppingClock clock;
    private ReactionImportStep step;

    /** The log of the step, because what one warning says about the index is not visible anywhere else. */
    private ListAppender<ILoggingEvent> logged;
    private Level levelBeforeTheTest;

    @BeforeEach
    void setUp() {
        observer = new FakeObserver();
        graphs = new InMemoryGraphs();
        imports = new InMemoryImports();
        models = new StoredModel();
        clock = new SteppingClock();
        step = stepFor(SYSTEM_REACTIONS);
    }

    @BeforeEach
    void captureTheLog() {
        Logger logger = (Logger) LoggerFactory.getLogger(ReactionImportStep.class);
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
        Logger logger = (Logger) LoggerFactory.getLogger(ReactionImportStep.class);
        logger.detachAppender(logged);
        logger.setLevel(levelBeforeTheTest);
        logged.stop();
    }

    private ReactionImportStep stepFor(ArchitectureImportKind kind) {
        return new ReactionImportStep(kind, observer, graphs, models, imports, ArchitectureImportMetrics.NONE,
                clock);
    }

    /**
     * An environment with no reaction observer, and an instance with the reactions switched off, look the same
     * from here - and neither is a failure to record. A row would put a kind that can never succeed in front
     * of an operator and on the staleness gauge.
     */
    @Test
    void run_whenNoObserverIsConfigured_thenNothingIsCalledAndNothingIsRecorded() {
        observer.configured = false;

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.NOT_CONFIGURED);

        assertThat(observer.indexCalls).isZero();
        assertThat(imports.states()).isEmpty();
    }

    @Test
    void run_whenNothingIsStoredYet_thenEveryGraphIsFetched() {
        observer.lists(entry("Orders", "\"sha256:a\""), entry("Shipping", "\"sha256:b\""));

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.REPLACED);

        assertThat(graphs.names()).containsExactly("Orders", "Shipping");
        assertThat(observer.fetches).isEqualTo(2);
    }

    @Test
    void run_whenAnEntityTagHasNotMoved_thenTheGraphIsConfirmedRatherThanFetched() {
        observer.lists(entry("Orders", "\"sha256:a\""));
        step.run(ENVIRONMENT, Deadline.none());
        observer.fetches = 0;

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.UNCHANGED);

        assertThat(observer.fetches).isZero();
        assertThat(graphs.confirmed).isEqualTo(1);
    }

    /** A {@code 304} on the index ends the kind: nothing in this environment's reactions moved. */
    @Test
    void run_whenTheIndexHasNotMoved_thenNothingIsAskedFor() {
        observer.lists(entry("Orders", "\"sha256:a\""));
        step.run(ENVIRONMENT, Deadline.none());
        observer.answersNotModified = true;
        observer.fetches = 0;

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.UNCHANGED);

        assertThat(observer.fetches).isZero();
        assertThat(observer.lastConditionalIndexEtag)
                .describedAs("the tag of the run that got through the whole list goes back").isNotNull();
    }

    @Test
    void run_whenTheIndexNoLongerListsAGraph_thenItIsRemoved() {
        observer.lists(entry("Orders", "\"sha256:a\""), entry("Shipping", "\"sha256:b\""));
        step.run(ENVIRONMENT, Deadline.none());

        observer.lists(entry("Orders", "\"sha256:a\""));
        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.REPLACED);

        assertThat(graphs.names()).containsExactly("Orders");
    }

    /**
     * The floor under the prune. An index that lists nothing while something is stored is not believed -
     * and for this step that also covers a model import that failed, because a landscape with no systems
     * places no names at all.
     */
    @Test
    void run_whenNothingCanBePlacedWhileSomethingIsStored_thenNothingIsRemoved() {
        observer.lists(entry("Orders", "\"sha256:a\""));
        step.run(ENVIRONMENT, Deadline.none());

        models.model = ArchitectureModel.empty();
        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.FAILED);

        assertThat(graphs.names()).containsExactly("Orders");
        assertThat(imports.state(ENVIRONMENT, SYSTEM_REACTIONS).failureReason())
                .contains("lists none this service could place");
    }

    @Test
    void run_whenTheObserverCannotBeRead_thenWhatIsStoredIsKept() {
        observer.lists(entry("Orders", "\"sha256:a\""));
        step.run(ENVIRONMENT, Deadline.none());
        observer.failing = true;

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.FAILED);

        assertThat(graphs.names()).containsExactly("Orders");
        assertThat(imports.state(ENVIRONMENT, SYSTEM_REACTIONS).lastSuccessAt())
                .describedAs("the last success is when the last run that worked was").isNotNull();
    }

    /** A run that stops at its deadline keeps what it stored, and is not a success. */
    @Test
    void run_whenTheDeadlineExpires_thenWhatWasStoredIsKept() {
        observer.lists(entry("Orders", "\"sha256:a\""), entry("Shipping", "\"sha256:b\""),
                entry("Catalog", "\"sha256:c\""));

        assertThat(step.run(ENVIRONMENT, Deadline.afterChecks(0))).isEqualTo(ImportOutcome.PARTIAL);

        assertThat(observer.fetches).isZero();
        assertThat(imports.state(ENVIRONMENT, SYSTEM_REACTIONS).complete()).isFalse();
    }

    /**
     * A graph that could not be replicated has to be offered again, so the index tag of that run may not be
     * sent back - a {@code 304} would hide it until the observer's graphs change.
     */
    @Test
    void run_whenAGraphIsSkipped_thenTheNextRunAsksTheIndexUnconditionally() {
        observer.lists(entry("Orders", "\"sha256:a\""));
        observer.missing("Orders");

        step.run(ENVIRONMENT, Deadline.none());
        step.run(ENVIRONMENT, Deadline.none());

        assertThat(observer.lastConditionalIndexEtag).isNull();
        assertThat(graphs.names()).isEmpty();
    }

    /**
     * <b>A name the model does not have is not stored</b>, and the index tag is not trusted after it: the
     * model may carry it after the next import, and a {@code 304} would hide it until the observer's graphs
     * move.
     */
    @Test
    void run_whenANameIsNotInTheModel_thenItIsNotStoredAndTheIndexIsAskedAgain() {
        observer.lists(entry("Orders", "\"sha256:a\""), entry("nothing-knows-this", "\"sha256:x\""));

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.REPLACED);

        assertThat(graphs.names()).containsExactly("Orders");
        assertThat(observer.lastConditionalIndexEtag).isNull();
        step.run(ENVIRONMENT, Deadline.none());
        assertThat(observer.lastConditionalIndexEtag)
                .describedAs("still unconditional while a listed name cannot be placed").isNull();
    }

    /**
     * <b>The observer lower-cases a system name</b>, and a system may be published under an alias entirely.
     * What is stored is the model's spelling, so a page is generated without resolving anything.
     */
    @Test
    void run_whenASystemIsPublishedUnderAnAlias_thenItIsStoredUnderTheModelsName() {
        observer.lists(entry("bestellungen", "\"sha256:a\""));

        step.run(ENVIRONMENT, Deadline.none());

        assertThat(graphs.names()).containsExactly("Orders");
        assertThat(graphs.stored.values()).singleElement()
                .satisfies(graph -> assertThat(graph.upstreamName())
                        .describedAs("what the observer called it is kept beside it")
                        .isEqualTo("bestellungen"));
    }

    /**
     * <b>Why there is no orphan sweep.</b> The names are resolved before anything is compared, so a system
     * that leaves the model stops being listed - and the prune, which is there for a graph the observer
     * withdrew, removes it for the same reason.
     */
    @Test
    void run_whenASystemLeavesTheModel_thenItsGraphIsPruned() {
        observer.lists(entry("Orders", "\"sha256:a\""), entry("Shipping", "\"sha256:b\""));
        step.run(ENVIRONMENT, Deadline.none());

        models.model = ArchitectureModel.of(List.of(models.model.systems().getFirst()));
        step.run(ENVIRONMENT, Deadline.none());

        assertThat(graphs.names()).containsExactly("Orders");
    }

    /**
     * An empty graph is stored as it arrived. Whether it is worth a page is the generator's decision, and the
     * import's job is to store what the observer said.
     */
    @Test
    void run_whenAGraphHasNoNodes_thenItIsStoredAllTheSame() {
        observer.lists(entry("Orders", "\"sha256:a\""));
        observer.emptyGraphs.add("Orders");

        step.run(ENVIRONMENT, Deadline.none());

        assertThat(graphs.names()).containsExactly("Orders");
    }

    /** A component's graph is stored with the system the observer says its reactions were published under. */
    @Test
    void run_whenAComponentIsListed_thenTheSystemComesFromTheIndex() {
        ReactionImportStep components = stepFor(COMPONENT_REACTIONS);
        observer.lists(new ReactionGraphRef(ENVIRONMENT, COMPONENT_REACTIONS, "orders-payment-scs",
                "orders-payment-scs", NO_VARIANT, "orders", "\"sha256:c\"", "/api/graphs/components/x", null,
                true));

        components.run(ENVIRONMENT, Deadline.none());

        assertThat(graphs.stored.values()).singleElement().satisfies(graph -> {
            assertThat(graph.name()).isEqualTo("orders-payment-scs");
            assertThat(graph.system()).isEqualTo("Orders");
        });
    }

    /** Every variant of a message type is a graph of its own, and each is stored under its own key. */
    @Test
    void run_whenAMessageTypeHasVariants_thenEachOfThemIsStored() {
        ReactionImportStep messages = stepFor(MESSAGE_REACTIONS);
        observer.lists(message("OrdersPaymentAcceptedEvent", NO_VARIANT, "\"sha256:m\""),
                message("OrdersPaymentAcceptedEvent", "legacy", "\"sha256:m\""));

        assertThat(messages.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.REPLACED);

        assertThat(graphs.stored.keySet()).extracting(Object::toString)
                .containsExactlyInAnyOrder("orderspaymentacceptedevent/", "orderspaymentacceptedevent/legacy");
    }

    /**
     * Two entries the observer spells differently and the model spells the same are one row, so they are one
     * fetch. Fetching both would write the row twice and let the counts exceed the total a log line reports.
     */
    @Test
    void run_whenTwoEntriesResolveToOneName_thenTheGraphIsFetchedOnce() {
        observer.lists(entry("Orders", "\"sha256:a\""), entry("orders", "\"sha256:b\""));

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.REPLACED);

        assertThat(observer.fetches).isOne();
        assertThat(graphs.names()).containsExactly("Orders");
    }

    /**
     * <b>A message type is listed once per variant</b>, so a single type the model does not have is dozens of
     * entries. The warning counts names and graphs apart and spells each name once: repeated eighty-one times
     * it says nothing about how much of the index could not be placed, which is the only thing it is read for.
     */
    @Test
    void run_whenAMessageTypeWithVariantsIsNotInTheModel_thenOneWarningNamesItOnceWithItsGraphs() {
        ReactionImportStep messages = stepFor(MESSAGE_REACTIONS);
        observer.lists(message("OrdersPaymentAcceptedEvent", NO_VARIANT, "\"sha256:m\""),
                message("SharedArchivedArtifactVersionCreatedEvent", NO_VARIANT, "\"sha256:x\""),
                message("SharedArchivedArtifactVersionCreatedEvent", "RAMO_Dossier", "\"sha256:x\""),
                message("SharedArchivedArtifactVersionCreatedEvent", "VSP_BeerTaxDeclaration", "\"sha256:x\""));

        messages.run(ENVIRONMENT, Deadline.none());

        assertThat(logged.list)
                .filteredOn(event -> event.getLevel() == Level.WARN)
                .extracting(ILoggingEvent::getFormattedMessage)
                .singleElement(as(STRING))
                .contains("1 of the names")
                .contains("covering 3 of 4 graphs")
                .contains("[SharedArchivedArtifactVersionCreatedEvent (3)]");
    }

    /**
     * <b>The system is part of what addresses a component's graph.</b> Two systems may each call a component
     * {@code gateway}, and one identity for both would fetch one of the two and store it as both.
     */
    @Test
    void run_whenTwoSystemsHaveAComponentOfOneName_thenBothGraphsAreStored() {
        ReactionImportStep components = stepFor(COMPONENT_REACTIONS);
        observer.lists(component("gateway", "orders", "\"sha256:a\""),
                component("gateway", "shipping", "\"sha256:b\""));

        assertThat(components.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.REPLACED);

        assertThat(observer.fetches).isEqualTo(2);
        // The model's spelling of the system, as the resolver stored it - the observer lower-cases its own.
        assertThat(graphs.stored.values()).extracting(StoredReactionGraph::system)
                .containsExactlyInAnyOrder("Orders", "Shipping");
    }

    private static ReactionGraphRef entry(String name, String etag) {
        return new ReactionGraphRef(ENVIRONMENT, SYSTEM_REACTIONS, name, name, NO_VARIANT, null, etag,
                "/api/graphs/systems/" + name, null, true);
    }

    private static ReactionGraphRef component(String name, String system, String etag) {
        return new ReactionGraphRef(ENVIRONMENT, COMPONENT_REACTIONS, name, name, NO_VARIANT, system, etag,
                "/api/graphs/components/" + name, null, true);
    }

    private static ReactionGraphRef message(String name, String variant, String etag) {
        return new ReactionGraphRef(ENVIRONMENT, MESSAGE_REACTIONS, name, name, variant, null, etag,
                "/api/graphs/messages/" + name, null, true);
    }

    /**
     * The landscape a run resolves names against: two systems, one of them with an alias, and one message
     * type. <b>Both systems have a component called {@code gateway}</b>, which is the case a component's
     * graph cannot be addressed by its name alone.
     */
    private static final class StoredModel implements ArchitectureModelSource {

        private ArchitectureModel model = ArchitectureModel.of(List.of(
                new DocumentedSystem("Orders", "orders", "", List.of("bestellungen"), null,
                        List.of(component("orders-payment-scs"), component("gateway")),
                        List.of(), List.of(new DocumentedMessage("OrdersPaymentAcceptedEvent",
                                "orders-payment-accepted-event", MessageKind.EVENT, null, null, null, null,
                                null, List.of(), List.of()))),
                new DocumentedSystem("Shipping", "shipping", "", List.of(), null,
                        List.of(component("gateway")), List.of(), List.of())));

        private static DocumentedComponent component(String name) {
            return new DocumentedComponent(name, name, null, ComponentType.SELF_CONTAINED_SYSTEM, null, null,
                    null, List.of(), null, null, null);
        }

        @Override
        public boolean isConfiguredFor(String environment) {
            return true;
        }

        @Override
        public Optional<String> sourceUrlOf(String environment) {
            return Optional.of("http://archrepo");
        }

        @Override
        public Optional<Instant> lastSuccessfulImportAt(String environment) {
            return Optional.of(NOW);
        }

        @Override
        public ArchitectureSnapshot read(String environment) {
            return new ArchitectureSnapshot(model, NOW);
        }

        @Override
        public List<String> systemSlugsOf(String environment) {
            return model.systems().stream().map(DocumentedSystem::slug).toList();
        }
    }

    private static final class FakeObserver implements ReactionGraphUpstream {

        private List<ReactionGraphRef> listed = List.of();
        private String indexEtag = "\"index-0\"";
        private boolean configured = true;
        private boolean answersNotModified;
        private boolean failing;
        private String lastConditionalIndexEtag;
        private final List<String> gone = new ArrayList<>();
        private final List<String> emptyGraphs = new ArrayList<>();
        private int indexCalls;
        private int fetches;

        void lists(ReactionGraphRef... entries) {
            listed = List.of(entries);
            indexEtag = "\"index-" + listed.size() + "\"";
        }

        void missing(String name) {
            gone.add(name);
        }

        @Override
        public boolean isConfiguredFor(String environment) {
            return configured;
        }

        @Override
        public Optional<Fetched<List<ReactionGraphRef>>> index(String environment,
                                                               ArchitectureImportKind kind,
                                                               String knownIndexEtag) {
            indexCalls++;
            lastConditionalIndexEtag = knownIndexEtag;
            if (failing) {
                throw new ArchitectureModelUnavailableException("The reaction observer answered 500.");
            }
            return answersNotModified ? Optional.empty() : Optional.of(new Fetched<>(listed, indexEtag));
        }

        @Override
        public GraphFetch content(String environment, ReactionGraphRef ref, String knownEtag) {
            fetches++;
            if (gone.contains(ref.upstreamName())) {
                return GraphFetch.skipped("it went away between the index and the fetch");
            }
            String graph = emptyGraphs.contains(ref.upstreamName())
                    ? "{\"nodes\":[],\"edges\":[]}"
                    : "{\"nodes\":[{\"id\":1}],\"edges\":[]}";
            return GraphFetch.stored(new ReactionGraph(ref.etag(), "fingerprint-of-" + ref.name(),
                    graph.getBytes(StandardCharsets.UTF_8),
                    emptyGraphs.contains(ref.upstreamName()) ? 0 : 1));
        }
    }

    /**
     * Keyed the way the table is: the name and the system folded, the variant as it stands, and each as its
     * own value rather than one joined string - so this double cannot answer differently from the database it
     * stands in for.
     */
    private static final class InMemoryGraphs implements ReactionGraphRepository {

        private final Map<Key, StoredReactionGraph> stored = new LinkedHashMap<>();
        private final Map<Key, Instant> checkedAt = new LinkedHashMap<>();
        private int confirmed;

        /** What is stored, spelled the way it was stored - which is what a test reads. */
        List<String> names() {
            return stored.values().stream().map(StoredReactionGraph::name).toList();
        }

        @Override
        public List<ReactionGraphRef> findRefs(String environment, ArchitectureImportKind kind) {
            return stored.values().stream()
                    .filter(graph -> graph.kind() == kind)
                    .map(graph -> new ReactionGraphRef(graph.environment(), graph.kind(), graph.name(),
                            graph.upstreamName(), graph.variant(), graph.system(), graph.etag(), null,
                            checkedAt.get(Key.of(graph)), graph.drawableNodes() > 0))
                    .toList();
        }

        @Override
        public Optional<StoredReactionGraph> find(String environment, ArchitectureImportKind kind, String name,
                                                  String system, String variant) {
            return Optional.ofNullable(stored.get(new Key(kind, name, system, variant)));
        }

        @Override
        public List<StoredReactionGraph> findVariants(String environment, String messageType) {
            return stored.values().stream()
                    .filter(graph -> graph.kind() == ArchitectureImportKind.MESSAGE_REACTIONS)
                    .filter(graph -> graph.name().equalsIgnoreCase(messageType))
                    .toList();
        }

        @Override
        public void store(StoredReactionGraph graph) {
            stored.put(Key.of(graph), graph);
            checkedAt.put(Key.of(graph), graph.importedAt());
        }

        @Override
        public void confirm(String environment, ArchitectureImportKind kind, String name, String system,
                            String variant, Instant at) {
            confirmed++;
            checkedAt.put(new Key(kind, name, system, variant), at);
        }

        @Override
        public void remove(Collection<ReactionGraphRef> refs) {
            refs.forEach(ref -> {
                Key key = new Key(ref.kind(), ref.name(), ref.system(), ref.variant());
                stored.remove(key);
                checkedAt.remove(key);
            });
        }

        private record Key(ArchitectureImportKind kind, String name, String system, String variant) {

            private Key(ArchitectureImportKind kind, String name, String system, String variant) {
                this.kind = kind;
                this.name = name.toLowerCase(Locale.ROOT);
                this.system = system == null ? "" : system.toLowerCase(Locale.ROOT);
                this.variant = variant;
            }

            static Key of(StoredReactionGraph graph) {
                return new Key(graph.kind(), graph.name(), graph.system(), graph.variant());
            }

            @Override
            public String toString() {
                return name + "/" + variant;
            }
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

    /** A clock that moves a second per reading, so that two timestamps of one run can be told apart. */
    private static final class SteppingClock extends Clock {

        private Instant now = NOW;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            now = now.plusSeconds(1);
            return now;
        }
    }
}
