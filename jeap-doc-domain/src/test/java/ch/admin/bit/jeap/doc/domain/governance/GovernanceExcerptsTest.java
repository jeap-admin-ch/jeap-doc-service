package ch.admin.bit.jeap.doc.domain.governance;

import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.GovernanceExcerptProperties;
import ch.admin.bit.jeap.doc.domain.SiteProperties;
import ch.admin.bit.jeap.doc.domain.content.GovernanceContent;
import ch.admin.bit.jeap.doc.domain.port.GovernanceResourceContent;
import ch.admin.bit.jeap.doc.domain.port.GovernanceResourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GovernanceExcerptsTest {

    private static final OffsetDateTime SCORED_AT = OffsetDateTime.parse("2026-10-22T06:45:21Z");
    private static final Clock ZURICH = Clock.fixed(Instant.parse("2026-10-22T07:00:00Z"), ZoneId.of("Europe/Zurich"));
    private static final LocalDate TODAY = LocalDate.parse("2026-10-22");

    private final InMemoryResources resources = new InMemoryResources();
    private final ParsedContent content = new ParsedContent();
    private GovernanceExcerpts excerpts;

    @BeforeEach
    void setUp() {
        excerpts = excerptsWithSites(Map.of("governance", governanceSite()));
    }

    @Test
    void ofSystem_whenScored_thenTheSystemsScoreAndItsComponentsWithLinksIntoTheGovernanceSite() {
        storeSystemsIndex("Inspecziun", "Orders");
        storeRulesIndex(rule("signed-messages", "Component Consumes Signed Messages"),
                rule("dependencies-current", "Dependencies are kept current"));
        storeRule("signed-messages");
        storeRule("dependencies-current");
        storeSystem(new GovernanceSystem("Inspecziun", 86, "STABLE", history(20), List.of(
                new GovernanceComponent("inspecziun-borderpanel-scs", 78, "DOWN", history(3), List.of(
                        state("signed-messages", "FAIL", "Signature is not enforced for consumption",
                                List.of("topic a", "topic b")),
                        state("dependencies-current", "OK", null, List.of()),
                        state("paused-rule", "PAUSED", null, List.of()),
                        state("disabled-rule", "DISABLED", null, List.of()),
                        state("unknown-rule", "UNKNOWN", null, List.of()))))));

        GovernanceExcerpt excerpt = excerpts.ofSystem("Inspecziun").orElseThrow();

        assertThat(excerpt.system()).isEqualTo("Inspecziun");
        assertThat(excerpt.link()).isEqualTo("/site/governance/systems/inspecziun/");
        assertThat(excerpt.rulesOverview()).isEqualTo("/site/governance/rules/");
        assertThat(excerpt.scoredAt()).isEqualTo(OffsetDateTime.parse("2026-10-22T08:45:21+02:00"));
        assertThat(excerpt.scoredAt().getOffset()).isEqualTo(ZoneOffset.ofHours(2));
        assertThat(excerpt.score()).isEqualTo(86);
        assertThat(excerpt.trend()).isEqualTo("STABLE");

        GovernanceExcerpt.Component component = excerpt.components().getFirst();
        assertThat(component.name()).isEqualTo("inspecziun-borderpanel-scs");
        assertThat(component.link()).isEqualTo("/site/governance/systems/inspecziun/inspecziun-borderpanel-scs/");
        assertThat(component.score()).isEqualTo(78);
        assertThat(component.trend()).isEqualTo("DOWN");
        assertThat(component.compliant()).isEqualTo(1);
        assertThat(component.pausedOrDisabled()).isEqualTo(2);
        assertThat(component.failingRules()).containsExactly(new GovernanceExcerpt.FailingRule(
                "signed-messages", "Component Consumes Signed Messages",
                "Signature is not enforced for consumption", List.of("topic a", "topic b"),
                OffsetDateTime.parse("2026-10-20T08:00:00+02:00"), "/site/governance/rules/signed-messages/"));
    }

    @Test
    void ofSystem_whenTheHistoryIsLonger_thenTheLastFourteenDaysOldestFirst() {
        storeSystemsIndex("Orders");
        storeSystem(new GovernanceSystem("Orders", 50, "UP", history(30), List.of()));

        List<GovernanceDailyScore> history = excerpts.ofSystem("Orders").orElseThrow().history();

        assertThat(history).hasSize(14);
        assertThat(history.getFirst().day()).isEqualTo(TODAY.minusDays(13));
        assertThat(history.getLast().day()).isEqualTo(TODAY);
    }

    @Test
    void ofSystem_whenTheNameDiffersInCase_thenTheScoredSystemIsFound() {
        storeSystemsIndex("NES");
        storeSystem(new GovernanceSystem("NES", 90, "STABLE", List.of(), List.of()));

        assertThat(excerpts.ofSystem("nes")).map(GovernanceExcerpt::system).contains("NES");
    }

    @Test
    void ofSystem_whenNothingIsKnown_thenEmpty() {
        assertThat(excerpts.ofSystem("Orders")).isEmpty();

        storeSystemsIndex("Orders");
        assertThat(excerpts.ofSystem("Unknown")).isEmpty();
        assertThat(excerpts.ofSystem("Orders")).as("listed, but its resource is not stored").isEmpty();
        assertThat(excerpts.ofSystem(" ")).isEmpty();
    }

    @Test
    void ofSystem_whenNoGovernanceSiteIsConfigured_thenEmpty() {
        storeSystemsIndex("Orders");
        storeSystem(new GovernanceSystem("Orders", 50, "UP", List.of(), List.of()));

        GovernanceExcerpts withoutSite = excerptsWithSites(Map.of());

        assertThat(withoutSite.ofSystem("Orders")).isEmpty();
    }

    @Test
    void ofSystem_whenTheSiteHasNoPageForIt_thenNoLink() {
        // "Orders" and "orders" share a slug, so the second one gets no page - and its components none either.
        storeSystemsIndex("Orders", "orders");
        storeSystem(new GovernanceSystem("orders", 50, "UP", List.of(), List.of(
                new GovernanceComponent("a", 50, "UP", List.of(), List.of(
                        state("unstored-rule", "FAIL", null, List.of()),
                        state("unlisted-rule", "FAIL", null, List.of()))))));
        storeRulesIndex(rule("unstored-rule", "Not stored"));

        GovernanceExcerpt excerpt = excerpts.ofSystem("orders").orElseThrow();

        assertThat(excerpt.link()).isNull();
        GovernanceExcerpt.Component component = excerpt.components().getFirst();
        assertThat(component.link()).isNull();
        assertThat(component.failingRules()).extracting(GovernanceExcerpt.FailingRule::link).containsOnlyNulls();
        assertThat(component.failingRules()).extracting(GovernanceExcerpt.FailingRule::label)
                .containsExactly("Not stored", "unlisted-rule");
    }

    @Test
    void ofSystem_whenNoRuleIsKnown_thenNoRulesOverview() {
        storeSystemsIndex("Orders");
        storeSystem(new GovernanceSystem("Orders", 50, "UP", List.of(), List.of()));

        assertThat(excerpts.ofSystem("Orders").orElseThrow().rulesOverview()).isNull();
    }

    @Test
    void ofSystem_whenTheGovernanceSiteIsTheDefaultOne_thenLinksStartAtTheRoot() {
        excerpts = excerptsWithSites(Map.of("default", governanceSite()));
        storeSystemsIndex("Orders");
        storeSystem(new GovernanceSystem("Orders", 50, "UP", List.of(), List.of()));

        assertThat(excerpts.ofSystem("Orders").orElseThrow().link()).isEqualTo("/systems/orders/");
    }

    @Test
    void whenSeveralGovernanceSitesAndNoneIsNamed_thenTheStartupFails() {
        Map<String, SiteProperties.Site> sites = twoGovernanceSites();

        assertThatThrownBy(() -> excerptsWithSites(sites, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[governance, reports]")
                .hasMessageContaining("jeap.doc.governance.excerpts.site");
    }

    @Test
    void whenSeveralGovernanceSitesAndOneIsNamed_thenLinksLeadIntoThatOne() {
        excerpts = excerptsWithSites(twoGovernanceSites(), "reports");
        storeSystemsIndex("Orders");
        storeSystem(new GovernanceSystem("Orders", 50, "UP", List.of(), List.of()));

        assertThat(excerpts.ofSystem("Orders").orElseThrow().link()).isEqualTo("/site/reports/systems/orders/");
    }

    @Test
    void whenTheNamedSiteIsNoGovernanceSite_thenTheStartupFails() {
        Map<String, SiteProperties.Site> sites = Map.of("governance", governanceSite());

        assertThatThrownBy(() -> excerptsWithSites(sites, "reports"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'reports'")
                .hasMessageContaining("[governance]");
    }

    private static Map<String, SiteProperties.Site> twoGovernanceSites() {
        Map<String, SiteProperties.Site> sites = new LinkedHashMap<>();
        sites.put("governance", governanceSite());
        sites.put("reports", governanceSite());
        return sites;
    }

    private GovernanceExcerpts excerptsWithSites(Map<String, SiteProperties.Site> sites) {
        return excerptsWithSites(sites, null);
    }

    private GovernanceExcerpts excerptsWithSites(Map<String, SiteProperties.Site> sites, String named) {
        SiteProperties properties = new SiteProperties();
        properties.setSites(new LinkedHashMap<>(sites));
        GovernanceExcerptProperties excerptProperties = new GovernanceExcerptProperties();
        excerptProperties.setSite(named);
        return new GovernanceExcerpts(resources, content, new DocumentationSites(properties), excerptProperties,
                ZURICH);
    }

    private static SiteProperties.Site governanceSite() {
        SiteProperties.Site site = new SiteProperties.Site();
        site.setTitle("Governance");
        site.setPerEnvironment(false);
        site.setContent(GovernanceContent.SOURCE_ID);
        return site;
    }

    private static List<GovernanceDailyScore> history(int days) {
        return IntStream.range(0, days)
                .mapToObj(back -> new GovernanceDailyScore(TODAY.minusDays(back), 50 + back))
                .toList().reversed();
    }

    private static GovernanceRuleState state(String ruleId, String state, String comment, List<String> details) {
        return new GovernanceRuleState(ruleId, null, state, comment, details,
                OffsetDateTime.parse("2026-10-20T06:00:00Z"));
    }

    private static GovernanceRuleSummary rule(String ruleId, String name) {
        return new GovernanceRuleSummary(ruleId, name, true, 1, null, null, null, 0);
    }

    private void storeSystemsIndex(String... names) {
        List<GovernanceSystemSummary> systems = new ArrayList<>();
        for (String name : names) {
            systems.add(new GovernanceSystemSummary(name, null, null));
        }
        store(GovernanceResourceKind.SYSTEMS_INDEX, null, new GovernanceSystemsIndex(SCORED_AT, 30, systems));
    }

    private void storeRulesIndex(GovernanceRuleSummary... rules) {
        store(GovernanceResourceKind.RULES_INDEX, null, new GovernanceRulesIndex(SCORED_AT, List.of(rules)));
    }

    private void storeRule(String ruleId) {
        store(GovernanceResourceKind.RULE, ruleId, ruleId);
    }

    private void storeSystem(GovernanceSystem system) {
        store(GovernanceResourceKind.SYSTEM, system.name(), system);
    }

    private void store(GovernanceResourceKind kind, String key, Object parsed) {
        byte[] bytes = (kind + "/" + key).getBytes(StandardCharsets.UTF_8);
        content.parsed.put(new String(bytes, StandardCharsets.UTF_8), parsed);
        resources.store(new GovernanceResource(kind, key, "\"" + kind + key + "\"", bytes, bytes.length,
                Instant.EPOCH));
    }

    /** Hands back whatever was stored under the bytes, which name it - the parsing is the adapter's to test. */
    private static final class ParsedContent implements GovernanceResourceContent {

        final Map<String, Object> parsed = new HashMap<>();

        @Override
        public Optional<GovernanceSystemsIndex> systemsIndex(byte[] content) {
            return of(content, GovernanceSystemsIndex.class);
        }

        @Override
        public Optional<GovernanceSystem> system(byte[] content) {
            return of(content, GovernanceSystem.class);
        }

        @Override
        public Optional<GovernanceRulesIndex> rulesIndex(byte[] content) {
            return of(content, GovernanceRulesIndex.class);
        }

        @Override
        public Optional<GovernanceRule> rule(byte[] content) {
            return Optional.empty();
        }

        private <T> Optional<T> of(byte[] content, Class<T> type) {
            Object value = parsed.get(new String(content, StandardCharsets.UTF_8));
            return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
        }
    }

    private static final class InMemoryResources implements GovernanceResourceRepository {

        final Map<String, GovernanceResource> stored = new LinkedHashMap<>();

        @Override
        public List<GovernanceResourceRef> findRefs(GovernanceResourceKind kind) {
            return stored.values().stream()
                    .filter(resource -> resource.kind() == kind)
                    .map(resource -> new GovernanceResourceRef(resource.kind(), resource.key(), resource.etag()))
                    .toList();
        }

        @Override
        public Optional<GovernanceResource> find(GovernanceResourceKind kind, String key) {
            return Optional.ofNullable(stored.get(kind + "/" + key));
        }

        @Override
        public void store(GovernanceResource resource) {
            stored.put(resource.kind() + "/" + resource.key(), resource);
        }

        @Override
        public <T> T readConsistently(Supplier<T> reads) {
            return reads.get();
        }

        @Override
        public void replace(GovernanceResource index, Collection<GovernanceResource> resources,
                            Collection<GovernanceResourceRef> removed) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void confirm(GovernanceResourceKind kind, String key, Instant checkedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void remove(Collection<GovernanceResourceRef> resources) {
            throw new UnsupportedOperationException();
        }
    }
}
