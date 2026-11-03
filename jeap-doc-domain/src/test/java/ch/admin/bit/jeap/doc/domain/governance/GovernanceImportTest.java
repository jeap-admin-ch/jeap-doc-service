package ch.admin.bit.jeap.doc.domain.governance;

import ch.admin.bit.jeap.doc.domain.port.GovernanceImportMetrics;
import ch.admin.bit.jeap.doc.domain.port.GovernanceImportRepository;
import ch.admin.bit.jeap.doc.domain.port.GovernanceIndexFetch;
import ch.admin.bit.jeap.doc.domain.port.GovernanceResourceFetch;
import ch.admin.bit.jeap.doc.domain.port.GovernanceResourceRepository;
import ch.admin.bit.jeap.doc.domain.port.GovernanceUnavailableException;
import ch.admin.bit.jeap.doc.domain.port.GovernanceUpstream;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The governance import: only what moved is fetched, and the site is rebuilt whenever the index itself moved,
 * whether or not a system or rule did.
 */
class GovernanceImportTest {

    private static final Instant NOW = Instant.parse("2026-11-03T10:47:00Z");

    private FakeUpstream upstream;
    private InMemoryResources resources;
    private InMemoryImports imports;
    private GovernanceImport governanceImport;

    @BeforeEach
    void setUp() {
        upstream = new FakeUpstream();
        resources = new InMemoryResources();
        imports = new InMemoryImports();
        governanceImport = new GovernanceImport(upstream, resources, imports, GovernanceImportMetrics.NONE,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ListAppender<ILoggingEvent> logged;
    private Level levelBeforeTheTest;

    @BeforeEach
    void captureTheLog() {
        Logger logger = (Logger) LoggerFactory.getLogger(GovernanceImport.class);
        logged = new ListAppender<>();
        logged.start();
        levelBeforeTheTest = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(logged);
    }

    /** A logger is global: left in place, a capture would accumulate over every test that runs after this one. */
    @AfterEach
    void restoreTheLogger() {
        Logger logger = (Logger) LoggerFactory.getLogger(GovernanceImport.class);
        logger.detachAppender(logged);
        logger.setLevel(levelBeforeTheTest);
        logged.stop();
    }

    @Test
    void run_whenNoGovernanceServiceIsConfigured_thenNothingHappens() {
        upstream.configured = false;

        assertThat(governanceImport.run()).isFalse();
    }

    @Test
    void run_whenNothingIsStoredYet_thenEveryListedSystemAndRuleIsFetched() {
        upstream.systems(entry(GovernanceResourceKind.SYSTEM, "orders", "\"sha256:aaa\""),
                entry(GovernanceResourceKind.SYSTEM, "shipping", "\"sha256:bbb\""));
        upstream.rules(entry(GovernanceResourceKind.RULE, "naming-convention", "\"sha256:ccc\""));

        assertThat(governanceImport.run()).isTrue();

        // 2 systems + 1 rule + the systems index + the rules index.
        assertThat(resources.stored).hasSize(5);
        assertThat(resources.keysOf(GovernanceResourceKind.SYSTEM)).containsExactlyInAnyOrder("orders", "shipping");
        assertThat(resources.keysOf(GovernanceResourceKind.RULE)).containsExactly("naming-convention");
        assertThat(resources.find(GovernanceResourceKind.SYSTEMS_INDEX, null)).isPresent();
        assertThat(resources.find(GovernanceResourceKind.RULES_INDEX, null)).isPresent();
        assertThat(upstream.systemFetches).isEqualTo(2);
        assertThat(upstream.ruleFetches).isEqualTo(1);
    }

    @Test
    void run_whenTheIndexIsUnchanged_thenNothingIsFetchedButABuildIsStillAskedFor() {
        upstream.systems(entry(GovernanceResourceKind.SYSTEM, "orders", "\"sha256:aaa\""));
        upstream.rules();
        governanceImport.run();
        upstream.systemsIndexNotModified = true;
        upstream.rulesIndexNotModified = true;
        upstream.systemFetches = 0;

        // A request lost after an earlier import has to be repeated, and nothing else would repeat it.
        assertThat(governanceImport.run()).isTrue();

        assertThat(upstream.systemFetches).isZero();
        assertThat(upstream.lastKnownSystemsIndexEtag).isEqualTo("\"systems-index-1\"");
    }

    /**
     * The index changing is what asks for a build, even where every entry's own tag - and so every stored
     * resource - stayed exactly as it was: both indexes carry the scoring time, so the "as of" on the
     * overviews has to catch up with every run.
     */
    @Test
    void run_whenOnlyTheIndexItselfChanged_thenABuildIsStillAskedFor() {
        upstream.systems(entry(GovernanceResourceKind.SYSTEM, "orders", "\"sha256:aaa\""));
        upstream.rules();
        governanceImport.run();
        upstream.systemFetches = 0;
        upstream.systemsIndexVersion++; // a new index etag, the same entries

        assertThat(governanceImport.run()).isTrue();

        // The entry's own tag did not move, so its content is not fetched again - only confirmed.
        assertThat(upstream.systemFetches).isZero();
        assertThat(resources.confirmed).isEqualTo(1);
    }

    @Test
    void run_whenOneSystemsEntityTagMoved_thenOnlyThatSystemIsFetched() {
        upstream.systems(entry(GovernanceResourceKind.SYSTEM, "orders", "\"sha256:aaa\""),
                entry(GovernanceResourceKind.SYSTEM, "shipping", "\"sha256:bbb\""));
        upstream.rules();
        governanceImport.run();
        upstream.systemFetches = 0;
        upstream.systemsIndexVersion++;
        upstream.systems(entry(GovernanceResourceKind.SYSTEM, "orders", "\"sha256:ccc\""),
                entry(GovernanceResourceKind.SYSTEM, "shipping", "\"sha256:bbb\""));

        assertThat(governanceImport.run()).isTrue();

        assertThat(upstream.systemFetches).isEqualTo(1);
        assertThat(resources.find(GovernanceResourceKind.SYSTEM, "orders"))
                .get().extracting(GovernanceResource::etag).isEqualTo("\"sha256:ccc\"");
    }

    @Test
    void run_whenASystemIsNoLongerListed_thenItIsRemoved() {
        upstream.systems(entry(GovernanceResourceKind.SYSTEM, "orders", "\"sha256:aaa\""),
                entry(GovernanceResourceKind.SYSTEM, "shipping", "\"sha256:bbb\""));
        upstream.rules();
        governanceImport.run();
        upstream.systemsIndexVersion++;
        upstream.systems(entry(GovernanceResourceKind.SYSTEM, "orders", "\"sha256:aaa\""));

        governanceImport.run();

        assertThat(resources.keysOf(GovernanceResourceKind.SYSTEM)).containsExactly("orders");
    }

    @Test
    void run_whenARuleIsNoLongerListed_thenItIsRemoved() {
        upstream.systems();
        upstream.rules(entry(GovernanceResourceKind.RULE, "naming-convention", "\"sha256:aaa\""),
                entry(GovernanceResourceKind.RULE, "produces-metrics", "\"sha256:bbb\""));
        governanceImport.run();
        upstream.rulesIndexVersion++;
        upstream.rules(entry(GovernanceResourceKind.RULE, "naming-convention", "\"sha256:aaa\""));

        governanceImport.run();

        assertThat(resources.keysOf(GovernanceResourceKind.RULE)).containsExactly("naming-convention");
    }

    /**
     * An index that answers "not modified" says the data is unchanged, not that everything in it was fetched.
     * Trusting it after a run that could not replicate an entry would skip that entry for ever.
     */
    @Test
    void run_whenAnEntryCouldNotBeReplicated_thenTheIndexIsAskedUnconditionallyNextTime() {
        upstream.systems(entry(GovernanceResourceKind.SYSTEM, "orders", "\"sha256:aaa\""));
        upstream.rules();
        upstream.skipSystemFetch = true;

        governanceImport.run();

        assertThat(imports.state(GovernanceResourceKind.SYSTEM).complete()).isFalse();
        assertThat(imports.state(GovernanceResourceKind.SYSTEM).conditionalIndexEtag()).isNull();
    }

    /**
     * A skipped entry is no success: nothing is stored, the age of the last success is not reset and the count
     * is of what is stored, not of what the index lists.
     */
    @Test
    void run_whenAnEntryCouldNotBeReplicated_thenNothingIsStoredAndItIsNoSuccess() {
        upstream.systems(entry(GovernanceResourceKind.SYSTEM, "orders", "\"sha256:aaa\""));
        upstream.rules();
        upstream.skipSystemFetch = true;

        governanceImport.run();

        GovernanceImportState state = imports.state(GovernanceResourceKind.SYSTEM);
        assertThat(state.lastOutcome()).isEqualTo(GovernanceImportOutcome.PARTIAL);
        assertThat(state.lastSuccessAt()).isNull();
        assertThat(state.itemCount()).isZero();
        assertThat(resources.keysOf(GovernanceResourceKind.SYSTEMS_INDEX)).isEmpty();
    }

    /**
     * The index and what it lists are replaced together: a failing fetch must not leave the new index and the
     * removal of a system behind. What is stored is the last complete state, so a build of it is harmless - and
     * here the rules, which did not move, are complete.
     */
    @Test
    void run_whenAFetchFailsAfterTheIndexMoved_thenNothingOfTheRunIsStored() {
        upstream.systems(entry(GovernanceResourceKind.SYSTEM, "orders", "\"sha256:aaa\""),
                entry(GovernanceResourceKind.SYSTEM, "shipping", "\"sha256:bbb\""));
        upstream.rules();
        governanceImport.run();
        upstream.rulesIndexNotModified = true;
        upstream.systems(entry(GovernanceResourceKind.SYSTEM, "orders", "\"sha256:ccc\""));
        upstream.systemsIndexVersion++;
        upstream.failSystemFetch = true;

        governanceImport.run();

        assertThat(resources.keysOf(GovernanceResourceKind.SYSTEM)).containsExactly("orders", "shipping");
        assertThat(resources.find(GovernanceResourceKind.SYSTEM, "orders").orElseThrow().etag())
                .isEqualTo("\"sha256:aaa\"");
        assertThat(resources.index(GovernanceResourceKind.SYSTEMS_INDEX).etag())
                .isEqualTo("\"systems-index-1\"");
        assertThat(imports.state(GovernanceResourceKind.SYSTEM).lastOutcome())
                .isEqualTo(GovernanceImportOutcome.FAILED);
    }

    @Test
    void run_whenTheUpstreamFails_thenWhatIsStoredIsKeptAndTheOutcomeIsRecorded() {
        upstream.systems(entry(GovernanceResourceKind.SYSTEM, "orders", "\"sha256:aaa\""));
        upstream.rules();
        governanceImport.run();
        upstream.failSystemsIndex = true;
        upstream.rulesIndexNotModified = true;

        // The rules are complete, so a build is asked for; the systems are as they were.
        assertThat(governanceImport.run()).isTrue();

        assertThat(imports.state(GovernanceResourceKind.SYSTEM).lastOutcome())
                .isEqualTo(GovernanceImportOutcome.FAILED);
        // The last published governance site is what stays served: nothing a failure touches is deleted or
        // overwritten, and no build is asked for a run that changed nothing (see GovernanceImportJob).
        assertThat(resources.keysOf(GovernanceResourceKind.SYSTEM)).containsExactly("orders");
    }

    /**
     * ERROR, not the architecture import's WARN for the same shape of failure: the Governance Service is a
     * dependency this service alarms on. The message is the assertion - it has to name the
     * resource and the upstream's own answer, so the log line and the alert agree on what went wrong.
     */
    @Test
    void run_whenTheUpstreamFails_thenItIsLoggedAsAnErrorNamingTheResourceAndTheAnswer() {
        upstream.rules();
        upstream.failSystemsIndex = true;
        upstream.failureMessage = "GET /docs-api/systems at https://governance.example answered 503.";

        governanceImport.run();

        assertThat(logged.list)
                .filteredOn(event -> event.getLevel() == Level.ERROR)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message)
                        .contains("SYSTEM")
                        .contains("GET /docs-api/systems at https://governance.example answered 503."));
    }

    private static GovernanceResourceRef entry(GovernanceResourceKind kind, String key, String etag) {
        return new GovernanceResourceRef(kind, key, etag);
    }

    /** A systems and a rules index, each versioned so a test can move only the index's own tag. */
    private static final class FakeUpstream implements GovernanceUpstream {

        boolean configured = true;
        List<GovernanceResourceRef> systemEntries = List.of();
        List<GovernanceResourceRef> ruleEntries = List.of();
        int systemsIndexVersion = 1;
        int rulesIndexVersion = 1;
        boolean systemsIndexNotModified;
        boolean rulesIndexNotModified;
        boolean failSystemsIndex;
        String failureMessage = "the Governance Service is down";
        boolean skipSystemFetch;
        boolean failSystemFetch;
        int systemFetches;
        int ruleFetches;
        String lastKnownSystemsIndexEtag;

        void systems(GovernanceResourceRef... entries) {
            systemEntries = List.of(entries);
        }

        void rules(GovernanceResourceRef... entries) {
            ruleEntries = List.of(entries);
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public Optional<GovernanceIndexFetch> systemsIndex(String knownIndexEtag) {
            lastKnownSystemsIndexEtag = knownIndexEtag;
            if (failSystemsIndex) {
                throw new GovernanceUnavailableException(failureMessage);
            }
            if (systemsIndexNotModified) {
                return Optional.empty();
            }
            return Optional.of(new GovernanceIndexFetch("systems".getBytes(),
                    "\"systems-index-%d\"".formatted(systemsIndexVersion), systemEntries));
        }

        @Override
        public Optional<GovernanceIndexFetch> rulesIndex(String knownIndexEtag) {
            if (rulesIndexNotModified) {
                return Optional.empty();
            }
            return Optional.of(new GovernanceIndexFetch("rules".getBytes(),
                    "\"rules-index-%d\"".formatted(rulesIndexVersion), ruleEntries));
        }

        @Override
        public GovernanceResourceFetch system(String systemName, String knownEtag) {
            systemFetches++;
            if (failSystemFetch) {
                throw new GovernanceUnavailableException(failureMessage);
            }
            if (skipSystemFetch) {
                return GovernanceResourceFetch.skipped("test");
            }
            GovernanceResourceRef entry = systemEntries.stream()
                    .filter(e -> e.key().equals(systemName)).findFirst().orElseThrow();
            return GovernanceResourceFetch.stored(new GovernanceResource(GovernanceResourceKind.SYSTEM,
                    systemName, entry.etag(), "{}".getBytes(), 2, NOW));
        }

        @Override
        public GovernanceResourceFetch rule(String ruleId, String knownEtag) {
            ruleFetches++;
            GovernanceResourceRef entry = ruleEntries.stream()
                    .filter(e -> e.key().equals(ruleId)).findFirst().orElseThrow();
            return GovernanceResourceFetch.stored(new GovernanceResource(GovernanceResourceKind.RULE, ruleId,
                    entry.etag(), "{}".getBytes(), 2, NOW));
        }
    }

    private static final class InMemoryResources implements GovernanceResourceRepository {

        final Map<String, GovernanceResource> stored = new LinkedHashMap<>();
        int confirmed;

        @Override
        public List<GovernanceResourceRef> findRefs(GovernanceResourceKind kind) {
            return stored.values().stream()
                    .filter(resource -> resource.kind() == kind)
                    .map(resource -> new GovernanceResourceRef(resource.kind(), resource.key(), resource.etag()))
                    .toList();
        }

        @Override
        public Optional<GovernanceResource> find(GovernanceResourceKind kind, String key) {
            return Optional.ofNullable(stored.get(keyOf(kind, key)));
        }

        @Override
        public void store(GovernanceResource resource) {
            stored.put(keyOf(resource.kind(), resource.key()), resource);
        }

        @Override
        public <T> T readConsistently(java.util.function.Supplier<T> reads) {
            return reads.get();
        }

        @Override
        public void replace(GovernanceResource index, Collection<GovernanceResource> resources,
                            Collection<GovernanceResourceRef> removed) {
            remove(removed);
            resources.forEach(this::store);
            store(index);
        }

        GovernanceResource index(GovernanceResourceKind kind) {
            return stored.get(keyOf(kind, null));
        }

        @Override
        public void confirm(GovernanceResourceKind kind, String key, Instant checkedAt) {
            confirmed++;
        }

        @Override
        public void remove(Collection<GovernanceResourceRef> refs) {
            refs.forEach(ref -> stored.remove(keyOf(ref.kind(), ref.key())));
        }

        List<String> keysOf(GovernanceResourceKind kind) {
            return stored.values().stream()
                    .filter(resource -> resource.kind() == kind)
                    .map(GovernanceResource::key)
                    .sorted()
                    .toList();
        }

        private static String keyOf(GovernanceResourceKind kind, String key) {
            return kind + "/" + key;
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
