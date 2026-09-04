package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureArtifact;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureSnapshot;
import ch.admin.bit.jeap.doc.domain.architecture.ComponentType;
import ch.admin.bit.jeap.doc.domain.architecture.ContractRole;
import ch.admin.bit.jeap.doc.domain.architecture.DatabaseSchemaReference;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessage;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessageVersion;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.architecture.MessageContract;
import ch.admin.bit.jeap.doc.domain.architecture.MessageKind;
import ch.admin.bit.jeap.doc.domain.architecture.OpenApiReference;
import ch.admin.bit.jeap.doc.domain.architecture.RelationKind;
import ch.admin.bit.jeap.doc.domain.architecture.RestApiOperation;
import ch.admin.bit.jeap.doc.domain.architecture.SystemRelation;
import ch.admin.bit.jeap.doc.domain.architecture.Team;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureArtifactRepository;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The imported architecture model against a real PostgreSQL.
 * <p>
 * Four things here are worth more than the round trip: that replacing a landscape whose teams changed does not
 * trip over the foreign keys, that reading one costs a fixed number of queries however many systems it has,
 * that an artifact whose component is gone is swept, and that a read while an import replaces the landscape
 * still returns one whole generation of it.
 */
class ArchitectureModelRepositoryAdapterIT extends PostgresTestContainerBase {

    private static final Instant IMPORTED_AT = Instant.parse("2026-08-28T08:00:11Z");

    @Autowired
    private ArchitectureModelRepository models;

    @Autowired
    private ArchitectureArtifactRepository artifacts;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void replaceAndRead_whenALandscapeWithEverythingSet_thenItComesBackUnchanged() {
        models.replace("full", ArchitectureModel.of(List.of(fullSystem())), IMPORTED_AT);

        ArchitectureModel loaded = models.read("full").model();

        assertThat(loaded.systems()).containsExactly(fullSystem());
    }

    @Test
    void replaceAndLoad_whenEveryOptionalFieldIsNullAndEveryListEmpty_thenItComesBackUnchanged() {
        DocumentedSystem bare = new DocumentedSystem("Bare", "bare", null, List.of(), null, List.of(),
                List.of(), List.of());

        models.replace("bare", ArchitectureModel.of(List.of(bare)), IMPORTED_AT);

        assertThat(models.read("bare").model().systems()).containsExactly(bare);
    }

    /**
     * The rows written before the slug column existed have none. They are gone with the first import after
     * the deployment, and until then the reader derives the slug from the name rather than failing the build.
     */
    @Test
    void load_whenAMessageWasStoredWithoutASlug_thenItIsDerivedFromTheName() {
        models.replace("pre-slug", ArchitectureModel.of(List.of(fullSystem())), IMPORTED_AT);
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        entityManager.getTransaction().begin();
        entityManager.createNativeQuery("update architecture_message set slug = null").executeUpdate();
        entityManager.getTransaction().commit();
        entityManager.close();

        ArchitectureModel loaded = models.read("pre-slug").model();

        assertThat(loaded.systems().getFirst().messages()).extracting(DocumentedMessage::slug)
                .containsExactly("orders-payment-accepted-event");
    }

    @Test
    void load_whenNothingHasEverBeenImported_thenEmptyRatherThanAFailure() {
        assertThat(models.read("never-imported").model().isEmpty()).isTrue();
    }

    /**
     * The delete order: a system and a component both reference a team, so the teams have to go after the
     * systems that reference them. Importing twice over changed teams is what would trip over it.
     */
    @Test
    void replace_whenTheTeamsChangedSinceTheLastImport_thenTheForeignKeysHold() {
        Team first = new Team("Team Alpha", "alpha@example.org", null, null);
        Team second = new Team("Team Beta", "beta@example.org", null, null);
        models.replace("teams", ArchitectureModel.of(List.of(systemOwnedBy(first))), IMPORTED_AT);

        models.replace("teams", ArchitectureModel.of(List.of(systemOwnedBy(second))), IMPORTED_AT);

        ArchitectureModel loaded = models.read("teams").model();
        assertThat(loaded.systems()).singleElement()
                .satisfies(system -> assertThat(system.team()).isEqualTo(second));
        assertThat(loaded.systems().getFirst().components()).singleElement()
                .satisfies(component -> assertThat(component.team()).isEqualTo(second));
    }

    @Test
    void replace_whenASystemIsGoneFromTheLandscape_thenItIsSimplyAbsent() {
        models.replace("shrinking", ArchitectureModel.of(List.of(namedSystem("Alpha"), namedSystem("Beta"))),
                IMPORTED_AT);

        models.replace("shrinking", ArchitectureModel.of(List.of(namedSystem("Alpha"))), IMPORTED_AT);

        assertThat(models.read("shrinking").model().systems()).extracting(DocumentedSystem::slug)
                .containsExactly("alpha");
    }

    @Test
    void replace_whenAnotherEnvironmentHasTheSameSystem_thenItIsUntouched() {
        models.replace("one", ArchitectureModel.of(List.of(namedSystem("Alpha"))), IMPORTED_AT);
        models.replace("two", ArchitectureModel.of(List.of(namedSystem("Alpha"))), IMPORTED_AT);

        models.replace("one", ArchitectureModel.of(List.of()), IMPORTED_AT);

        assertThat(models.read("one").model().isEmpty()).isTrue();
        assertThat(models.read("two").model().systems()).hasSize(1);
    }

    /**
     * A generation run reads a whole landscape at once. Forty-nine systems must not mean five hundred round
     * trips, which is what an association mapping would have made of it.
     */
    @Test
    void read_whateverTheNumberOfSystems_thenTheQueryCountDoesNotGrow() {
        models.replace("small", landscapeOf(2), IMPORTED_AT);
        models.replace("large", landscapeOf(40), IMPORTED_AT);

        long forSmall = queriesToRead("small");
        long forLarge = queriesToRead("large");

        assertThat(forLarge).isEqualTo(forSmall).isLessThanOrEqualTo(10);
    }

    /**
     * <b>The write side, which nothing else measures.</b> A landscape is replaced hourly per environment, and
     * the four child tables with an {@code @IdClass} - the aliases, the REST operations, the message versions
     * and the contract versions - are the largest of them. Spring Data decides insert-versus-update from the
     * identifier, and a composite id whose second component is a primitive {@code int} is never null: without
     * {@code Persistable} every one of those rows is written with a {@code merge}, which is a {@code select}
     * before every {@code insert}.
     * <p>
     * The bound is a bound and not the number: twenty systems with two aliases, two operations, two message
     * versions and two contract versions each cost 323 statements as this stands, and 160 more - eight extra
     * selects per system - the moment one of the four entities stops declaring itself new.
     */
    @Test
    void replace_thenTheChildRowsAreInsertedRatherThanMerged() {
        int systems = 20;
        models.replace("write-count", ArchitectureModel.of(List.of()), IMPORTED_AT);
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        models.replace("write-count", landscapeWithChildren(systems), IMPORTED_AT);

        assertThat(statistics.getPrepareStatementCount())
                .describedAs("no select before an insert on any of the four child tables")
                .isLessThan(18L * systems);
    }

    @Test
    void removeOrphans_whenTheModelNoLongerHasTheComponent_thenTheArtifactGoes() {
        models.replace("sweep", ArchitectureModel.of(List.of(fullSystem())), IMPORTED_AT);
        artifacts.store(artifactFor("Orders", "orders-payment-scs"));
        artifacts.store(artifactFor("Orders", "orders-withdrawn-scs"));

        models.replace("sweep", ArchitectureModel.of(List.of(fullSystem())), IMPORTED_AT);

        assertThat(artifacts.findRefs("sweep", ArchitectureImportKind.OPENAPI_SPEC))
                .extracting(ref -> ref.component())
                .containsExactly("orders-payment-scs");
    }

    /**
     * A build reading the landscape while an import replaces it - which nothing stops, because the build lock
     * is per site and the import lock is per environment.
     * <p>
     * The read is ten statements, each keyed by the identifiers the one before it returned, and a replace gives
     * every row an identifier fresh from a sequence. So at the default isolation of PostgreSQL, where every
     * statement takes its own snapshot, an import committing in the middle of a read makes the child queries
     * match nothing at all and the landscape comes back as <b>systems with no components</b> - which a build
     * publishes without anything going wrong. <b>Without the snapshot the read is taken in, this fails within a
     * few iterations.</b>
     */
    @Test
    void read_whileAnImportIsReplacingTheLandscape_thenNoSystemLosesItsComponents() throws InterruptedException {
        ArchitectureModel landscape = landscapeOf(30);
        models.replace("racing", landscape, IMPORTED_AT);
        AtomicBoolean importing = new AtomicBoolean(true);
        AtomicReference<RuntimeException> importFailed = new AtomicReference<>();
        Thread importer = new Thread(() -> {
            try {
                for (int generation = 1; importing.get(); generation++) {
                    models.replace("racing", landscape, IMPORTED_AT.plusSeconds(generation));
                }
            } catch (RuntimeException e) {
                importFailed.set(e);
            }
        }, "importing-racing");

        importer.start();
        try {
            for (int read = 1; read <= 60; read++) {
                ArchitectureSnapshot snapshot = models.read("racing");
                assertThat(snapshot.model().systems()).as("the systems read by read %d", read).hasSize(30);
                assertThat(snapshot.model().systems()).allSatisfy(system ->
                        assertThat(system.components()).as("the components of %s", system.slug()).hasSize(1));
                assertThat(snapshot.importedAt()).as("the import the content of read %d is from", read)
                        .isNotNull();
            }
        } finally {
            importing.set(false);
            importer.join(Duration.ofSeconds(30));
        }
        assertThat(importFailed.get()).as("the import running beside the reads").isNull();
    }

    /**
     * The stamp every generated page carries: the import the content came from, out of the same snapshot as the
     * content itself. Read on its own it would be the last import that <i>ran</i>, which is a different moment
     * whenever the last one found the landscape unchanged and wrote nothing.
     */
    @Test
    void read_whenTheLandscapeWasImportedTwice_thenTheStampIsTheImportTheContentIsFrom() {
        Instant later = IMPORTED_AT.plus(Duration.ofHours(1));
        models.replace("stamped", ArchitectureModel.of(List.of(namedSystem("Alpha"))), IMPORTED_AT);
        assertThat(models.read("stamped").importedAt()).isEqualTo(IMPORTED_AT);

        models.replace("stamped", ArchitectureModel.of(List.of(namedSystem("Alpha"))), later);

        assertThat(models.read("stamped").importedAt()).isEqualTo(later);
    }

    @Test
    void read_whenTheEnvironmentWasNeverImported_thenThereIsNoStampAndNoLandscape() {
        ArchitectureSnapshot snapshot = models.read("unstamped");

        assertThat(snapshot.model().isEmpty()).isTrue();
        assertThat(snapshot.importedAt()).isNull();
    }

    private long queriesToRead(String environment) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        models.read(environment).model();
        return statistics.getPrepareStatementCount();
    }

    /** Systems whose every {@code @IdClass} child table carries rows - see the statement-count test. */
    private static ArchitectureModel landscapeWithChildren(int systems) {
        return ArchitectureModel.of(IntStream.range(0, systems).mapToObj(index -> {
            String name = "System" + index;
            String slug = name.toLowerCase(java.util.Locale.ROOT);
            DocumentedComponent component = new DocumentedComponent(slug + "-scs", slug + "-scs", null,
                    ComponentType.SELF_CONTAINED_SYSTEM, null, null, null,
                    List.of(new RestApiOperation("GET", "/api/a"), new RestApiOperation("POST", "/api/a")),
                    null, null);
            DocumentedMessage message = new DocumentedMessage(name + "PaidEvent", slug + "-paid-event",
                    MessageKind.EVENT, null, "topic", null, null, null,
                    List.of(DocumentedMessageVersion.of("1.0.0"), DocumentedMessageVersion.of("2.0.0")),
                    List.of(new MessageContract(ContractRole.of("PUBLISHER"), slug + "-scs", name, "topic",
                            List.of("1.0.0", "2.0.0"))));
            return new DocumentedSystem(name, slug, null, List.of(name + "-alias", name + "-legacy"), null,
                    List.of(component), List.of(), List.of(message));
        }).toList());
    }

    private static ArchitectureModel landscapeOf(int systems) {
        return ArchitectureModel.of(IntStream.range(0, systems)
                .mapToObj(index -> namedSystem("System" + index))
                .toList());
    }

    private static DocumentedSystem namedSystem(String name) {
        return new DocumentedSystem(name, name.toLowerCase(java.util.Locale.ROOT), null, List.of(), null,
                List.of(component(name.toLowerCase(java.util.Locale.ROOT) + "-scs", null)), List.of(), List.of());
    }

    private static DocumentedSystem systemOwnedBy(Team team) {
        return new DocumentedSystem("Owned", "owned", null, List.of(), team,
                List.of(component("owned-scs", team)), List.of(), List.of());
    }

    private static DocumentedComponent component(String name, Team team) {
        return new DocumentedComponent(name, name, null, ComponentType.SELF_CONTAINED_SYSTEM, team, null, null,
                List.of(), null, null);
    }

    private static DocumentedSystem fullSystem() {
        Team team = new Team("Team Orders", "orders@example.org", "https://jira.example.org/ORDERS",
                "https://confluence.example.org/orders");
        DocumentedComponent payment = new DocumentedComponent("orders-payment-scs", "orders-payment-scs",
                "Takes the money", ComponentType.SELF_CONTAINED_SYSTEM, team, "openshift",
                ZonedDateTime.of(2026, 8, 20, 9, 30, 0, 0, ZoneId.of("Europe/Zurich")),
                List.of(new RestApiOperation("GET", "/api/payments"),
                        new RestApiOperation("POST", "/api/payments")),
                new OpenApiReference("2.1.0", "https://orders.example.org", "/docs-api/openapi",
                        "https://orders.example.org/swagger"),
                new DatabaseSchemaReference("14", "/docs-api/database-schema"));
        DocumentedMessage accepted = new DocumentedMessage("OrdersPaymentAcceptedEvent",
                "orders-payment-accepted-event", MessageKind.EVENT,
                "PUBLIC", "orders.payment", "The payment went through", "https://registry.example.org/descriptor",
                "https://confluence.example.org/payment",
                List.of(DocumentedMessageVersion.of("1.0.0"), DocumentedMessageVersion.of("2.0.0")),
                List.of(new MessageContract(ContractRole.of("PUBLISHER"), "orders-payment-scs", "Orders",
                                "orders.payment", List.of("2.0.0")),
                        new MessageContract(ContractRole.of("CONSUMER"), "shipping-dispatch-scs", "Shipping",
                                "orders.payment", List.of("1.0.0", "2.0.0"))));
        SystemRelation relation = new SystemRelation(RelationKind.of("EVENT"), "Shipping",
                "shipping-dispatch-scs", "Orders", "orders-payment-scs", "OrdersPaymentAcceptedEvent", null,
                null, null);
        return new DocumentedSystem("Orders", "orders", "Everything about orders", List.of("ORD", "Bestellung"),
                team, List.of(payment), List.of(relation), List.of(accepted));
    }

    private static ArchitectureArtifact artifactFor(String system, String component) {
        byte[] content = "{\"openapi\":\"3.0.0\"}".getBytes(StandardCharsets.UTF_8);
        return new ArchitectureArtifact("sweep", ArchitectureImportKind.OPENAPI_SPEC, system, component, "2.1.0",
                "\"sha256:abc\"", content, content.length, IMPORTED_AT, IMPORTED_AT);
    }
}
