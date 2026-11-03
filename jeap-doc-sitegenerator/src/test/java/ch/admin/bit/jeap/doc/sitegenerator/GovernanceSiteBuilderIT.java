package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.BuildProperties;
import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.PartKey;
import ch.admin.bit.jeap.doc.domain.PublicationProperties;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SitePart;
import ch.admin.bit.jeap.doc.domain.SiteProperties;
import ch.admin.bit.jeap.doc.domain.SystemSitePartition;
import ch.admin.bit.jeap.doc.domain.content.GovernanceContent;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceComponent;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceDailyScore;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceRule;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceRuleExemption;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceRuleState;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceRuleSummary;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceRulesIndex;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceSystem;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceSystemSummary;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceSystemsIndex;
import ch.admin.bit.jeap.doc.domain.port.BuiltSite;
import ch.admin.bit.jeap.doc.domain.template.StructureTemplates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Builds the governance site with the real site generator: the pages {@link GovernanceContentWriter} writes
 * have to be ones the site template accepts, with its broken-link check on.
 * <p>
 * Like {@code DocusaurusSiteBuilderIT}, it needs Node and the installed site template dependencies. It looks at
 * the static HTML the build produces; a diagram is drawn by the reader's browser, so all that is checked for the
 * score history is that it is not shown as a code listing.
 */
class GovernanceSiteBuilderIT {

    private static final Instant GENERATED_AT = Instant.parse("2026-11-04T09:00:00Z");
    private static final String GOVERNANCE = "governance";

    @TempDir
    Path workspaceRoot;

    private final FakeGovernanceResources resources = new FakeGovernanceResources();
    private final FakeGovernanceContent content = new FakeGovernanceContent();

    private DocusaurusSiteBuilder builder;
    private Site site;

    @BeforeEach
    void setUp() {
        BuildProperties properties = new BuildProperties();
        properties.setWorkspaceDirectory(workspaceRoot);
        properties.setNodeModulesDirectory(Path.of("target/site-install/node_modules").toAbsolutePath());
        PublicationProperties publication = new PublicationProperties();
        publication.setUrl("https://doc.example.ch");

        SiteProperties configured = new SiteProperties();
        SiteProperties.Site governance = new SiteProperties.Site();
        governance.setTitle("Governance");
        governance.setPerEnvironment(false);
        governance.setContent(GovernanceContent.SOURCE_ID);
        configured.getSites().put(GOVERNANCE, governance);
        DocumentationSites sites = new DocumentationSites(configured);
        site = sites.find(GOVERNANCE).orElseThrow();

        SiteSources writing = new SiteSources(new SiteUrls(publication, "/docs"), new DefaultResourceLoader(),
                List.of(new GovernanceContentWriter(resources, content)), sites,
                new SystemSitePartition(NoArchitectureModel.INSTANCE, new NoCustomDocumentation()), properties,
                TestProvenance.of(configured, NoArchitectureModel.INSTANCE, new StructureTemplates(List.of())),
                new AboutThisDocumentation(), new PublicationProperties());
        builder = new DocusaurusSiteBuilder(properties, new BuildWorkspaces(properties), new SiteTemplate(),
                new NodeProcess(properties), writing);
        fillTheSite();
    }

    /**
     * Every kind of page the site has links to the others, and the site template fails the whole build on a link
     * that leads nowhere - so a build that succeeds proves the paths the writer links to are the paths it writes.
     * The site also lists a system and a rule that have no page, names an unknown rule on a component page, and
     * has a relative link in an explanation: none of them may become a link.
     */
    @Test
    void generate_thenEveryPageOfTheGovernanceSiteIsBuiltAndTheLinksBetweenThemResolve() throws Exception {
        BuiltSite built = builder.generate(builder.prepare(1, site, wholeSiteOf(site), GENERATED_AT));

        Path directory = built.directory();
        assertThat(directory.resolve("index.html")).isRegularFile();
        assertThat(directory.resolve("systems/orders/index.html")).isRegularFile();
        assertThat(directory.resolve("systems/orders/components/orders-payment-scs/index.html")).isRegularFile();
        assertThat(directory.resolve("rules/index.html")).isRegularFile();
        assertThat(directory.resolve("rules/naming-convention/index.html")).isRegularFile();
        assertThat(directory.resolve("rules/component-produces-metrics/index.html")).isRegularFile();
        String overview = read(directory.resolve("index.html"));
        assertThat(overview).contains("Governance").contains("orders").contains("73%").contains("shipping");
        assertThat(directory.resolve("systems/shipping")).doesNotExist();
        assertThat(directory.resolve("rules/unreadable-rule")).doesNotExist();
    }

    /** A fold is a closed {@code details} element: collapsed until a reader opens it. */
    @Test
    void generate_thenARuleWithExemptionsHasAFoldThatIsCollapsed() throws Exception {
        BuiltSite built = builder.generate(builder.prepare(2, site, wholeSiteOf(site), GENERATED_AT));

        String overview = read(built.directory().resolve("rules/index.html"));
        assertThat(overview).contains("Naming convention - 1 exemption").contains("Migration in progress");
        Matcher folds = Pattern.compile("<details[^>]*>").matcher(overview);
        int seen = 0;
        while (folds.find()) {
            seen++;
            assertThat(folds.group()).describedAs("a fold starts closed").doesNotContain(" open");
        }
        assertThat(seen).as("one fold, for the one rule with exemptions").isEqualTo(1);
    }

    /**
     * The score history is not a code listing: the template hands a Mermaid fence to the browser to draw, and the
     * HTML of the build has nothing of it but the place it goes. That the browser then draws it is checked by
     * {@code MermaidChartBrowserIT} in {@code jeap-doc-web}, not here, where there is no browser.
     */
    @Test
    void generate_thenTheScoreHistoryIsNotShownAsACodeListing() throws Exception {
        BuiltSite built = builder.generate(builder.prepare(3, site, wholeSiteOf(site), GENERATED_AT));

        String page = read(built.directory().resolve("systems/orders/index.html"));
        assertThat(page).contains("Score history").contains("2026-11-01").contains("68%");
        assertThat(page).doesNotContain("language-mermaid").doesNotContain("xychart-beta");
    }

    private void fillTheSite() {
        GovernanceSystemsIndex index = new GovernanceSystemsIndex(OffsetDateTime.parse("2026-11-04T08:45:00Z"), 30,
                List.of(new GovernanceSystemSummary("orders", 73, "DOWN"),
                        new GovernanceSystemSummary("shipping", 50, "STABLE")));
        content.index = index;
        resources.index(index);
        GovernanceRuleState state = new GovernanceRuleState("naming-convention", "Naming convention", "FAIL",
                "Not named after the system", List.of(), OffsetDateTime.parse("2026-11-01T06:00:00Z"));
        GovernanceComponent component = new GovernanceComponent("orders-payment-scs", 65, "UP", List.of(
                new GovernanceDailyScore(LocalDate.parse("2026-11-01"), 60),
                new GovernanceDailyScore(LocalDate.parse("2026-11-02"), 65)), List.of(state,
                new GovernanceRuleState("unknown-rule", "Unknown rule", "OK", null, List.of(), null)));
        content.systemsByKey.put("orders", new GovernanceSystem("orders", 73, "DOWN", List.of(
                new GovernanceDailyScore(LocalDate.parse("2026-11-01"), 68),
                new GovernanceDailyScore(LocalDate.parse("2026-11-02"), 73)), List.of(component)));

        content.rulesIndex = new GovernanceRulesIndex(OffsetDateTime.parse("2026-11-04T08:45:00Z"), List.of(
                new GovernanceRuleSummary("naming-convention", "Naming convention", true, 10, new BigDecimal("100.0"),
                        92, "STABLE", 1),
                new GovernanceRuleSummary("component-produces-metrics", "Component produces metrics", false, null,
                        null, null, "NO_DATA", null),
                new GovernanceRuleSummary("unreadable-rule", "Unreadable rule", true, 5, BigDecimal.TEN, 80,
                        "STABLE", 0)));
        resources.rulesIndex();
        GovernanceRuleExemption exemption = new GovernanceRuleExemption("exemption-1", "legacy-gateway",
                "Migration in progress", LocalDate.parse("2026-12-31"), "PAUSED", List.of());
        content.rulesByKey.put("naming-convention", new GovernanceRule("naming-convention", "Naming convention",
                true, "# Naming convention\n\nChecks the name, see [the guide](./guide.md).", 10,
                new BigDecimal("100.0"), null, false,
                List.of(), List.of(exemption), 92, "STABLE", List.of(), List.of(), List.of()));
        content.rulesByKey.put("component-produces-metrics", new GovernanceRule("component-produces-metrics",
                "Component produces metrics", false, "Checks for metrics.", null, null, null, false, List.of(),
                List.of(), null, "NO_DATA", List.of(), List.of(), List.of()));
    }

    private static String read(Path file) throws Exception {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static SitePart wholeSiteOf(Site site) {
        return new SitePart(PartKey.shellOf(site.id()), "the site itself", "", true, List.of(), List.of());
    }
}
