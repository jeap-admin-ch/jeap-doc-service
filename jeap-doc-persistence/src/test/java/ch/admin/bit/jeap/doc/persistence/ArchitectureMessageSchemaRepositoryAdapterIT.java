package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.architecture.MessageSchema;
import ch.admin.bit.jeap.doc.domain.architecture.imports.MessageVersionRef;
import ch.admin.bit.jeap.doc.domain.architecture.MessageVersionSchemas;
import ch.admin.bit.jeap.doc.domain.port.MessageSchemaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The replicated message type schemas against a real PostgreSQL.
 * <p>
 * What matters here beyond the round trip: that a rendering of any length survives it, that reading a system
 * reads that system only - it is the call a build makes once per system, and reading a landscape's worth of
 * renderings is what this shape exists to avoid - and that storing a version this service already holds
 * replaces the row rather than failing on the unique index.
 */
class ArchitectureMessageSchemaRepositoryAdapterIT extends PostgresTestContainerBase {

    private static final Instant REPLICATED_AT = Instant.parse("2026-09-03T08:00:11Z");

    @Autowired
    private MessageSchemaRepository schemas;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void store_thenEveryFieldComesBackUnchanged() {
        MessageVersionSchemas stored = version("round-trip", "orders", "OrdersPaidEvent", "2.0.0");

        schemas.store(stored);

        assertThat(schemas.findAll("round-trip", "orders")).containsExactly(stored);
    }

    /**
     * A resolved schema is every imported IDL file inlined into one, so it is thousands of characters. The
     * column is text for that reason, and this is what says so.
     */
    @Test
    void store_whenTheRenderingIsLong_thenItSurvivesTheRoundTrip() {
        String longRendering = ("//-- Start OrdersPaidEventValue.avdl\n"
                                + "string orderId;\n").repeat(4000);
        schemas.store(new MessageVersionSchemas("long", "orders", "OrdersPaidEvent", "1.0.0", null, null, null,
                new MessageSchema("OrdersPaidEventValue.avdl", "https://registry/value.avdl", longRendering),
                "\"sha256:long\"", REPLICATED_AT));

        assertThat(schemas.findAll("long", "orders")).singleElement()
                .satisfies(version -> assertThat(version.value().resolvedSchema()).isEqualTo(longRendering));
    }

    /**
     * The call a build makes, once per system. Reading a landscape's renderings into a build that then runs the
     * site generator for minutes is exactly what this shape avoids, so it must not read the neighbours.
     */
    @Test
    void findAll_thenOnlyTheSystemAskedFor() {
        schemas.store(version("several", "orders", "OrdersPaidEvent", "1.0.0"));
        schemas.store(version("several", "orders", "OrdersPaidEvent", "2.0.0"));
        schemas.store(version("several", "shipping", "ShippingSentEvent", "1.0.0"));
        schemas.store(version("other-environment", "orders", "OrdersPaidEvent", "1.0.0"));

        assertThat(schemas.findAll("several", "orders"))
                .extracting(MessageVersionSchemas::version).containsExactly("1.0.0", "2.0.0");
    }

    /**
     * The architecture model and these rows carry the spellings of two different exports of the same upstream.
     * An exact match would answer nothing where they differ in case, and every page of that system would be
     * written complete and without a schema on it - no failed build, no broken link, nothing in the log.
     */
    @Test
    void findAll_whenTheModelSpellsTheSystemDifferently_thenItsSchemasAreStillFound() {
        schemas.store(version("folded", "orders", "OrdersPaidEvent", "1.0.0"));

        assertThat(schemas.findAll("folded", "ORDERS")).hasSize(1);
    }

    /**
     * <b>The other half of the fold.</b> Reading case-insensitively while storing case-sensitively would let one
     * version become two rows - both listed on the page, and neither pruned, because the import compares
     * identities the same way it stores them. The unique index folds the two names, so the second store is a
     * replacement of the first.
     */
    @Test
    void store_whenTheUpstreamChangesTheCaseItSpellsAVersionWith_thenItIsStillOneRow() {
        schemas.store(version("recase", "Orders", "OrdersPaidEvent", "1.0.0"));

        schemas.store(version("recase", "orders", "orderspaidevent", "1.0.0"));

        assertThat(schemas.findAll("recase", "ORDERS")).hasSize(1);
        assertThat(schemas.findRefs("recase")).hasSize(1);
    }

    /** And a prune addresses the row whichever of the two spellings the caller happens to hold it under. */
    @Test
    void remove_whenTheCallerSpellsTheVersionDifferently_thenTheRowStillGoes() {
        schemas.store(version("recase-remove", "orders", "OrdersPaidEvent", "1.0.0"));

        schemas.remove(List.of(MessageVersionRef.stored("recase-remove", "ORDERS", "ORDERSPAIDEVENT", "1.0.0",
                null, null)));

        assertThat(schemas.findRefs("recase-remove")).isEmpty();
    }

    /**
     * The read a generation run makes, once per system, must be served by the identity index rather than by a
     * scan of the table holding the two largest columns in this database. Asserted on the plan, because the
     * behaviour is identical either way - which is how an index on the wrong expression went unnoticed.
     */
    @Test
    void findAll_thenTheIdentityIndexServesIt() {
        schemas.store(version("planned", "orders", "OrdersPaidEvent", "1.0.0"));

        String plan = String.join(" ", jdbc.queryForList(
                "explain select * from architecture_message_schema where environment = 'planned' "
                + "and lower(system_name) = lower('ORDERS') order by message_name, version", String.class));

        assertThat(plan).contains("architecture_message_schema_identity");
    }

    @Test
    void findAll_whenTheSystemHasNoSchemas_thenNothingRatherThanAFailure() {
        assertThat(schemas.findAll("empty", "orders")).isEmpty();
    }

    /**
     * A run revalidates a stored version with the tag it was served under, and records that it did so without
     * rewriting the schemas - which is what a confirmation costs: one column of one row.
     */
    @Test
    void confirm_thenTheVersionIsCheckedAgainWithoutItsSchemasBeingRewritten() {
        schemas.store(version("confirmed", "orders", "OrdersPaidEvent", "1.0.0"));
        Instant later = REPLICATED_AT.plusSeconds(3600);

        schemas.confirm("confirmed", "orders", "OrdersPaidEvent", "1.0.0", later);

        assertThat(schemas.findRefs("confirmed")).singleElement().satisfies(ref -> {
            assertThat(ref.checkedAt()).isEqualTo(later);
            assertThat(ref.etag()).isEqualTo("\"sha256:1.0.0\"");
        });
        assertThat(schemas.findAll("confirmed", "orders")).singleElement()
                .satisfies(stored -> assertThat(stored.replicatedAt())
                        .describedAs("a confirmation does not claim the schemas were fetched again")
                        .isEqualTo(REPLICATED_AT));
    }

    /** What deciding which versions to fetch works on: the versions that are stored, their tags, and no more. */
    @Test
    void findRefs_thenEveryStoredVersionOfTheEnvironment() {
        schemas.store(version("refs", "orders", "OrdersPaidEvent", "1.0.0"));
        schemas.store(version("refs", "shipping", "ShippingSentEvent", "3.1.0"));

        assertThat(schemas.findRefs("refs")).extracting(MessageVersionRef::identity)
                .containsExactlyInAnyOrder("orders OrdersPaidEvent 1.0.0", "shipping ShippingSentEvent 3.1.0");
        assertThat(schemas.findRefs("refs")).allSatisfy(ref -> {
            assertThat(ref.etag()).describedAs("the tag the next run revalidates with").isNotNull();
            assertThat(ref.checkedAt()).describedAs("what orders the revalidations of a truncated run")
                    .isEqualTo(REPLICATED_AT);
        });
    }

    /**
     * What the prune of an import run does, and the escape hatch for a schema corrected in place: the row goes,
     * and the next run fetches the version again.
     */
    @Test
    void remove_thenThoseVersionsAreGoneAndTheRestStay() {
        schemas.store(version("pruned", "orders", "OrdersPaidEvent", "1.0.0"));
        schemas.store(version("pruned", "orders", "OrdersPaidEvent", "2.0.0"));

        schemas.remove(List.of(MessageVersionRef.stored("pruned", "orders", "OrdersPaidEvent", "1.0.0",
                null, null)));

        assertThat(schemas.findAll("pruned", "orders")).extracting(MessageVersionSchemas::version)
                .containsExactly("2.0.0");
    }

    /** A message type with no key schema is ordinary, and its key side reads back as nothing at all. */
    /**
     * <b>The unique index is the load-bearing constraint of this table</b>, and a blind insert walks into it.
     * The upstream lists a version twice where a system defines an event and a command of one name, and it
     * answers with its own spelling of the system rather than the one the index gave - so a second store of a
     * version this service holds is a case, not a defect. Without the lookup in the adapter this fails with a
     * {@code DataIntegrityViolationException}, which the import step records as a failure of the whole
     * environment on every run from then on.
     */
    @Test
    void store_whenTheVersionIsAlreadyStored_thenTheRowIsReplacedRatherThanRefused() {
        schemas.store(version("twice", "orders", "OrdersPaidEvent", "2.0.0"));

        schemas.store(new MessageVersionSchemas("twice", "orders", "OrdersPaidEvent", "2.0.0", "FORWARD",
                "1.0.0", null, new MessageSchema("Value.avdl", "https://registry/v2.avdl", "string later;"),
                "\"sha256:second\"", REPLICATED_AT));

        assertThat(schemas.findAll("twice", "orders")).singleElement().satisfies(version -> {
            assertThat(version.compatibilityMode()).isEqualTo("FORWARD");
            assertThat(version.value().resolvedSchema()).isEqualTo("string later;");
            assertThat(version.key())
                    .describedAs("a side the upstream no longer serves is cleared, not left behind")
                    .isNull();
        });
    }

    @Test
    void store_whenThereIsNoKeySchema_thenTheKeySideIsAbsentRatherThanEmpty() {
        schemas.store(new MessageVersionSchemas("no-key", "orders", "OrdersPaidEvent", "1.0.0", null, null,
                null, new MessageSchema("OrdersPaidEventValue.avdl", "https://registry/value.avdl", "string a;"),
                "\"sha256:no-key\"", REPLICATED_AT));

        assertThat(schemas.findAll("no-key", "orders")).singleElement()
                .satisfies(version -> assertThat(version.key()).isNull());
    }

    private static MessageVersionSchemas version(String environment, String system, String message,
                                                 String version) {
        return new MessageVersionSchemas(environment, system, message, version, "BACKWARD", "1.0.0",
                new MessageSchema("Key.avdl", "https://registry/key.avdl", "string orderId;"),
                new MessageSchema("Value.avdl", "https://registry/value.avdl", "string orderId;\nint total;"),
                "\"sha256:" + version + "\"", REPLICATED_AT);
    }
}
