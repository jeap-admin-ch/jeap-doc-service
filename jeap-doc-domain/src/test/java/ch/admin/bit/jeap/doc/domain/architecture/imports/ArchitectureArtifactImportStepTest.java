package ch.admin.bit.jeap.doc.domain.architecture.imports;

import ch.admin.bit.jeap.doc.domain.port.ArchitectureArtifactRepository;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureArtifactUpstream;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportMetrics;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportRepository;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUnavailableException;
import ch.admin.bit.jeap.doc.domain.port.ArtifactFetch;
import ch.admin.bit.jeap.doc.domain.port.Fetched;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The replication of the artifacts: only what moved is fetched, and a run that stops early keeps what it got.
 */
class ArchitectureArtifactImportStepTest {

    private static final String ENVIRONMENT = "dev";
    private static final ArchitectureImportKind KIND = ArchitectureImportKind.OPENAPI_SPEC;
    private static final Instant NOW = Instant.parse("2026-08-28T08:00:00Z");

    private FakeUpstream upstream;
    private InMemoryArtifacts artifacts;
    private InMemoryImports imports;
    private SteppingClock clock;
    private ArchitectureArtifactImportStep step;

    @BeforeEach
    void setUp() {
        upstream = new FakeUpstream();
        artifacts = new InMemoryArtifacts();
        imports = new InMemoryImports();
        clock = new SteppingClock();
        step = new ArchitectureArtifactImportStep(KIND, upstream, artifacts, imports,
                ArchitectureImportMetrics.NONE, clock);
    }

    @Test
    void run_whenNothingIsStoredYet_thenEverythingIsFetched() {
        upstream.lists(entry("orders", "orders-payment-scs", "\"sha256:aaa\""),
                entry("orders", "orders-basket-scs", "\"sha256:bbb\""));

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.REPLACED);

        assertThat(artifacts.stored).hasSize(2);
        assertThat(upstream.fetches).isEqualTo(2);
    }

    @Test
    void run_whenAnEntityTagIsUnchanged_thenTheContentIsNotFetchedAgain() {
        upstream.lists(entry("orders", "orders-payment-scs", "\"sha256:aaa\""));
        step.run(ENVIRONMENT, Deadline.none());
        upstream.fetches = 0;

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.UNCHANGED);

        assertThat(upstream.fetches).isZero();
        assertThat(artifacts.confirmed).isEqualTo(1);
    }

    @Test
    void run_whenAnEntityTagMoved_thenOnlyThatOneIsFetched() {
        upstream.lists(entry("orders", "orders-payment-scs", "\"sha256:aaa\""),
                entry("orders", "orders-basket-scs", "\"sha256:bbb\""));
        step.run(ENVIRONMENT, Deadline.none());
        upstream.fetches = 0;
        upstream.lists(entry("orders", "orders-payment-scs", "\"sha256:ccc\""),
                entry("orders", "orders-basket-scs", "\"sha256:bbb\""));

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.REPLACED);

        assertThat(upstream.fetches).isEqualTo(1);
    }

    @Test
    void run_whenTheIndexIsUnchanged_thenNothingIsLookedAt() {
        upstream.lists(entry("orders", "orders-payment-scs", "\"sha256:aaa\""));
        step.run(ENVIRONMENT, Deadline.none());
        upstream.answersNotModified = true;
        upstream.fetches = 0;

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.UNCHANGED);

        assertThat(upstream.fetches).isZero();
        assertThat(upstream.lastConditionalIndexEtag).isEqualTo("\"index-1\"");
    }

    @Test
    void run_whenAnArtifactIsNoLongerListed_thenItIsRemoved() {
        upstream.lists(entry("orders", "orders-payment-scs", "\"sha256:aaa\""),
                entry("orders", "orders-basket-scs", "\"sha256:bbb\""));
        step.run(ENVIRONMENT, Deadline.none());
        upstream.lists(entry("orders", "orders-payment-scs", "\"sha256:aaa\""));

        step.run(ENVIRONMENT, Deadline.none());

        assertThat(artifacts.names()).containsExactly("orders/orders-payment-scs");
    }

    /**
     * An index that answers "not modified" says the landscape is unchanged, not that everything in it was
     * fetched. Trusting it after a truncated run would skip what was missed for ever.
     */
    @Test
    void run_whenTheRunBeforeItWasTruncated_thenTheIndexIsAskedUnconditionally() {
        upstream.lists(entry("orders", "a-scs", "\"sha256:aaa\""), entry("orders", "b-scs", "\"sha256:bbb\""));
        step.run(ENVIRONMENT, Deadline.of(Duration.ZERO));
        assertThat(imports.state(ENVIRONMENT, KIND).complete()).isFalse();

        step.run(ENVIRONMENT, Deadline.none());

        assertThat(upstream.lastConditionalIndexEtag).isNull();
    }

    @Test
    void run_whenTheDeadlineHasGone_thenWhatWasStoredIsKept() {
        upstream.lists(entry("orders", "a-scs", "\"sha256:aaa\""));

        assertThat(step.run(ENVIRONMENT, Deadline.of(Duration.ZERO))).isEqualTo(ImportOutcome.PARTIAL);

        assertThat(imports.state(ENVIRONMENT, KIND).complete()).isFalse();
    }

    /**
     * The staleness gauge is what an operator alarms on, so a run that never got through its list must not
     * refresh it - otherwise a replication truncating at item twenty of five hundred reads healthy for ever.
     */
    @Test
    void run_whenTheDeadlineHasGone_thenItDoesNotCountAsASuccess() {
        upstream.lists(entry("orders", "a-scs", "\"sha256:aaa\""));

        step.run(ENVIRONMENT, Deadline.of(Duration.ZERO));

        assertThat(imports.state(ENVIRONMENT, KIND).hasEverSucceeded()).isFalse();
    }

    @Test
    void run_whenItGetsThroughTheList_thenItCountsAsASuccess() {
        upstream.lists(entry("orders", "a-scs", "\"sha256:aaa\""));

        step.run(ENVIRONMENT, Deadline.none());

        assertThat(imports.state(ENVIRONMENT, KIND).hasEverSucceeded()).isTrue();
    }

    @Test
    void run_whenOneContentIsGone_thenTheRestIsStillFetched() {
        upstream.lists(entry("orders", "a-scs", "\"sha256:aaa\""), entry("orders", "b-scs", "\"sha256:bbb\""));
        upstream.missing("orders/a-scs");

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.REPLACED);

        assertThat(artifacts.names()).containsExactly("orders/b-scs");
    }

    /**
     * An artifact that could not be replicated is not one that was confirmed. Had the run trusted the index
     * afterwards, a "not modified" on it would have hidden the artifact for as long as the index stayed the
     * same - a new specification that was briefly gone, absent for ever.
     */
    @Test
    void run_whenAnArtifactCouldNotBeReplicated_thenTheNextRunAsksTheIndexAgainAndFetchesIt() {
        upstream.lists(entry("orders", "a-scs", "\"sha256:aaa\""), entry("orders", "b-scs", "\"sha256:bbb\""));
        upstream.missing("orders/a-scs");
        step.run(ENVIRONMENT, Deadline.none());
        assertThat(imports.state(ENVIRONMENT, KIND).complete()).isFalse();
        upstream.gone.clear();
        upstream.fetches = 0;

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.REPLACED);

        assertThat(upstream.lastConditionalIndexEtag).isNull();
        assertThat(upstream.fetches).isEqualTo(1);
        assertThat(artifacts.names()).containsExactly("orders/b-scs", "orders/a-scs");
    }

    /**
     * Skipping one is not failing: an artifact over the size cap is the upstream's defect, and the run
     * replicated everything else. It would otherwise keep the staleness alarm on for as long as the defect
     * stands.
     */
    @Test
    void run_whenAnArtifactIsSkipped_thenTheRunStillCountsAsASuccess() {
        upstream.lists(entry("orders", "a-scs", "\"sha256:aaa\""), entry("orders", "b-scs", "\"sha256:bbb\""));
        upstream.missing("orders/a-scs");

        step.run(ENVIRONMENT, Deadline.none());

        assertThat(imports.state(ENVIRONMENT, KIND).hasEverSucceeded()).isTrue();
    }

    /** A "not modified" against the stored copy confirms it, and the index tag stays trustworthy. */
    @Test
    void run_whenTheUpstreamConfirmsAStoredCopy_thenTheIndexTagIsTrusted() {
        upstream.lists(entry("orders", "a-scs", "\"sha256:aaa\""));
        step.run(ENVIRONMENT, Deadline.none());
        upstream.lists(entry("orders", "a-scs", "\"sha256:bbb\""));
        upstream.confirms("orders/a-scs");
        artifacts.confirmed = 0;

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.UNCHANGED);

        assertThat(artifacts.confirmed).isEqualTo(1);
        assertThat(imports.state(ENVIRONMENT, KIND).complete()).isTrue();
        assertThat(imports.state(ENVIRONMENT, KIND).conditionalIndexEtag()).isEqualTo("\"index-1\"");
    }

    /** A "not modified" with nothing stored confirms nothing, and is left for the next run like a skip. */
    @Test
    void run_whenTheUpstreamConfirmsWhatIsNotStored_thenTheIndexIsNotTrusted() {
        upstream.lists(entry("orders", "a-scs", "\"sha256:aaa\""));
        upstream.confirms("orders/a-scs");

        step.run(ENVIRONMENT, Deadline.none());

        assertThat(artifacts.stored).isEmpty();
        assertThat(artifacts.confirmed).isZero();
        assertThat(imports.state(ENVIRONMENT, KIND).complete()).isFalse();
    }

    /**
     * A run that keeps hitting its deadline has to spend its time on what changed. Ordered by name, twenty
     * unchanged entries would be reconfirmed on every run and the one changed entry at the end of the
     * alphabet never reached.
     */
    @Test
    void run_whenTheDeadlineKeepsExpiring_thenAChangedArtifactIsFetchedBeforeTheUnchangedOnesAreConfirmed() {
        List<ArchitectureArtifactRef> unchanged = new ArrayList<>();
        for (char name = 'a'; name <= 't'; name++) {
            unchanged.add(entry("orders", name + "-scs", "\"sha256:" + name + "\""));
        }
        List<ArchitectureArtifactRef> before = new ArrayList<>(unchanged);
        before.add(entry("orders", "z-scs", "\"sha256:z1\""));
        upstream.lists(before.toArray(ArchitectureArtifactRef[]::new));
        step.run(ENVIRONMENT, Deadline.none());
        List<ArchitectureArtifactRef> after = new ArrayList<>(unchanged);
        after.add(entry("orders", "z-scs", "\"sha256:z2\""));
        upstream.lists(after.toArray(ArchitectureArtifactRef[]::new));
        upstream.fetches = 0;

        assertThat(step.run(ENVIRONMENT, Deadline.afterChecks(2))).isEqualTo(ImportOutcome.PARTIAL);

        assertThat(upstream.fetches).isEqualTo(1);
        assertThat(artifacts.etagOf("orders", "z-scs")).isEqualTo("\"sha256:z2\"");
    }

    /**
     * And the confirmations rotate: each truncated run confirms the entries that were checked longest ago, so
     * every entry is looked at eventually rather than the same first ones on every run.
     */
    @Test
    void run_whenTheDeadlineKeepsExpiring_thenEachRunConfirmsTheOnesCheckedLongestAgo() {
        upstream.lists(entry("orders", "a-scs", "\"sha256:aaa\""), entry("orders", "b-scs", "\"sha256:bbb\""),
                entry("orders", "c-scs", "\"sha256:ccc\""));
        step.run(ENVIRONMENT, Deadline.none());

        for (int run = 0; run < 3; run++) {
            clock.advance(Duration.ofMinutes(15));
            step.run(ENVIRONMENT, Deadline.afterChecks(2));
        }

        assertThat(artifacts.confirmations).containsExactly("orders/a-scs", "orders/b-scs", "orders/c-scs");
    }

    @Test
    void run_whenTheUpstreamIsUnreachable_thenWhatIsStoredIsKept() {
        upstream.lists(entry("orders", "a-scs", "\"sha256:aaa\""));
        step.run(ENVIRONMENT, Deadline.none());
        upstream.failing = true;

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.FAILED);

        assertThat(artifacts.stored).hasSize(1);
        assertThat(imports.state(ENVIRONMENT, KIND).failureReason()).isNotNull();
    }

    /**
     * A step reports what went wrong rather than throwing it: the staleness gauge reads the state row, and a
     * run that dies on the way out leaves it saying that the last success is as old as it was.
     */
    @Test
    void run_whenTheDatabaseFails_thenItIsStillRecordedAsAFailure() {
        upstream.lists(entry("orders", "a-scs", "\"sha256:aaa\""));
        artifacts.failing = true;

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.FAILED);

        assertThat(imports.state(ENVIRONMENT, KIND).failureReason()).isNotNull();
        assertThat(imports.state(ENVIRONMENT, KIND).lastSuccessAt()).isNull();
    }

    /**
     * <b>The floor under the prune.</b> An index that lists nothing where something is stored would delete the
     * whole environment's worth of artifacts in one run - and everything that produces it, a proxy, a truncated
     * answer, an architecture repository whose own import failed, looks exactly like an upstream that publishes
     * nothing. An upstream that has genuinely withdrawn every one of them is still withdrawing them one run
     * later, so refusing costs an operator a warning and nothing else.
     */
    @Test
    void run_whenTheIndexSuddenlyListsNothing_thenNothingIsRemoved() {
        upstream.lists(entry("orders", "a-scs", "\"sha256:aaa\""), entry("orders", "b-scs", "\"sha256:bbb\""));
        step.run(ENVIRONMENT, Deadline.none());
        upstream.lists();

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.FAILED);

        assertThat(artifacts.names()).containsExactly("orders/a-scs", "orders/b-scs");
        assertThat(imports.state(ENVIRONMENT, KIND).failureReason()).contains("lists none while 2 are stored");
        assertThat(imports.state(ENVIRONMENT, KIND).complete())
                .describedAs("and the index tag is not trusted afterwards")
                .isFalse();
    }

    /** With nothing stored, an index that lists nothing is simply an environment with no artifacts. */
    @Test
    void run_whenTheIndexListsNothingAndNothingIsStored_thenItIsAnEmptyEnvironment() {
        upstream.lists();

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.UNCHANGED);

        assertThat(artifacts.stored).isEmpty();
    }

    /**
     * <b>The identity folds case, the way the unique index on the table does.</b> The model and these rows carry
     * the spellings of two different exports of the same upstream, so a re-spelled system would otherwise be a
     * second entry the index does not refuse - fetched again on every run, and never pruned.
     */
    @Test
    void run_whenTheIndexRespellsASystem_thenItIsTheSameArtifactRatherThanASecondOne() {
        upstream.lists(entry("orders", "a-scs", "\"sha256:aaa\""));
        step.run(ENVIRONMENT, Deadline.none());
        upstream.lists(entry("Orders", "A-SCS", "\"sha256:aaa\""));

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.UNCHANGED);

        assertThat(artifacts.stored).hasSize(1);
        assertThat(artifacts.names())
                .describedAs("the stored row keeps the spelling it arrived with")
                .containsExactly("orders/a-scs");
        assertThat(artifacts.confirmations)
                .describedAs("and the row is confirmed rather than fetched a second time")
                .containsExactly("Orders/A-SCS");
    }

    /**
     * The two names are two fields and not one joined string. A system {@code a/b} with a component {@code c}
     * and a system {@code a} with a component {@code b/c} produce the same joined key, and a prune would then
     * take the neighbour with it.
     */
    @Test
    void run_whenTwoArtifactsJoinToTheSameKey_thenTheyStayTwoArtifacts() {
        upstream.lists(entry("a/b", "c", "\"sha256:aaa\""), entry("a", "b/c", "\"sha256:bbb\""));

        assertThat(step.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.REPLACED);

        assertThat(artifacts.stored).hasSize(2);
    }

    private static ArchitectureArtifactRef entry(String system, String component, String etag) {
        return new ArchitectureArtifactRef(ENVIRONMENT, KIND, system, component, "1.0.0", etag, NOW,
                "/archrepo/docs-api/systems/%s/components/%s/openapi".formatted(system, component), null);
    }

    private static final class FakeUpstream implements ArchitectureArtifactUpstream {

        private List<ArchitectureArtifactRef> listed = List.of();
        private String indexEtag = "\"index-0\"";
        private boolean answersNotModified;
        private boolean failing;
        private String lastConditionalIndexEtag;
        private final List<String> gone = new ArrayList<>();
        private final List<String> confirming = new ArrayList<>();
        private int fetches;

        void lists(ArchitectureArtifactRef... entries) {
            listed = List.of(entries);
            indexEtag = "\"index-" + listed.size() + "\"";
        }

        void missing(String identity) {
            gone.add(identity);
        }

        /** Answers "not modified" for the entry, whatever tag the index listed for it. */
        void confirms(String identity) {
            confirming.add(identity);
        }

        @Override
        public Optional<Fetched<List<ArchitectureArtifactRef>>> index(String environment,
                                                                      ArchitectureImportKind kind,
                                                                      String knownIndexEtag) {
            lastConditionalIndexEtag = knownIndexEtag;
            if (failing) {
                throw new ArchitectureModelUnavailableException("The architecture repository answered 500.");
            }
            return answersNotModified ? Optional.empty() : Optional.of(new Fetched<>(listed, indexEtag));
        }

        @Override
        public ArtifactFetch content(String environment, ArchitectureArtifactRef entry, String knownEtag) {
            fetches++;
            String identity = entry.system() + "/" + entry.component();
            if (gone.contains(identity)) {
                return ArtifactFetch.skipped("it went away between the index and the fetch");
            }
            if (confirming.contains(identity)) {
                return ArtifactFetch.unchanged();
            }
            byte[] content = entry.etag().getBytes(StandardCharsets.UTF_8);
            return ArtifactFetch.stored(new ArchitectureArtifact(environment, entry.kind(), entry.system(),
                    entry.component(), entry.version(), entry.etag(), content, content.length,
                    entry.modifiedAt(), NOW));
        }
    }

    /**
     * Keyed the way the table is: by the two names as two values, and <b>folded</b>. Keying it by the names
     * joined with a slash instead would let this double answer differently from the database it stands in for -
     * it would fold nothing, and it would make one row of a system {@code a/b} with a component {@code c} and
     * a system {@code a} with a component {@code b/c}.
     */
    private static final class InMemoryArtifacts implements ArchitectureArtifactRepository {

        private final Map<Key, ArchitectureArtifact> stored = new LinkedHashMap<>();
        private final Map<Key, Instant> checkedAt = new LinkedHashMap<>();
        private final List<String> confirmations = new ArrayList<>();
        private int confirmed;
        private boolean failing;

        /** What is stored, spelled the way it arrived - which is what a test reads. */
        List<String> names() {
            return stored.values().stream().map(a -> a.system() + "/" + a.component()).toList();
        }

        String etagOf(String system, String component) {
            return stored.get(new Key(system, component)).etag();
        }

        @Override
        public List<ArchitectureArtifactRef> findRefs(String environment, ArchitectureImportKind kind) {
            return stored.values().stream()
                    .map(artifact -> new ArchitectureArtifactRef(artifact.environment(), artifact.kind(),
                            artifact.system(), artifact.component(), artifact.version(), artifact.etag(),
                            artifact.modifiedAt(), null,
                            checkedAt.get(new Key(artifact.system(), artifact.component()))))
                    .toList();
        }

        @Override
        public Optional<ArchitectureArtifact> find(String environment, ArchitectureImportKind kind,
                                                   String system, String component) {
            return Optional.ofNullable(stored.get(new Key(system, component)));
        }

        @Override
        public List<ArchitectureArtifact> findAll(String environment, ArchitectureImportKind kind,
                                                  String system) {
            return stored.values().stream().filter(a -> a.system().equalsIgnoreCase(system)).toList();
        }

        @Override
        public void store(ArchitectureArtifact artifact) {
            if (failing) {
                throw new IllegalStateException("The database went away.");
            }
            Key key = new Key(artifact.system(), artifact.component());
            stored.put(key, artifact);
            checkedAt.put(key, artifact.replicatedAt());
        }

        @Override
        public void confirm(String environment, ArchitectureImportKind kind, String system, String component,
                            Instant checkedAt) {
            confirmed++;
            confirmations.add(system + "/" + component);
            this.checkedAt.put(new Key(system, component), checkedAt);
        }

        @Override
        public void remove(Collection<ArchitectureArtifactRef> refs) {
            refs.forEach(ref -> stored.remove(new Key(ref.system(), ref.component())));
        }

        @Override
        public int removeOrphans(String environment) {
            return 0;
        }

        private record Key(String system, String component) {

            private Key {
                system = system.toLowerCase(java.util.Locale.ROOT);
                component = component.toLowerCase(java.util.Locale.ROOT);
            }
        }
    }

    /** A clock the test moves by hand, so that one run is visibly later than the one before it. */
    private static final class SteppingClock extends Clock {

        private Instant now = NOW;

        void advance(Duration by) {
            now = now.plus(by);
        }

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
            return now;
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
