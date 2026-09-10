package ch.admin.bit.jeap.doc.domain.architecture.imports;

import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.ComponentType;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessage;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.architecture.MessageKind;
import ch.admin.bit.jeap.doc.domain.architecture.SystemTopology;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportMetrics;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportRepository;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelRepository;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUnavailableException;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUpstream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The import of the architecture model: fetched whole, written whole, and all or nothing.
 * <p>
 * What matters here is not the mapping - that is the adapter's - but what the step does when the upstream is
 * unhelpful: nothing that leaves a landscape half replaced, and nothing that quietly leaves a system out.
 */
class ArchitectureModelImportStepTest {

    private static final String ENVIRONMENT = "dev";
    private static final Instant NOW = Instant.parse("2026-08-28T08:00:00Z");

    private FakeUpstream upstream;
    private InMemoryModels models;
    private InMemoryImports imports;
    private ArchitectureModelImportStep step;

    @BeforeEach
    void setUp() {
        upstream = new FakeUpstream();
        models = new InMemoryModels();
        imports = new InMemoryImports();
        imports.writeOrder = models.writeOrder;
        step = new ArchitectureModelImportStep(upstream, models, imports, ArchitectureImportMetrics.NONE,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /**
     * <b>The landscape is stored before the state row that says the repository was read.</b>
     * <p>
     * What a build reads is held between builds, keyed on when the repository was last read successfully - see
     * {@code StoredArchitectureModel}. That is only safe in this order: a state row written first would let a
     * build read the landscape this import is about to replace and hold it under the new key, and the site
     * would then publish the previous landscape until the next import.
     * <p>
     * The builds are asked for after the whole chain rather than here, and for the same reason: a trigger
     * starts a pass at once, so a part that started before the state row moved would hold the landscape that
     * was just replaced - see {@code ArchitectureImportJobTest}.
     */
    @Test
    void run_whenTheLandscapeIsStored_thenTheStateRowComesAfterIt() {
        upstream.has("Orders");

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.REPLACED);

        assertThat(models.writeOrder).containsExactly("landscape", "last success");
    }

    /**
     * <b>Nothing is stored that was not read whole.</b> A run that could not read the landscape writes
     * nothing, so the outcome it reports is what keeps the chain from asking for a build - see
     * {@code ArchitectureImportJobTest}.
     */
    @Test
    void run_whenTheUpstreamCannotBeRead_thenNothingIsWritten() {
        upstream.has("Orders", "Shipping");
        upstream.failsOn("Shipping");

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.FAILED);

        assertThat(models.writes).isZero();
    }

    @Test
    void run_whenNothingHasEverBeenImported_thenTheWholeLandscapeIsStored() {
        upstream.has("Orders", "Shipping");

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.REPLACED);

        assertThat(models.stored.systems()).extracting(DocumentedSystem::slug)
                .containsExactly("orders", "shipping");
        assertThat(imports.state(ENVIRONMENT, ArchitectureImportKind.MODEL).hasEverSucceeded()).isTrue();
    }

    @Test
    void run_whenTheLandscapeIsUnchanged_thenNothingIsWritten() {
        upstream.has("Orders");
        step.run(ENVIRONMENT, Deadline.none());
        models.writes = 0;

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.UNCHANGED);

        assertThat(models.writes).isZero();
    }

    @Test
    void run_whenSomethingChanged_thenEverythingIsReplaced() {
        upstream.has("Orders");
        step.run(ENVIRONMENT, Deadline.none());
        upstream.has("Orders", "Shipping");

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.REPLACED);

        assertThat(models.stored.systems()).hasSize(2);
    }

    @Test
    void run_whenASystemIsGoneFromTheArchitectureRepository_thenItIsSimplyAbsent() {
        upstream.has("Orders", "Shipping");
        step.run(ENVIRONMENT, Deadline.none());
        upstream.has("Orders");

        step.run(ENVIRONMENT, Deadline.none());

        assertThat(models.stored.systems()).extracting(DocumentedSystem::slug).containsExactly("orders");
    }

    /**
     * A landscape missing one system is not a landscape, and the alternative to leaving the stored one alone
     * is silently deleting documentation.
     */
    @Test
    void run_whenOneSystemCannotBeRead_thenNothingIsWrittenAndWhatIsStoredIsKept() {
        upstream.has("Orders", "Shipping");
        step.run(ENVIRONMENT, Deadline.none());
        ArchitectureModel before = models.stored;
        upstream.has("Orders", "Shipping", "Billing");
        upstream.failsOn("Billing");
        models.writes = 0;

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.FAILED);

        assertThat(models.writes).isZero();
        assertThat(models.stored).isSameAs(before);
    }

    /**
     * A system listed a moment ago and gone now is a race between two requests. Reading the list again is what
     * resolves it; a second disappearance means the landscape is moving faster than it can be read.
     */
    @Test
    void run_whenASystemDisappearsWhileItIsBeingRead_thenTheLandscapeIsReadAgain() {
        upstream.has("Orders", "Shipping");
        upstream.vanishesOnce("Shipping");

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.REPLACED);

        assertThat(models.stored.systems()).hasSize(2);
    }

    @Test
    void run_whenASystemKeepsDisappearing_thenNothingIsWritten() {
        upstream.has("Orders", "Shipping");
        upstream.vanishesAlways("Shipping");

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.FAILED);

        assertThat(models.writes).isZero();
    }

    @Test
    void run_whenTheDeadlineHasGone_thenNothingIsWritten() {
        upstream.has("Orders");

        assertThat(step.run(ENVIRONMENT, Deadline.of(Duration.ZERO)))
                .isEqualTo(ImportOutcome.PARTIAL);

        assertThat(models.writes).isZero();
    }

    /**
     * What the run did, on the row and not only in the meter.
     * <p>
     * A run that stopped at its deadline stored what it had reached: it is neither a success nor a failure, and
     * it is the one outcome most worth telling apart, because it is what a landscape grown past its budget
     * looks like. Read off the timestamps alone it would be indistinguishable from an upstream that could not
     * be reached.
     */
    @Test
    void run_thenTheRowSaysWhatTheRunDid() {
        upstream.has("Orders");

        step.run(ENVIRONMENT, Deadline.none());
        assertThat(imports.state(ENVIRONMENT, ArchitectureImportKind.MODEL).lastOutcome())
                .isEqualTo(ImportOutcome.REPLACED);

        step.run(ENVIRONMENT, Deadline.none());
        assertThat(imports.state(ENVIRONMENT, ArchitectureImportKind.MODEL).lastOutcome())
                .isEqualTo(ImportOutcome.UNCHANGED);
    }

    @Test
    void run_whenTheDeadlineHasGone_thenTheRowSaysPartialRatherThanFailed() {
        upstream.has("Orders");

        assertThat(step.run(ENVIRONMENT, Deadline.of(Duration.ZERO)))
                .isEqualTo(ImportOutcome.PARTIAL);

        ArchitectureImportState state = imports.state(ENVIRONMENT, ArchitectureImportKind.MODEL);
        assertThat(state.lastOutcome()).isEqualTo(ImportOutcome.PARTIAL);
        assertThat(state.hasEverSucceeded()).describedAs("a truncated run is not a success").isFalse();
    }

    /**
     * A name that cannot become a path segment is data that should not exist. Nothing is skipped over it: the
     * run is abandoned, and the landscape stored before it goes on being generated from.
     */
    @Test
    void run_whenASystemNameHasNoSlug_thenTheRunIsAbandoned() {
        upstream.has("Orders", "***");

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.FAILED);

        assertThat(models.writes).isZero();
        assertThat(imports.state(ENVIRONMENT, ArchitectureImportKind.MODEL).failureReason())
                .contains("***").contains("no slug");
    }

    @Test
    void run_whenTwoSystemNamesYieldOneSlug_thenTheRunIsAbandoned() {
        upstream.has("Order Fulfilment", "Order_Fulfilment");

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.FAILED);

        assertThat(models.writes).isZero();
        assertThat(imports.state(ENVIRONMENT, ArchitectureImportKind.MODEL).failureReason())
                .contains("order-fulfilment");
    }

    @Test
    void run_whenTheMessageNamesAreCamelCased_thenTheirSlugsAreKebabCased() {
        upstream.has("Orders");
        upstream.messagesOf("Orders", "OrdersPaymentAcceptedEvent", "OrdersCheckErpAvailabilityV2Command");

        step.run(ENVIRONMENT, Deadline.none());

        assertThat(models.stored.systems().getFirst().messages())
                .extracting(DocumentedMessage::slug)
                .containsExactly("orders-payment-accepted-event", "orders-check-erp-availability-v2-command");
    }

    /**
     * A message is documented under its slug, so a name that yields none has no page to be written to. The
     * run is abandoned rather than the message left out: nothing downstream would notice a missing page.
     */
    @Test
    void run_whenAMessageNameYieldsNoSlug_thenTheRunIsAbandoned() {
        upstream.has("Orders");
        upstream.messagesOf("Orders", "OrdersPaymentAcceptedEvent", "(?)");

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.FAILED);

        assertThat(models.writes).isZero();
        assertThat(imports.state(ENVIRONMENT, ArchitectureImportKind.MODEL).failureReason())
                .contains("(?)").contains("Orders").contains("no slug");
    }

    /**
     * Two names that kebab-case the same would be one page, the second written over the first while the
     * listing still named both. The architecture repository only refuses two names that differ by case.
     */
    @Test
    void run_whenTwoMessageNamesYieldOneSlug_thenTheRunIsAbandoned() {
        upstream.has("Orders");
        upstream.messagesOf("Orders", "OrdersFooEvent", "Orders-Foo-Event");

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.FAILED);

        assertThat(models.writes).isZero();
        assertThat(imports.state(ENVIRONMENT, ArchitectureImportKind.MODEL).failureReason())
                .contains("OrdersFooEvent").contains("Orders-Foo-Event").contains("orders-foo-event");
    }

    /** Two systems may each define a message of the same name: the segment is unique within a system. */
    @Test
    void run_whenTwoSystemsDefineAMessageOfTheSameName_thenBothAreDocumented() {
        upstream.has("Orders", "Shipping");
        upstream.messagesOf("Orders", "StatusChangedEvent");
        upstream.messagesOf("Shipping", "StatusChangedEvent");

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.REPLACED);
    }

    /** {@code index} is the listing of the group the page would sit in, and the page would replace it. */
    @Test
    void run_whenAMessageIsCalledIndex_thenTheRunIsAbandoned() {
        upstream.has("Orders");
        upstream.messagesOf("Orders", "Index");

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.FAILED);

        assertThat(models.writes).isZero();
        assertThat(imports.state(ENVIRONMENT, ArchitectureImportKind.MODEL).failureReason())
                .contains("'Index'").contains("listing");
    }

    @Test
    void run_whenTheComponentNamesAreNotSlugs_thenTheyAreDerivedRatherThanLeftOut() {
        upstream.has("Orders");
        upstream.componentsOf("Orders", "Orders Payment SCS");

        step.run(ENVIRONMENT, Deadline.none());

        assertThat(models.stored.systems().getFirst().components())
                .extracting(DocumentedComponent::slug)
                .containsExactly("orders-payment-scs");
    }

    /**
     * <b>What the content hash may not notice.</b> An importer upstream advances a component's {@code lastSeen}
     * continuously, so a hash that reads it to the second is a hash of a clock: it would never match, and every
     * hourly run of every environment would delete and re-insert every row to store what was already there -
     * which is the entire cost the hash exists to avoid.
     */
    @Test
    void run_whenOnlyTheLastSeenMovedWithinTheDay_thenNothingIsWritten() {
        upstream.has("Orders");
        upstream.componentsOf("Orders", "orders-scs");
        upstream.lastSaw(ZonedDateTime.parse("2026-08-28T06:00:00Z"));
        step.run(ENVIRONMENT, Deadline.none());
        models.writes = 0;
        upstream.lastSaw(ZonedDateTime.parse("2026-08-28T07:00:00Z"));

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.UNCHANGED);

        assertThat(models.writes).isZero();
    }

    /** And what it has to notice: a day later is a different day, so the page's "Last seen" is rewritten. */
    @Test
    void run_whenTheLastSeenMovesToAnotherDay_thenEverythingIsReplaced() {
        upstream.has("Orders");
        upstream.componentsOf("Orders", "orders-scs");
        upstream.lastSaw(ZonedDateTime.parse("2026-08-28T06:00:00Z"));
        step.run(ENVIRONMENT, Deadline.none());
        models.writes = 0;
        upstream.lastSaw(ZonedDateTime.parse("2026-08-29T06:00:00Z"));

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.REPLACED);

        assertThat(models.writes).isOne();
    }

    /**
     * How the architecture repository orders its answer is not part of the landscape, so reading it differently
     * twice must not look like a landscape that changed.
     */
    @Test
    void run_whenTheUpstreamListsTheSystemsInAnotherOrder_thenNothingIsWritten() {
        upstream.has("Orders", "Shipping");
        step.run(ENVIRONMENT, Deadline.none());
        models.writes = 0;
        upstream.listsTheSystemsBackwards();

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.UNCHANGED);

        assertThat(models.writes).isZero();
    }

    /**
     * The <b>same</b> name twice, not two names that collapse to one slug. It is stored under a unique slug, so
     * left to the write it fails on a database constraint - and what reaches the operator is then a constraint
     * violation rather than the one sentence that resolves it.
     */
    @Test
    void run_whenASystemIsListedTwice_thenTheRunIsAbandonedSayingSo() {
        upstream.has("Orders", "Orders");

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.FAILED);

        assertThat(models.writes).isZero();
        assertThat(imports.state(ENVIRONMENT, ArchitectureImportKind.MODEL).failureReason())
                .contains("system 'Orders'").contains("listed twice").contains("dev");
    }

    /** The same for a component, which is unique within its system. */
    @Test
    void run_whenAComponentOfASystemIsListedTwice_thenTheRunIsAbandonedSayingSo() {
        upstream.has("Orders");
        upstream.componentsOf("Orders", "orders-scs", "orders-scs");

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.FAILED);

        assertThat(models.writes).isZero();
        assertThat(imports.state(ENVIRONMENT, ArchitectureImportKind.MODEL).failureReason())
                .contains("component 'orders-scs'").contains("listed twice");
    }

    /**
     * And the message names the right kind of thing. The slug check is shared between systems and components,
     * and telling an operator to rename a <i>system</i> when two components collide sends them looking for
     * something that does not exist.
     */
    @Test
    void run_whenTwoComponentNamesYieldOneSlug_thenTheMessageNamesTheComponentsAndTheirSystem() {
        upstream.has("Orders");
        upstream.componentsOf("Orders", "Orders SCS", "Orders_SCS");

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.FAILED);

        assertThat(imports.state(ENVIRONMENT, ArchitectureImportKind.MODEL).failureReason())
                .contains("components 'Orders SCS' and 'Orders_SCS'")
                .contains("the system 'Orders'")
                .doesNotContain("systems '");
    }

    /**
     * <b>An index entry has to answer with itself.</b> Upstream a system is addressed by name or by alias, so
     * an alias of one system that is the name of another makes both entries answer with the same system -
     * which would document it twice, the second time under a name that is not its own.
     */
    @Test
    void run_whenASystemAnswersWithAnotherSystem_thenItIsLeftOutAndTheRestIsImported() {
        upstream.has("Orders", "Shared");
        upstream.answersWith("Shared", "Orders");

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.REPLACED);

        assertThat(models.stored.systems()).extracting(DocumentedSystem::name).containsExactly("Orders");
        assertThat(models.stored.systems()).extracting(DocumentedSystem::slug).containsExactly("orders");
    }

    /** A name that differs only in case or in whitespace is the system that was asked for. */
    @Test
    void run_whenASystemAnswersWithItsNameSpelledDifferently_thenItIsImported() {
        upstream.has("Orders");
        upstream.answersWith("Orders", " orders ");

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.REPLACED);

        assertThat(models.stored.systems()).extracting(DocumentedSystem::name).containsExactly(" orders ");
    }

    /**
     * <b>The floor under the skip.</b> An upstream where nothing answers for itself is not a landscape this
     * service can leave one system out of - importing nothing would replace the documentation of the whole
     * environment with an empty one, which is what every other failure here is careful not to do.
     */
    @Test
    void run_whenEverySystemAnswersWithAnother_thenNothingIsWritten() {
        upstream.has("Orders", "Shared");
        upstream.answersWith("Orders", "Something else");
        upstream.answersWith("Shared", "Something else");

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.FAILED);

        assertThat(models.writes).isZero();
        assertThat(imports.state(ENVIRONMENT, ArchitectureImportKind.MODEL).failureReason())
                .contains("Not one of the 2 systems").contains("dev");
    }

    @Test
    void run_whenNoArchitectureRepositoryIsConfigured_thenNothingHappens() {
        assertThat(step.run("no-archrepo", Deadline.none())).isEqualTo(ImportOutcome.NOT_CONFIGURED);
    }

    private static final class FakeUpstream implements ArchitectureModelUpstream {

        private final Map<String, List<String>> components = new LinkedHashMap<>();
        private final Map<String, List<String>> messages = new LinkedHashMap<>();
        private List<String> systems = List.of();
        private String failing;
        private final Map<String, String> answeredWith = new LinkedHashMap<>();
        private String vanishing;
        private int vanishesTimes;
        private ZonedDateTime lastSeen;

        void has(String... names) {
            systems = List.of(names);
        }

        /** The order the upstream happens to answer the system list in, which is not part of the landscape. */
        void listsTheSystemsBackwards() {
            List<String> reversed = new ArrayList<>(systems);
            Collections.reverse(reversed);
            systems = List.copyOf(reversed);
        }

        /** When an importer upstream last saw every component. It moves on its own, run after run. */
        void lastSaw(ZonedDateTime when) {
            lastSeen = when;
        }

        void componentsOf(String system, String... names) {
            components.put(system, List.of(names));
        }

        void messagesOf(String system, String... names) {
            messages.put(system, List.of(names));
        }

        void failsOn(String system) {
            failing = system;
        }

        /** What an alias of one system that is the name of another does upstream: both names, one system. */
        void answersWith(String system, String answered) {
            answeredWith.put(system, answered);
        }

        void vanishesOnce(String system) {
            vanishing = system;
            vanishesTimes = 1;
        }

        void vanishesAlways(String system) {
            vanishing = system;
            vanishesTimes = Integer.MAX_VALUE;
        }

        @Override
        public Set<String> environments() {
            return Set.of(ENVIRONMENT);
        }

        @Override
        public Optional<String> urlOf(String environment) {
            return Optional.of("https://archrepo.example.org/archrepo");
        }

        @Override
        public List<String> systemNames(String environment) {
            return systems;
        }

        @Override
        public Optional<SystemTopology> topology(String environment, String system) {
            if (system.equals(failing)) {
                throw new ArchitectureModelUnavailableException("The architecture repository answered 500.");
            }
            if (system.equals(vanishing) && vanishesTimes > 0) {
                vanishesTimes--;
                return Optional.empty();
            }
            List<DocumentedComponent> parts = new ArrayList<>();
            for (String name : components.getOrDefault(system, List.of())) {
                parts.add(new DocumentedComponent(name, null, null, ComponentType.of("BACKEND"), null, null,
                        lastSeen, List.of(), null, null, null));
            }
            return Optional.of(new SystemTopology(answeredWith.getOrDefault(system, system), null, List.of(),
                    null, parts, List.of()));
        }

        @Override
        public Optional<List<DocumentedMessage>> messages(String environment, String system) {
            return Optional.of(messages.getOrDefault(system, List.of()).stream()
                    .map(name -> new DocumentedMessage(name, null, MessageKind.EVENT, null, "topic", null, null,
                            null, List.of(), List.of()))
                    .toList());
        }
    }

    private static final class InMemoryModels implements ArchitectureModelRepository {

        private ArchitectureModel stored = ArchitectureModel.empty();
        private Instant storedImportedAt;
        private int writes;
        /** What was written, and in which order - shared with the state rows, which are written after it. */
        private List<String> writeOrder = new ArrayList<>();

        @Override
        public ArchitectureSnapshot read(String environment) {
            return new ArchitectureSnapshot(stored, storedImportedAt);
        }

        @Override
        public List<String> systemSlugsOf(String environment) {
            return stored.systems().stream().map(DocumentedSystem::slug).sorted().toList();
        }

        @Override
        public void replace(String environment, ArchitectureModel model, Instant importedAt) {
            stored = model;
            storedImportedAt = importedAt;
            writes++;
            writeOrder.add("landscape");
        }
    }

    private static final class InMemoryImports implements ArchitectureImportRepository {

        private final Map<String, ArchitectureImportState> states = new LinkedHashMap<>();
        /** The same list the landscape writes into, so that the order between the two is visible. */
        private List<String> writeOrder = new ArrayList<>();

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
            if (state.lastSuccessAt() != null) {
                writeOrder.add("last success");
            }
        }
    }
}
