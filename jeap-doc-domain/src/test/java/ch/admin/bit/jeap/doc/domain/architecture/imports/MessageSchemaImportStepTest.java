package ch.admin.bit.jeap.doc.domain.architecture.imports;

import ch.admin.bit.jeap.doc.domain.architecture.MessageSchema;
import ch.admin.bit.jeap.doc.domain.architecture.MessageVersionSchemas;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportMetrics;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportRepository;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUnavailableException;
import ch.admin.bit.jeap.doc.domain.port.MessageSchemaRepository;
import ch.admin.bit.jeap.doc.domain.port.MessageSchemaUpstream;
import ch.admin.bit.jeap.doc.domain.port.SchemaFetch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What one run of the message schema replication does.
 * <p>
 * The assertion that matters most is what a request costs. Every listed version is asked about, because a
 * version is not fixed once published - but a version this service already holds is asked
 * <b>conditionally</b>, and an unchanged one answers {@code 304} with no payload. That is what lets this run on
 * the same schedule as the model, and it is the first thing a change here would break.
 */
class MessageSchemaImportStepTest {

    private static final String ENVIRONMENT = "prod";
    private static final Instant NOW = Instant.parse("2026-09-03T08:00:00Z");

    private FakeUpstream upstream;
    private InMemorySchemas schemas;
    private InMemoryImports imports;
    private MessageSchemaImportStep step;

    @BeforeEach
    void setUp() {
        upstream = new FakeUpstream();
        schemas = new InMemorySchemas();
        imports = new InMemoryImports();
        step = new MessageSchemaImportStep(upstream, schemas, imports, ArchitectureImportMetrics.NONE,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void run_whenNothingIsStored_thenEveryListedVersionIsFetched() {
        upstream.lists("orders", "OrdersPaidEvent", "1.0.0", "2.0.0");

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.REPLACED);

        assertThat(schemas.stored).hasSize(2);
        assertThat(upstream.fetches).containsExactly("orders OrdersPaidEvent 1.0.0",
                "orders OrdersPaidEvent 2.0.0");
        assertThat(upstream.conditionalFetches)
                .describedAs("nothing was stored, so there is no tag to ask with").isEmpty();
        assertThat(imports.state(ENVIRONMENT, ArchitectureImportKind.MESSAGE_SCHEMA).itemCount()).isEqualTo(2);
    }

    /**
     * <b>The economy of this step.</b> A second run of an unchanged landscape asks about every version, and
     * every one of them answers "not modified" against the tag stored beside its schemas - so the run costs
     * one request per version and not one payload.
     */
    @Test
    void run_whenEveryVersionIsStored_thenEachIsRevalidatedAndNoneIsFetchedAgain() {
        upstream.lists("orders", "OrdersPaidEvent", "1.0.0", "2.0.0");
        step.run(ENVIRONMENT, Deadline.none());
        upstream.forgetWhatWasAsked();

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.UNCHANGED);

        assertThat(upstream.conditionalFetches).containsExactly("orders OrdersPaidEvent 1.0.0",
                "orders OrdersPaidEvent 2.0.0");
        assertThat(schemas.writes)
                .describedAs("a confirmed version is not written again").isEqualTo(2);
        assertThat(schemas.confirmations).isEqualTo(2);
    }

    /**
     * The reason this step revalidates at all. A version's payload is not fixed once published: the
     * compatibility it declares is derived upstream from the version list, so publishing an intermediate
     * version changes what an already published version answers.
     */
    @Test
    void run_whenAStoredVersionMovedUpstream_thenItIsFetchedAgainAndReplaced() {
        upstream.lists("orders", "OrdersPaidEvent", "1.0.0");
        step.run(ENVIRONMENT, Deadline.none());
        upstream.serves("orders OrdersPaidEvent 1.0.0", "sha256:later", "BACKWARD", "2.0.0");

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.REPLACED);

        assertThat(schemas.stored.get("orders orderspaidevent 1.0.0").compatibleVersion()).isEqualTo("2.0.0");
        assertThat(schemas.stored).hasSize(1);
    }

    @Test
    void run_whenAVersionIsNew_thenOnlyThatOneIsFetchedWithoutATag() {
        upstream.lists("orders", "OrdersPaidEvent", "1.0.0");
        step.run(ENVIRONMENT, Deadline.none());
        upstream.forgetWhatWasAsked();
        upstream.lists("orders", "OrdersPaidEvent", "1.0.0", "2.0.0");

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.REPLACED);

        assertThat(upstream.unconditionalFetches).containsExactly("orders OrdersPaidEvent 2.0.0");
        assertThat(upstream.conditionalFetches).containsExactly("orders OrdersPaidEvent 1.0.0");
    }

    /** What is not stored is asked about first, so a run that runs out of time still makes progress. */
    @Test
    void run_thenWhatIsNotStoredIsAskedAboutBeforeWhatOnlyNeedsConfirming() {
        upstream.lists("orders", "OrdersPaidEvent", "1.0.0");
        step.run(ENVIRONMENT, Deadline.none());
        upstream.forgetWhatWasAsked();
        upstream.lists("orders", "OrdersPaidEvent", "1.0.0", "2.0.0");

        step.run(ENVIRONMENT, Deadline.afterChecks(2));

        assertThat(upstream.fetches)
                .describedAs("the version with no schemas at all comes before the one that only needs a tag "
                             + "comparison, whatever the index order")
                .containsExactly("orders OrdersPaidEvent 2.0.0");
    }

    @Test
    void run_whenAVersionIsWithdrawn_thenItIsRemoved() {
        upstream.lists("orders", "OrdersPaidEvent", "1.0.0", "2.0.0");
        step.run(ENVIRONMENT, Deadline.none());
        // The index still names the message type, and no longer names 1.0.0 - which is what a withdrawn
        // version looks like, as against a message type the index goes quiet about altogether.
        upstream.forgetEverything();
        upstream.lists("orders", "OrdersPaidEvent", "2.0.0");

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.REPLACED);

        assertThat(schemas.stored.keySet()).containsExactly("orders orderspaidevent 2.0.0");
    }

    /**
     * The index is served from the architecture repository's own store, so a run of <i>its</i> import that was
     * partial answers with fewer message types than exist. Treating an absent one as a deletion would throw
     * away every schema of it and fetch them all again on the next run.
     */
    @Test
    void run_whenTheIndexDoesNotMentionAMessageTypeAtAll_thenItsVersionsAreLeftAlone() {
        upstream.lists("orders", "OrdersPaidEvent", "1.0.0");
        upstream.lists("shipping", "ShippingSentEvent", "1.0.0");
        step.run(ENVIRONMENT, Deadline.none());
        upstream.forgetEverything();
        upstream.lists("orders", "OrdersPaidEvent", "1.0.0");

        step.run(ENVIRONMENT, Deadline.none());

        assertThat(schemas.stored.keySet())
                .describedAs("the shipping schemas are not thrown away because the index went quiet about them")
                .contains("shipping shippingsentevent 1.0.0");
    }

    @Test
    void run_whenTheDeadlineExpires_thenWhatWasStoredIsKeptAndTheRunIsPartial() {
        upstream.lists("orders", "OrdersPaidEvent", "1.0.0", "2.0.0");

        assertThat(step.run(ENVIRONMENT, Deadline.of(Duration.ZERO))).isEqualTo(ImportOutcome.PARTIAL);

        assertThat(imports.state(ENVIRONMENT, ArchitectureImportKind.MESSAGE_SCHEMA).hasEverSucceeded())
                .describedAs("a truncated run is not a success").isFalse();
        assertThat(imports.state(ENVIRONMENT, ArchitectureImportKind.MESSAGE_SCHEMA).complete()).isFalse();
    }

    /** What a run that stopped early left is what the next one starts from. */
    @Test
    void run_whenAPreviousRunStoppedEarly_thenTheNextOnePicksUpWhatIsMissing() {
        upstream.lists("orders", "OrdersPaidEvent", "1.0.0", "2.0.0");
        step.run(ENVIRONMENT, Deadline.afterChecks(1));
        int storedAfterTheFirstRun = schemas.stored.size();

        step.run(ENVIRONMENT, Deadline.none());

        assertThat(storedAfterTheFirstRun).isLessThan(2);
        assertThat(schemas.stored).hasSize(2);
    }

    @Test
    void run_whenTheUpstreamIsUnavailable_thenWhatIsStoredIsKept() {
        upstream.lists("orders", "OrdersPaidEvent", "1.0.0");
        step.run(ENVIRONMENT, Deadline.none());
        upstream.unavailable = true;

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.FAILED);

        assertThat(schemas.stored).hasSize(1);
        assertThat(imports.state(ENVIRONMENT, ArchitectureImportKind.MESSAGE_SCHEMA).failureReason())
                .isNotNull();
    }

    /** One version withdrawn between the index and the fetch costs that version, not the run. */
    @Test
    void run_whenOneVersionCannotBeFetched_thenTheRestAreStored() {
        upstream.lists("orders", "OrdersPaidEvent", "1.0.0", "2.0.0");
        upstream.missing.add("orders OrdersPaidEvent 1.0.0");

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.REPLACED);

        assertThat(schemas.stored.keySet()).containsExactly("orders orderspaidevent 2.0.0");
    }

    /**
     * A "not modified" against nothing is not a confirmation. Storing nothing and confirming nothing leaves the
     * version to the next run, which asks for it unconditionally.
     * <p>
     * And the run is <b>not</b> a success: it is the only version there was, so this run replicated nothing at
     * all. Reporting it as unchanged would refresh {@code lastSuccessAt} and reset the staleness gauge for an
     * environment whose every version failed, which is the one thing that gauge is watched for.
     */
    @Test
    void run_whenAVersionAnswersNotModifiedWithNothingStored_thenItIsSkippedAndTheRunIsNotASuccess() {
        upstream.lists("orders", "OrdersPaidEvent", "1.0.0");
        upstream.answersNotModifiedTo.add("orders OrdersPaidEvent 1.0.0");

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.PARTIAL);

        assertThat(schemas.stored).isEmpty();
        assertThat(schemas.confirmations).isZero();
        assertThat(imports.state(ENVIRONMENT, ArchitectureImportKind.MESSAGE_SCHEMA).complete()).isFalse();
        assertThat(imports.state(ENVIRONMENT, ArchitectureImportKind.MESSAGE_SCHEMA).hasEverSucceeded())
                .isFalse();
    }

    /**
     * The upstream answers with the system and the message type <b>as it stores them</b>, which need not be how
     * the index spelled them - an alias resolves to the stored name, and the path is matched ignoring case. Two
     * index entries then collapse onto one row, and storing it twice would be a second insert against the
     * unique index: without the run's own record of what it has just written, that fails the replication of the
     * environment and keeps failing it.
     */
    @Test
    void run_whenTheUpstreamAnswersWithItsOwnSpelling_thenOneRowIsStored() {
        // An alias and the name it resolves to, which are two names and therefore two identities in the index:
        // the run only finds out they are one row from what the upstream answers. Two spellings of the *same*
        // name would collapse into one entry before the run asks anything, and this would then pass with the
        // guard deleted. The alias sorts first, so it is the one that is asked - and the entry that follows is
        // the row it has just written.
        upstream.lists("legacy-orders", "OrdersPaidEvent", "1.0.0");
        upstream.lists("orders", "OrdersPaidEvent", "1.0.0");
        upstream.answersAs("orders");

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.REPLACED);

        assertThat(schemas.stored.keySet()).containsExactly("orders orderspaidevent 1.0.0");
        assertThat(upstream.fetches)
                .describedAs("the second entry is not asked: it is the row the first one just wrote")
                .containsExactly("legacy-orders OrdersPaidEvent 1.0.0");
        assertThat(schemas.writes).isOne();
    }

    /**
     * <b>And the second run costs nothing.</b> A stored version whose spelling differs from the index's is the
     * case a case-sensitive comparison never recognises: it is fetched in full on every run, never confirmed,
     * and - because the message type it belongs to is not in the index under that spelling either - never
     * pruned. One run cannot see any of that, which is why this asserts over two.
     */
    @Test
    void run_whenTheStoredSpellingDiffersFromTheIndex_thenTheNextRunStillOnlyRevalidates() {
        upstream.lists("Orders", "OrdersPaidEvent", "1.0.0");
        upstream.answersAs("orders");
        step.run(ENVIRONMENT, Deadline.none());
        upstream.forgetWhatWasAsked();
        int writesAfterTheFirstRun = schemas.writes;

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.UNCHANGED);

        assertThat(upstream.unconditionalFetches)
                .describedAs("the stored version is recognised, so it is asked about with its tag").isEmpty();
        assertThat(upstream.conditionalFetches).containsExactly("Orders OrdersPaidEvent 1.0.0");
        assertThat(schemas.writes).isEqualTo(writesAfterTheFirstRun);
        assertThat(schemas.confirmations).isEqualTo(1);
    }

    /** A version the index stops listing is pruned whichever spelling the store holds it under. */
    @Test
    void run_whenAVersionStoredUnderAnotherSpellingIsWithdrawn_thenItIsStillRemoved() {
        upstream.lists("Orders", "OrdersPaidEvent", "1.0.0");
        upstream.lists("Orders", "OrdersPaidEvent", "2.0.0");
        upstream.answersAs("orders");
        step.run(ENVIRONMENT, Deadline.none());
        upstream.forgetEverything();
        upstream.lists("Orders", "OrdersPaidEvent", "2.0.0");

        step.run(ENVIRONMENT, Deadline.none());

        assertThat(schemas.stored.keySet()).containsExactly("orders orderspaidevent 2.0.0");
    }

    /** An index that lists one version twice costs one request and one row, not a failed run. */
    @Test
    void run_whenTheIndexListsAVersionTwice_thenItIsFetchedOnce() {
        upstream.listsTwice("orders", "OrdersPaidEvent", "1.0.0");

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.REPLACED);

        assertThat(upstream.fetches).containsExactly("orders OrdersPaidEvent 1.0.0");
        assertThat(schemas.stored).hasSize(1);
    }

    @Test
    void run_whenTheIndexIsEmpty_thenNothingIsStoredAndTheRunSucceeds() {
        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.UNCHANGED);

        assertThat(imports.state(ENVIRONMENT, ArchitectureImportKind.MESSAGE_SCHEMA).hasEverSucceeded())
                .isTrue();
    }

    private static final class FakeUpstream implements MessageSchemaUpstream {

        private final Map<String, MessageVersionRef> listed = new LinkedHashMap<>();
        private final List<MessageVersionRef> extra = new ArrayList<>();
        private final List<String> fetches = new ArrayList<>();
        private final List<String> conditionalFetches = new ArrayList<>();
        private final List<String> unconditionalFetches = new ArrayList<>();
        private final List<String> missing = new ArrayList<>();
        private final List<String> answersNotModifiedTo = new ArrayList<>();
        private final Map<String, Served> served = new LinkedHashMap<>();
        private int indexReads;
        private boolean unavailable;
        private String answersAs;

        private void lists(String system, String message, String... versions) {
            for (String version : versions) {
                MessageVersionRef ref = MessageVersionRef.listed(ENVIRONMENT, system, message, version,
                        "/docs-api/message-types/" + system + "/" + message + "/versions/" + version);
                listed.put(ref.identity(), ref);
            }
        }

        /** What the upstream serves for one version from now on - a new tag means it moved. */
        private void serves(String identity, String etag, String mode, String compatibleVersion) {
            served.put(identity, new Served(etag, mode, compatibleVersion));
        }

        /** The system name the upstream answers with, whatever the index entry said. */
        private void answersAs(String system) {
            answersAs = system;
        }

        /** One version listed twice, as the upstream does where a kind is part of its grouping key. */
        private void listsTwice(String system, String message, String version) {
            lists(system, message, version);
            extra.add(MessageVersionRef.listed(ENVIRONMENT, system, message, version, "/docs-api/x"));
        }

        private void forgetWhatWasAsked() {
            fetches.clear();
            conditionalFetches.clear();
            unconditionalFetches.clear();
        }

        private void forgetEverything() {
            listed.clear();
            extra.clear();
        }

        @Override
        public List<MessageVersionRef> index(String environment) {
            indexReads++;
            if (unavailable) {
                throw new ArchitectureModelUnavailableException("the upstream is down");
            }
            List<MessageVersionRef> refs = new ArrayList<>(listed.values());
            refs.addAll(extra);
            return List.copyOf(refs);
        }

        @Override
        public SchemaFetch version(String environment, MessageVersionRef ref, String knownEtag) {
            fetches.add(ref.identity());
            if (knownEtag == null) {
                unconditionalFetches.add(ref.identity());
            } else {
                conditionalFetches.add(ref.identity());
            }
            if (missing.contains(ref.identity())) {
                return SchemaFetch.skipped("it went away between the index and the fetch");
            }
            if (answersNotModifiedTo.contains(ref.identity())) {
                return SchemaFetch.unchanged();
            }
            Served serving = served.getOrDefault(ref.identity(),
                    new Served("sha256:first", "BACKWARD", null));
            if (serving.etag().equals(knownEtag)) {
                return SchemaFetch.unchanged();
            }
            String system = answersAs == null ? ref.system() : answersAs;
            return SchemaFetch.stored(new MessageVersionSchemas(environment, system, ref.message(),
                    ref.version(), serving.mode(), serving.compatibleVersion(),
                    new MessageSchema("Key.avdl", "https://registry/Key.avdl", "string id;"),
                    new MessageSchema("Value.avdl", "https://registry/Value.avdl", "string id;"),
                    serving.etag(), NOW));
        }

        private record Served(String etag, String mode, String compatibleVersion) {
        }
    }

    private static final class InMemorySchemas implements MessageSchemaRepository {

        private final Map<String, MessageVersionSchemas> stored = new LinkedHashMap<>();
        private final Map<String, Instant> checkedAt = new LinkedHashMap<>();
        private int writes;
        private int confirmations;

        /** Keyed the way the unique index of the real table is: folded on the two names. */
        private static String keyOf(String system, String message, String version) {
            return system.toLowerCase(java.util.Locale.ROOT) + " "
                   + message.toLowerCase(java.util.Locale.ROOT) + " " + version;
        }

        @Override
        public List<MessageVersionRef> findRefs(String environment) {
            return stored.values().stream()
                    .map(version -> MessageVersionRef.stored(environment, version.system(), version.message(),
                            version.version(), version.etag(),
                            checkedAt.get(keyOf(version.system(), version.message(), version.version()))))
                    .toList();
        }

        @Override
        public List<MessageVersionSchemas> findAll(String environment, String system) {
            return stored.values().stream().filter(version -> version.system().equals(system)).toList();
        }

        @Override
        public void store(MessageVersionSchemas version) {
            String key = keyOf(version.system(), version.message(), version.version());
            stored.put(key, version);
            checkedAt.put(key, version.replicatedAt());
            writes++;
        }

        @Override
        public void confirm(String environment, String system, String message, String version,
                            Instant when) {
            checkedAt.put(keyOf(system, message, version), when);
            confirmations++;
        }

        @Override
        public void remove(Collection<MessageVersionRef> versions) {
            versions.forEach(version -> {
                // Addressed the way the real delete addresses it: folded, so the spelling the caller happens
                // to hold the version under does not decide whether the row goes.
                String key = keyOf(version.system(), version.message(), version.version());
                stored.remove(key);
                checkedAt.remove(key);
            });
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
