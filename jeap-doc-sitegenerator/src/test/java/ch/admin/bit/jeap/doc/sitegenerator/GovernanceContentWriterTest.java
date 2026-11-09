package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SitePart;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceComponent;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceDailyScore;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceGracePeriodComponent;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceNonCompliantComponent;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceResource;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceResourceKind;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceResourceRef;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceRule;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceRuleExemption;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceRuleParameter;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceRuleState;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceRuleSummary;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceRulesIndex;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceSystem;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceSystemConformance;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceSystemSummary;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceSystemsIndex;
import ch.admin.bit.jeap.doc.domain.port.GovernanceResourceContent;
import ch.admin.bit.jeap.doc.domain.port.GovernanceResourceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The governance site: the system scores overview at its root, a page per scored system, a page per component
 * and a page per rule.
 * <p>
 * {@link FakeGovernanceContent} stands in for the adapter that parses a stored resource's bytes - see {@code
 * GovernanceResourceContentAdapterTest} for that parsing itself - so these cases are about what the writer
 * does with the parsed shapes: the paths, the slugs, the escaping and the scoring time.
 */
class GovernanceContentWriterTest {

    private static final Instant GENERATED_AT = Instant.parse("2026-11-04T09:00:00Z");
    private static final Site SITE = new Site("governance", "Governance", null, null, null, "jeap", List.of(),
            false, false);

    @TempDir
    Path content;

    private final FakeGovernanceResources resources = new FakeGovernanceResources();
    private final FakeGovernanceContent fakeContent = new FakeGovernanceContent();
    private final GovernanceContentWriter writer = new GovernanceContentWriter(resources, fakeContent);

    @Test
    void write_whenNoIndexIsStored_thenTheOverviewSaysNoScoringRunHasCompleted() throws IOException {
        writer.write(SITE, shell(), content, GENERATED_AT);

        Path tree = content.resolve(SiteSources.SINGLE_TREE);
        assertThat(filesBelow(tree)).containsExactly("index.md");
        assertThat(Files.readString(tree.resolve("index.md")))
                .contains("No scoring run has completed yet.")
                .doesNotContain("](/systems/)");
    }

    @Test
    void write_thenTheOverviewListsEverySystemAndSaysWhenItWasScored() throws IOException {
        fakeContent.index = new GovernanceSystemsIndex(OffsetDateTime.parse("2026-11-04T08:45:00Z"), 30,
                List.of(new GovernanceSystemSummary("orders", 73, "DOWN")));
        resources.index(fakeContent.index);
        fakeContent.systemsByKey.put("orders", systemOf("orders", 73, "DOWN"));

        writer.write(SITE, shell(), content, GENERATED_AT);

        assertThat(Files.readString(content.resolve(SiteSources.SINGLE_TREE).resolve("index.md")))
                .contains("[Systems](/systems/) lists the score and trend of every system");

        String overview = Files.readString(content.resolve(SiteSources.SINGLE_TREE).resolve("systems/index.md"));
        assertThat(overview).contains("slug: \"/systems/\"")
                .contains("2026-11-04 ")
                .contains("[orders](/systems/orders/)")
                .contains("73%").contains("Down");
    }

    @Test
    void write_thenTheOverviewListsTheBestScoredSystemFirst() throws IOException {
        fakeContent.index = new GovernanceSystemsIndex(null, 30, List.of(
                new GovernanceSystemSummary("alpha", 40, "STABLE"),
                new GovernanceSystemSummary("beta", 90, "STABLE"),
                new GovernanceSystemSummary("gamma", null, "STABLE"),
                new GovernanceSystemSummary("delta", 65, "STABLE")));
        resources.index(fakeContent.index);
        for (GovernanceSystemSummary system : fakeContent.index.systems()) {
            fakeContent.systemsByKey.put(system.name(), systemOf(system.name(), system.score(), "STABLE"));
        }

        writer.write(SITE, shell(), content, GENERATED_AT);

        String overview = Files.readString(content.resolve(SiteSources.SINGLE_TREE).resolve("systems/index.md"));
        assertThat(overview.indexOf("[beta]")).isPositive()
                .isLessThan(overview.indexOf("[delta]"));
        assertThat(overview.indexOf("[delta]")).isLessThan(overview.indexOf("[alpha]"));
        assertThat(overview.indexOf("[alpha]")).isLessThan(overview.indexOf("gamma"));
    }

    /** A name that is not already a slug still has to be documented, the same rule the architecture import follows. */
    @Test
    void write_whenASystemNameNeedsSlugging_thenItsPageIsAtTheSluggedPath() throws IOException {
        fakeContent.index = new GovernanceSystemsIndex(null, 30,
                List.of(new GovernanceSystemSummary("Order Fulfilment", 80, "STABLE")));
        resources.index(fakeContent.index);
        fakeContent.systemsByKey.put("Order Fulfilment", systemOf("Order Fulfilment", 80, "STABLE"));

        writer.write(SITE, shell(), content, GENERATED_AT);

        Path tree = content.resolve(SiteSources.SINGLE_TREE);
        assertThat(filesBelow(tree)).contains("systems/order-fulfilment/index.md");
        assertThat(Files.readString(tree.resolve("systems/index.md")))
                .contains("[Order Fulfilment](/systems/order-fulfilment/)");
        assertThat(Files.readString(tree.resolve("systems/order-fulfilment/index.md")))
                .contains("slug: \"/systems/order-fulfilment/\"")
                .contains("title: \"Order Fulfilment\"");
    }

    /** Two names that fold to the same slug must not have one overwrite the other's page. */
    @Test
    void write_whenTwoSystemNamesFoldToTheSameSlug_thenOnlyTheFirstIsWritten() throws IOException {
        fakeContent.index = new GovernanceSystemsIndex(null, 30, List.of(
                new GovernanceSystemSummary("Orders", 70, "STABLE"),
                new GovernanceSystemSummary("orders", 71, "STABLE")));
        resources.index(fakeContent.index);
        fakeContent.systemsByKey.put("Orders", systemOf("Orders", 70, "STABLE"));
        fakeContent.systemsByKey.put("orders", systemOf("orders", 71, "STABLE"));

        writer.write(SITE, shell(), content, GENERATED_AT);

        Path tree = content.resolve(SiteSources.SINGLE_TREE);
        assertThat(filesBelow(tree)).containsExactlyInAnyOrder("index.md", "systems/_category_.json",
                "systems/index.md", "systems/orders/_category_.json", "systems/orders/index.md");
        assertThat(Files.readString(tree.resolve("systems/orders/index.md"))).contains("title: \"Orders\"");
    }

    /**
     * A system the index lists but whose own resource could not be read is listed without a link, not failed:
     * the site build fails on a link to a page that does not exist.
     */
    @Test
    void write_whenASystemsOwnResourceIsMissing_thenItIsListedWithoutALinkAndItsPageIsNotWritten()
            throws IOException {
        fakeContent.index = new GovernanceSystemsIndex(null, 30,
                List.of(new GovernanceSystemSummary("orders", 70, "STABLE")));
        resources.index(fakeContent.index);
        // Deliberately not added to fakeContent.systemsByKey - the resource is "missing".

        writer.write(SITE, shell(), content, GENERATED_AT);

        Path tree = content.resolve(SiteSources.SINGLE_TREE);
        assertThat(Files.readString(tree.resolve("systems/index.md"))).contains("| orders | 🟡 70% | → Stable |");
        assertThat(filesBelow(tree)).containsExactlyInAnyOrder("index.md", "systems/_category_.json", "systems/index.md");
    }

    @Test
    void write_thenASystemPageListsItsComponentsAndLinksToTheirOwnPages() throws IOException {
        fakeContent.index = new GovernanceSystemsIndex(null, 30,
                List.of(new GovernanceSystemSummary("orders", 70, "STABLE")));
        resources.index(fakeContent.index);
        GovernanceComponent component = new GovernanceComponent("orders-payment-scs", 65, "UP", List.of(),
                List.of());
        fakeContent.systemsByKey.put("orders",
                new GovernanceSystem("orders", 70, "STABLE", List.of(), List.of(component)));

        writer.write(SITE, shell(), content, GENERATED_AT);

        Path tree = content.resolve(SiteSources.SINGLE_TREE);
        assertThat(filesBelow(tree)).contains("systems/orders/orders-payment-scs/index.md",
                "systems/orders/_category_.json");
        assertThat(Files.readString(tree.resolve("systems/orders/index.md")))
                .contains("[orders-payment-scs](/systems/orders/orders-payment-scs/)");
        String componentPage = Files.readString(
                tree.resolve("systems/orders/orders-payment-scs/index.md"));
        assertThat(componentPage).contains("slug: \"/systems/orders/orders-payment-scs/\"")
                .contains("65%").contains("Up");
    }

    @Test
    void write_thenASystemPageListsTheBestScoredComponentFirst() throws IOException {
        fakeContent.index = new GovernanceSystemsIndex(null, 30,
                List.of(new GovernanceSystemSummary("orders", 70, "STABLE")));
        resources.index(fakeContent.index);
        fakeContent.systemsByKey.put("orders", new GovernanceSystem("orders", 70, "STABLE", List.of(), List.of(
                new GovernanceComponent("low", 40, "UP", List.of(), List.of()),
                new GovernanceComponent("unscored", null, "UP", List.of(), List.of()),
                new GovernanceComponent("high", 95, "UP", List.of(), List.of()),
                new GovernanceComponent("middle", 70, "UP", List.of(), List.of()))));

        writer.write(SITE, shell(), content, GENERATED_AT);

        String page = Files.readString(content.resolve(SiteSources.SINGLE_TREE).resolve("systems/orders/index.md"));
        assertThat(page.indexOf("[high]")).isPositive().isLessThan(page.indexOf("[middle]"));
        assertThat(page.indexOf("[middle]")).isLessThan(page.indexOf("[low]"));
        assertThat(page.indexOf("[low]")).isLessThan(page.indexOf("[unscored]"));
    }

    @Test
    void write_thenAComponentPageListsItsRuleStatesLinkingToTheRulePage() throws IOException {
        fakeContent.index = new GovernanceSystemsIndex(null, 30,
                List.of(new GovernanceSystemSummary("orders", 70, "STABLE")));
        resources.index(fakeContent.index);
        setRulesIndex("naming-convention");
        fakeContent.rulesByKey.put("naming-convention", activeRuleWithNoFindings("naming-convention", false));
        GovernanceRuleState failing = new GovernanceRuleState("naming-convention", "Naming convention", "FAIL",
                "Not named after the system", List.of("orders-payment does not start with orders-", "second finding"),
                OffsetDateTime.parse("2026-11-01T06:00:00Z"));
        GovernanceComponent component = new GovernanceComponent("orders-payment-scs", 65, "UP", List.of(),
                List.of(failing));
        fakeContent.systemsByKey.put("orders",
                new GovernanceSystem("orders", 70, "STABLE", List.of(), List.of(component)));

        writer.write(SITE, shell(), content, GENERATED_AT);

        String componentPage = Files.readString(content.resolve(SiteSources.SINGLE_TREE)
                .resolve("systems/orders/orders-payment-scs/index.md"));
        assertThat(componentPage).contains("[Naming convention](/rules/naming-convention/)")
                .contains("Non-compliant").contains("Not named after the system")
                .contains("| Not named after the system :list[:item[orders-payment does not start with orders-]"
                        + ":item[second finding]] |");
    }

    /**
     * A rule state naming a rule that has no page - unknown to the rules index, unreadable, or with no rule ID
     * at all - shows the rule's name without a link, since the site build fails on a link to nowhere.
     */
    @Test
    void write_whenARuleStateNamesARuleWithoutAPage_thenItIsShownWithoutALink() throws IOException {
        fakeContent.index = new GovernanceSystemsIndex(null, 30,
                List.of(new GovernanceSystemSummary("orders", 70, "STABLE")));
        resources.index(fakeContent.index);
        setRulesIndex("unreadable-rule");
        GovernanceComponent component = new GovernanceComponent("orders-payment-scs", 65, "UP", List.of(),
                List.of(new GovernanceRuleState("unknown-rule", "Unknown rule", "OK", null, List.of(), null),
                        new GovernanceRuleState("unreadable-rule", "Unreadable rule", "OK", null, List.of(),
                                null),
                        new GovernanceRuleState(null, "Rule without an ID", "OK", null, List.of(), null)));
        fakeContent.systemsByKey.put("orders",
                new GovernanceSystem("orders", 70, "STABLE", List.of(), List.of(component)));

        writer.write(SITE, shell(), content, GENERATED_AT);

        String componentPage = Files.readString(content.resolve(SiteSources.SINGLE_TREE)
                .resolve("systems/orders/orders-payment-scs/index.md"));
        assertThat(componentPage).contains("| Unknown rule | ✅ Compliant | - |")
                .contains("| Unreadable rule | ✅ Compliant | - |")
                .contains("| Rule without an ID | ✅ Compliant | - |");
    }

    @Test
    void write_thenTheScoreHistoryIsNotRepeatedAsATable() throws IOException {
        fakeContent.index = new GovernanceSystemsIndex(null, 30,
                List.of(new GovernanceSystemSummary("orders", 70, "STABLE")));
        resources.index(fakeContent.index);
        List<GovernanceDailyScore> history = List.of(
                new GovernanceDailyScore(LocalDate.parse("2026-11-01"), 68),
                new GovernanceDailyScore(LocalDate.parse("2026-11-02"), 70));
        fakeContent.systemsByKey.put("orders", new GovernanceSystem("orders", 70, "STABLE", history, List.of()));

        writer.write(SITE, shell(), content, GENERATED_AT);

        String systemPage = Files.readString(
                content.resolve(SiteSources.SINGLE_TREE).resolve("systems/orders/index.md"));
        assertThat(systemPage).contains("Score history").doesNotContain("| Day | Score |");
    }

    /** A system page's score history has a chart before the table of the same values, not after it. */
    @Test
    void write_thenASystemsScoreHistoryIsAMermaidXyLineChart() throws IOException {
        fakeContent.index = new GovernanceSystemsIndex(null, 30,
                List.of(new GovernanceSystemSummary("orders", 70, "STABLE")));
        resources.index(fakeContent.index);
        List<GovernanceDailyScore> history = List.of(
                new GovernanceDailyScore(LocalDate.parse("2026-11-01"), 68),
                new GovernanceDailyScore(LocalDate.parse("2026-11-02"), 70));
        fakeContent.systemsByKey.put("orders", new GovernanceSystem("orders", 70, "STABLE", history, List.of()));

        writer.write(SITE, shell(), content, GENERATED_AT);

        String systemPage = Files.readString(
                content.resolve(SiteSources.SINGLE_TREE).resolve("systems/orders/index.md"));
        assertThat(systemPage).contains("```mermaid").contains("xychart-beta")
                .contains("x-axis [\"2026-11-01\", \"2026-11-02\"]")
                .contains("y-axis \"Score (%)\" 0 --> 100")
                .contains("line [68, 70]");
        assertThat(systemPage).contains("## Overall").doesNotContain("| Day | Score |");
    }

    /** The same chart, on a component page - {@code writeHistory} is the one place both pages write it. */
    @Test
    void write_thenAComponentsScoreHistoryIsAMermaidXyLineChartToo() throws IOException {
        fakeContent.index = new GovernanceSystemsIndex(null, 30,
                List.of(new GovernanceSystemSummary("orders", 70, "STABLE")));
        resources.index(fakeContent.index);
        List<GovernanceDailyScore> history = List.of(
                new GovernanceDailyScore(LocalDate.parse("2026-11-01"), 60),
                new GovernanceDailyScore(LocalDate.parse("2026-11-02"), 65));
        GovernanceComponent component = new GovernanceComponent("orders-payment-scs", 65, "UP", history,
                List.of());
        fakeContent.systemsByKey.put("orders",
                new GovernanceSystem("orders", 70, "STABLE", List.of(), List.of(component)));

        writer.write(SITE, shell(), content, GENERATED_AT);

        String componentPage = Files.readString(content.resolve(SiteSources.SINGLE_TREE)
                .resolve("systems/orders/orders-payment-scs/index.md"));
        assertThat(componentPage).contains("```mermaid").contains("xychart-beta")
                .contains("x-axis [\"2026-11-01\", \"2026-11-02\"]")
                .contains("line [60, 65]");
    }

    /**
     * A description or a name from outside is escaped like every other imported text - see {@code Md.text} -
     * so a Markdown control character in a system's name cannot change the structure of the page.
     */
    @Test
    void write_whenASystemNameContainsMarkdownCharacters_thenItIsEscapedOnThePage() throws IOException {
        fakeContent.index = new GovernanceSystemsIndex(null, 30,
                List.of(new GovernanceSystemSummary("Orders *and* [Shipping]", 70, "STABLE")));
        resources.index(fakeContent.index);
        fakeContent.systemsByKey.put("Orders *and* [Shipping]",
                systemOf("Orders *and* [Shipping]", 70, "STABLE"));

        writer.write(SITE, shell(), content, GENERATED_AT);

        String overview = Files.readString(content.resolve(SiteSources.SINGLE_TREE).resolve("systems/index.md"));
        assertThat(overview).contains("Orders \\*and\\* \\[Shipping\\]");
    }

    /** The marker before a score or a rate changes exactly at 50 and at 80, never a step early or late. */
    @ParameterizedTest
    @CsvSource({
            "49, 🔴", // red
            "50, 🟡", // yellow
            "79, 🟡", // yellow
            "80, 🟢", // green
    })
    void write_thenASystemsScoreCarriesTheMarkerOfItsThreshold(int score, String marker) throws IOException {
        fakeContent.index = new GovernanceSystemsIndex(null, 30,
                List.of(new GovernanceSystemSummary("orders", score, "STABLE")));
        resources.index(fakeContent.index);
        fakeContent.systemsByKey.put("orders", systemOf("orders", score, "STABLE"));

        writer.write(SITE, shell(), content, GENERATED_AT);

        String overview = Files.readString(content.resolve(SiteSources.SINGLE_TREE).resolve("systems/index.md"));
        assertThat(overview).contains(marker + " " + score + "%");
    }

    @ParameterizedTest
    @CsvSource({
            "OK, Compliant",
            "FAIL, Non-compliant",
            "PAUSED, Paused",
            "DISABLED, Disabled",
            "SOMETHING_UNKNOWN, Unknown",
    })
    void write_thenARuleStateCarriesItsOwnMarkerAndLabel(String state, String label) throws IOException {
        fakeContent.index = new GovernanceSystemsIndex(null, 30,
                List.of(new GovernanceSystemSummary("orders", 70, "STABLE")));
        resources.index(fakeContent.index);
        GovernanceRuleState ruleState = new GovernanceRuleState("naming-convention", "Naming convention",
                state, null, List.of(), OffsetDateTime.parse("2026-11-01T06:00:00Z"));
        GovernanceComponent component = new GovernanceComponent("orders-payment-scs", 65, "UP", List.of(),
                List.of(ruleState));
        fakeContent.systemsByKey.put("orders",
                new GovernanceSystem("orders", 70, "STABLE", List.of(), List.of(component)));

        writer.write(SITE, shell(), content, GENERATED_AT);

        String componentPage = Files.readString(content.resolve(SiteSources.SINGLE_TREE)
                .resolve("systems/orders/orders-payment-scs/index.md"));
        assertThat(componentPage).contains(label);
    }

    @Test
    void write_thenATrendCarriesAnArrowBeforeItsWord() throws IOException {
        fakeContent.index = new GovernanceSystemsIndex(null, 30,
                List.of(new GovernanceSystemSummary("orders", 70, "UP")));
        resources.index(fakeContent.index);
        fakeContent.systemsByKey.put("orders", systemOf("orders", 70, "UP"));

        writer.write(SITE, shell(), content, GENERATED_AT);

        String overview = Files.readString(content.resolve(SiteSources.SINGLE_TREE).resolve("systems/index.md"));
        assertThat(overview).contains("↑ Up");
    }

    @Test
    void write_thenAnActiveRulesPageShowsItsIdWeightDelayParametersAndConformance() throws IOException {
        setRulesIndex("naming-convention");
        GovernanceRule rule = new GovernanceRule("naming-convention", "Naming convention", true,
                "# Naming convention\n\nChecks the name.", 10, new BigDecimal("50.0"), Duration.ofHours(168),
                false, List.of(new GovernanceRuleParameter("ignored-service-names", "legacy-gateway")), List.of(),
                92, "STABLE", List.of(new GovernanceSystemConformance("orders", 95)),
                List.of(new GovernanceNonCompliantComponent("orders", "orders-legacy",
                        LocalDate.parse("2026-03-03"))),
                List.of());
        fakeContent.rulesByKey.put("naming-convention", rule);

        writer.write(SITE, shell(), content, GENERATED_AT);

        Path tree = content.resolve(SiteSources.SINGLE_TREE);
        assertThat(filesBelow(tree)).contains("rules/naming-convention/index.md");
        String page = Files.readString(tree.resolve("rules/naming-convention/index.md"));
        assertThat(page).contains("slug: \"/rules/naming-convention/\"")
                .contains("title: \"Naming convention\"")
                .contains("Rule ID: `naming-convention`")
                .contains("Checks the name.")
                .contains("10").contains("7 days")
                .contains("`ignored-service-names`").contains("legacy-gateway")
                .contains("92%")
                .contains("orders").contains("95%")
                .contains("orders-legacy").contains("2026-03-03");
        // The page's own title is its one top-level heading; the explanation's moves below it.
        assertThat(page.lines().filter(line -> line.startsWith("# ")))
                .containsExactly("# Naming convention");
        assertThat(page.lines()).contains("## Naming convention");
        assertThat(page).contains(":::details[Explanation]");
        assertThat(page.indexOf(":::details[Explanation]")).isLessThan(page.indexOf("## Conformance"));
    }

    @Test
    void write_whenARuleIsInactive_thenItsPageShowsOnlyItsIdAndExplanation() throws IOException {
        setRulesIndex("component-produces-metrics");
        GovernanceRule rule = new GovernanceRule("component-produces-metrics", "Component produces metrics",
                false, "# Component produces metrics\n\nChecks for metrics.", null, null, null, false, List.of(),
                List.of(), null, "NO_DATA", List.of(), List.of(), List.of());
        fakeContent.rulesByKey.put("component-produces-metrics", rule);

        writer.write(SITE, shell(), content, GENERATED_AT);

        String page = Files.readString(content.resolve(SiteSources.SINGLE_TREE)
                .resolve("rules/component-produces-metrics/index.md"));
        assertThat(page).contains("Checks for metrics.")
                .doesNotContain("Weight").doesNotContain("Conformance").doesNotContain("Parameters");
    }

    @Test
    void write_whenARuleHasNoExplanation_thenThePageSaysSo() throws IOException {
        setRulesIndex("a-custom-rule");
        GovernanceRule rule = new GovernanceRule("a-custom-rule", "A custom rule", true, null, 5,
                new BigDecimal("25.0"), null, false, List.of(), List.of(), 100, "STABLE", List.of(), List.of(),
                List.of());
        fakeContent.rulesByKey.put("a-custom-rule", rule);

        writer.write(SITE, shell(), content, GENERATED_AT);

        String page = Files.readString(
                content.resolve(SiteSources.SINGLE_TREE).resolve("rules/a-custom-rule/index.md"));
        assertThat(page).contains("No explanation is available for this rule.");
    }

    /**
     * The explanation is a Markdown document authored outside this service; its own structure renders, but a
     * raw HTML tag inside it must not become an element of the page.
     */
    @Test
    void write_whenAnExplanationContainsRawHtml_thenItIsShownAsText() throws IOException {
        setRulesIndex("a-custom-rule");
        GovernanceRule rule = new GovernanceRule("a-custom-rule", "A custom rule", true,
                "# A custom rule\n\nLeftover from Confluence: <script>alert('x')</script> and a <br/>.", 5,
                new BigDecimal("25.0"), null, false, List.of(), List.of(), 100, "STABLE", List.of(), List.of(),
                List.of());
        fakeContent.rulesByKey.put("a-custom-rule", rule);

        writer.write(SITE, shell(), content, GENERATED_AT);

        String page = Files.readString(
                content.resolve(SiteSources.SINGLE_TREE).resolve("rules/a-custom-rule/index.md"));
        assertThat(page).contains("&lt;script>alert('x')&lt;/script>").contains("&lt;br/>")
                .doesNotContain("<script>").doesNotContain("<br/>");
    }

    @Test
    void write_whenARuleHasAGracePeriodConfiguredButNobodyIsInIt_thenThePageSaysSo() throws IOException {
        setRulesIndex("a-rule-with-grace-period");
        GovernanceRule rule = activeRuleWithNoFindings("a-rule-with-grace-period", true);
        fakeContent.rulesByKey.put("a-rule-with-grace-period", rule);

        writer.write(SITE, shell(), content, GENERATED_AT);

        String page = Files.readString(
                content.resolve(SiteSources.SINGLE_TREE).resolve("rules/a-rule-with-grace-period/index.md"));
        assertThat(page).contains("Violation grace period")
                .contains("No component is currently within the grace period.");
    }

    @Test
    void write_whenARuleHasNoGracePeriodConfigured_thenThePageShowsNoGracePeriodSection() throws IOException {
        setRulesIndex("a-rule-without-grace-period");
        GovernanceRule rule = activeRuleWithNoFindings("a-rule-without-grace-period", false);
        fakeContent.rulesByKey.put("a-rule-without-grace-period", rule);

        writer.write(SITE, shell(), content, GENERATED_AT);

        String page = Files.readString(
                content.resolve(SiteSources.SINGLE_TREE).resolve("rules/a-rule-without-grace-period/index.md"));
        assertThat(page).doesNotContain("Violation grace period")
                .doesNotContain("Non-compliant components");
    }

    @Test
    void write_whenAComponentIsWithinTheGracePeriod_thenThePageListsItWithDetectionAndEnd() throws IOException {
        setRulesIndex("a-rule-with-grace-period");
        GovernanceGracePeriodComponent component = new GovernanceGracePeriodComponent("orders",
                "orders-legacy", "Migration planned", List.of("tracked in JEAP-1", "owner: team a"),
                OffsetDateTime.parse("2026-10-20T06:00:00Z"), OffsetDateTime.parse("2026-11-10T06:00:00Z"));
        GovernanceRule rule = new GovernanceRule("a-rule-with-grace-period", "A rule with a grace period", true,
                "Explanation.", 5, new BigDecimal("25.0"), null, true, List.of(), List.of(), 100, "STABLE",
                List.of(), List.of(), List.of(component));
        fakeContent.rulesByKey.put("a-rule-with-grace-period", rule);

        writer.write(SITE, shell(), content, GENERATED_AT);

        String page = Files.readString(
                content.resolve(SiteSources.SINGLE_TREE).resolve("rules/a-rule-with-grace-period/index.md"));
        assertThat(page).contains("orders-legacy").contains("Migration planned")
                .contains("| Migration planned :list[:item[tracked in JEAP-1]:item[owner: team a]] |")
                .contains("2026-10-20").contains("2026-11-10");
    }

    /** A rule the rules index lists but whose own resource could not be read is listed without a link, not failed. */
    @Test
    void write_whenARulesOwnResourceIsMissing_thenItIsListedWithoutALinkAndHasNoPage() throws IOException {
        setRulesIndex("naming-convention");
        // Deliberately not added to fakeContent.rulesByKey - the resource is "missing".

        writer.write(SITE, shell(), content, GENERATED_AT);

        Path tree = content.resolve(SiteSources.SINGLE_TREE);
        assertThat(filesBelow(tree)).containsExactlyInAnyOrder("index.md", "rules/_category_.json",
                "rules/index.md");
        assertThat(Files.readString(tree.resolve("rules/index.md")))
                .contains("| naming-convention | Active | 5 | 10% | 🟢 100% | → Stable | 0 |");
    }

    /** A rule ID is used in a path only once it is a slug, so one reading {@code ..} cannot leave the tree. */
    @Test
    void write_whenARuleIdYieldsNoSlug_thenItHasNoPageAndIsListedWithoutALink() throws IOException {
        setRulesIndex("..");
        fakeContent.rulesByKey.put("..", activeRuleWithNoFindings("..", false));

        writer.write(SITE, shell(), content, GENERATED_AT);

        Path tree = content.resolve(SiteSources.SINGLE_TREE);
        assertThat(filesBelow(tree)).containsExactlyInAnyOrder("index.md", "rules/_category_.json",
                "rules/index.md");
        assertThat(Files.readString(tree.resolve("index.md"))).contains("No scoring run has completed yet.");
        assertThat(Files.readString(tree.resolve("rules/index.md"))).contains("| .. | Active |");
    }

    /** One value the upstream left out must cost a cell, not the build of the whole site. */
    @Test
    void write_whenTheUpstreamLeftDatesOut_thenThoseCellsAreEmptyAndTheRestIsWritten() throws IOException {
        fakeContent.index = new GovernanceSystemsIndex(null, 30,
                List.of(new GovernanceSystemSummary("orders", 70, "STABLE")));
        resources.index(fakeContent.index);
        fakeContent.systemsByKey.put("orders", new GovernanceSystem("orders", 70, "STABLE", List.of(
                new GovernanceDailyScore(null, 50),
                new GovernanceDailyScore(LocalDate.parse("2026-11-02"), 70)), List.of()));
        setRulesIndex("naming-convention");
        fakeContent.rulesByKey.put("naming-convention", new GovernanceRule("naming-convention", null, true,
                "Explanation.", 5, new BigDecimal("25.0"), null, true, List.of(), List.of(), 100, "STABLE",
                List.of(), List.of(new GovernanceNonCompliantComponent("orders", "orders-legacy", null)),
                List.of(new GovernanceGracePeriodComponent("orders", "orders-new", "Planned", List.of(), null,
                        null))));

        writer.write(SITE, shell(), content, GENERATED_AT);

        Path tree = content.resolve(SiteSources.SINGLE_TREE);
        assertThat(Files.readString(tree.resolve("systems/orders/index.md")))
                .contains("x-axis [\"2026-11-02\"]").contains("line [70]").doesNotContain("null");
        assertThat(Files.readString(tree.resolve("rules/naming-convention/index.md")))
                .contains("title: \"naming-convention\"")
                .contains("| [orders](/systems/orders/) | orders-legacy | - |")
                .contains("| [orders](/systems/orders/) | orders-new | Planned | - | - |");
    }

    /**
     * A rule page links each system and component it names to its page - in the conformance by system, the
     * non-compliant components and the grace period alike - and names one without a page as text.
     */
    @Test
    void write_thenARulePageLinksTheSystemsAndComponentsThatHaveAPage() throws IOException {
        fakeContent.index = new GovernanceSystemsIndex(null, 30,
                List.of(new GovernanceSystemSummary("orders", 70, "STABLE")));
        resources.index(fakeContent.index);
        GovernanceComponent legacy = new GovernanceComponent("orders-legacy", 40, "DOWN", List.of(), List.of());
        fakeContent.systemsByKey.put("orders",
                new GovernanceSystem("orders", 70, "STABLE", List.of(), List.of(legacy)));
        setRulesIndex("naming-convention");
        fakeContent.rulesByKey.put("naming-convention", new GovernanceRule("naming-convention", null, true,
                "Explanation.", 5, new BigDecimal("25.0"), null, true, List.of(), List.of(), 80, "STABLE",
                List.of(new GovernanceSystemConformance("orders", 50),
                        new GovernanceSystemConformance("shipping", 90)),
                List.of(new GovernanceNonCompliantComponent("orders", "orders-legacy", null),
                        new GovernanceNonCompliantComponent("orders", "orders-unscored", null),
                        new GovernanceNonCompliantComponent("shipping", "orders-legacy", null)),
                List.of(new GovernanceGracePeriodComponent("orders", "orders-legacy", "Planned", List.of(), null,
                        null))));

        writer.write(SITE, shell(), content, GENERATED_AT);

        String page = Files.readString(
                content.resolve(SiteSources.SINGLE_TREE).resolve("rules/naming-convention/index.md"));
        String orders = "[orders](/systems/orders/)";
        String legacyLink = "[orders-legacy](/systems/orders/orders-legacy/)";
        assertThat(page).contains("| " + orders + " | 🟡 50% |")
                .contains("| shipping | 🟢 90% |")
                .contains("| " + orders + " | " + legacyLink + " | - |")
                .contains("| " + orders + " | orders-unscored | - |")
                .contains("| shipping | orders-legacy | - |")
                .contains("| " + orders + " | " + legacyLink + " | Planned | - | - |");
    }

    @Test
    void write_thenTheRulesOverviewListsActiveRulesFirstByWeightThenInactiveRules() throws IOException {
        fakeContent.rulesIndex = new GovernanceRulesIndex(null, List.of(
                new GovernanceRuleSummary("light-rule", "Light rule", true, 10, new BigDecimal("33.3"), 80,
                        "STABLE", 0),
                new GovernanceRuleSummary("heavy-rule", "Heavy rule", true, 20, new BigDecimal("66.7"), 90,
                        "UP", 1),
                new GovernanceRuleSummary("inactive-rule", "Inactive rule", false, null, null, null,
                        "NO_DATA", null)));
        resources.rulesIndex();
        fakeContent.rulesByKey.put("light-rule", activeRuleWithNoFindings("light-rule", false));
        fakeContent.rulesByKey.put("heavy-rule", activeRuleWithNoFindings("heavy-rule", false));
        fakeContent.rulesByKey.put("inactive-rule", new GovernanceRule("inactive-rule", "Inactive rule", false,
                "Explanation.", null, null, null, false, List.of(), List.of(), null, "NO_DATA", List.of(),
                List.of(), List.of()));

        writer.write(SITE, shell(), content, GENERATED_AT);

        String overview = Files.readString(
                content.resolve(SiteSources.SINGLE_TREE).resolve("rules/index.md"));
        assertThat(overview).contains("slug: \"/rules/\"").contains("title: \"Rules\"")
                .contains("90%").contains("80%").contains("66.7%").contains("33.3%")
                .contains("Active").contains("Inactive");
        int heavy = overview.indexOf("Heavy rule");
        int light = overview.indexOf("Light rule");
        int inactive = overview.indexOf("Inactive rule");
        assertThat(heavy).isGreaterThanOrEqualTo(0).isLessThan(light);
        assertThat(light).isLessThan(inactive);
    }

    @Test
    void write_thenTheRulesOverviewNotesTheShareIsNominal() throws IOException {
        setRulesIndex("naming-convention");
        fakeContent.rulesByKey.put("naming-convention", activeRuleWithNoFindings("naming-convention", false));

        writer.write(SITE, shell(), content, GENERATED_AT);

        String overview = Files.readString(
                content.resolve(SiteSources.SINGLE_TREE).resolve("rules/index.md"));
        assertThat(overview).contains("nominal");
    }

    @Test
    void write_whenARuleHasExemptions_thenTheOverviewShowsACollapsedFoldListingThem() throws IOException {
        setRulesIndex("naming-convention");
        GovernanceRuleExemption exemption = new GovernanceRuleExemption("exemption-1", "legacy-gateway",
                "Migration in progress", LocalDate.parse("2026-12-31"), "PAUSED", List.of());
        GovernanceRule rule = new GovernanceRule("naming-convention", "Naming convention", true,
                "Explanation.", 10, BigDecimal.TEN, null, false, List.of(), List.of(exemption), 92, "STABLE",
                List.of(), List.of(), List.of());
        fakeContent.rulesByKey.put("naming-convention", rule);

        writer.write(SITE, shell(), content, GENERATED_AT);

        String overview = Files.readString(
                content.resolve(SiteSources.SINGLE_TREE).resolve("rules/index.md"));
        assertThat(overview).contains(":::details[Naming convention - 1 exemption]")
                .contains("legacy-gateway").contains("Migration in progress").contains("2026-12-31")
                .contains("Paused");
    }

    @Test
    void write_whenARuleHasNoExemptions_thenTheOverviewShowsNoFoldForIt() throws IOException {
        setRulesIndex("naming-convention");
        fakeContent.rulesByKey.put("naming-convention", activeRuleWithNoFindings("naming-convention", false));

        writer.write(SITE, shell(), content, GENERATED_AT);

        String overview = Files.readString(
                content.resolve(SiteSources.SINGLE_TREE).resolve("rules/index.md"));
        assertThat(overview).doesNotContain(":::details");
    }

    /** The Governance Service lets one component exemption name several rules; each carries its own copy. */
    @Test
    void write_whenOneExemptionNamesTwoRules_thenItIsListedUnderBoth() throws IOException {
        fakeContent.rulesIndex = new GovernanceRulesIndex(null, List.of(
                new GovernanceRuleSummary("rule-a", "Rule A", true, 10, BigDecimal.TEN, 90, "STABLE", 1),
                new GovernanceRuleSummary("rule-b", "Rule B", true, 10, BigDecimal.TEN, 90, "STABLE", 1)));
        resources.rulesIndex();
        GovernanceRuleExemption exemption = new GovernanceRuleExemption("exemption-shared", "legacy-gateway",
                "Migration in progress", LocalDate.parse("2026-12-31"), "PAUSED", List.of());
        fakeContent.rulesByKey.put("rule-a", new GovernanceRule("rule-a", "Rule A", true, "Explanation.", 10,
                BigDecimal.TEN, null, false, List.of(), List.of(exemption), 90, "STABLE", List.of(), List.of(),
                List.of()));
        fakeContent.rulesByKey.put("rule-b", new GovernanceRule("rule-b", "Rule B", true, "Explanation.", 10,
                BigDecimal.TEN, null, false, List.of(), List.of(exemption), 90, "STABLE", List.of(), List.of(),
                List.of()));

        writer.write(SITE, shell(), content, GENERATED_AT);

        String overview = Files.readString(
                content.resolve(SiteSources.SINGLE_TREE).resolve("rules/index.md"));
        assertThat(overview).contains(":::details[Rule A - 1 exemption]")
                .contains(":::details[Rule B - 1 exemption]");
        assertThat(countOccurrences(overview, "legacy-gateway")).isEqualTo(2);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) != -1) {
            count++;
            from += needle.length();
        }
        return count;
    }

    private static GovernanceRule activeRuleWithNoFindings(String ruleId, boolean gracePeriodConfigured) {
        return new GovernanceRule(ruleId, "A rule", true, "Explanation.", 5, new BigDecimal("25.0"), null,
                gracePeriodConfigured, List.of(), List.of(), 100, "STABLE", List.of(), List.of(), List.of());
    }

    /** Lists each rule ID in the rules index, with values that are not under test in any of these cases. */
    private void setRulesIndex(String... ruleIds) {
        List<GovernanceRuleSummary> summaries = new ArrayList<>();
        for (String ruleId : ruleIds) {
            summaries.add(new GovernanceRuleSummary(ruleId, ruleId, true, 5, BigDecimal.TEN, 100, "STABLE", 0));
        }
        fakeContent.rulesIndex = new GovernanceRulesIndex(null, summaries);
        resources.rulesIndex();
    }

    private static GovernanceSystem systemOf(String name, Integer score, String trend) {
        return new GovernanceSystem(name, score, trend, List.of(), List.of());
    }

    private static SitePart shell() {
        return SitePart.wholeSiteOf(SITE);
    }

    private static List<String> filesBelow(Path tree) throws IOException {
        try (Stream<Path> files = Files.walk(tree)) {
            return files.filter(Files::isRegularFile)
                    .map(file -> tree.relativize(file).toString().replace('\\', '/'))
                    .toList();
        }
    }
}
