package ch.admin.bit.jeap.doc.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentationSitesTest {

    @Test
    void find_whenNothingConfigured_thenTheDefaultSiteWithTheDefaultEnvironments() {
        DocumentationSites sites = new DocumentationSites(new SiteProperties());

        assertThat(sites.ids()).containsExactly(Site.DEFAULT_SITE);
        Site site = sites.find(Site.DEFAULT_SITE).orElseThrow();
        // Not "default": that is the site every instance gets without configuring anything, and the title ends
        // up in the navbar and the browser tab.
        assertThat(site.title()).isEqualTo("Documentation");
        assertThat(site.environments()).extracting(SiteEnvironment::id).containsExactly("dev", "ref", "abn", "prod");
        assertThat(site.mainEnvironment().id()).isEqualTo("prod");
        assertThat(site.latestEnvironment().id()).isEqualTo("dev");
        assertThat(site.schedule()).contains("0 5 6-20 * * *");
        assertThat(site.publishOnUpload()).isTrue();
    }

    @Test
    void find_whenANamedSiteConfiguresNoTitle_thenItIsCalledAfterItsId() {
        DocumentationSites sites = new DocumentationSites(properties(Map.of("governance", site(configured -> {
        }))));

        assertThat(sites.find("governance").orElseThrow().title()).isEqualTo("governance");
    }

    @Test
    void find_whenSiteUnknown_thenEmpty() {
        assertThat(new DocumentationSites(new SiteProperties()).find("governance")).isEmpty();
    }

    @Test
    void routePrefix_thenTheDefaultSiteOwnsTheRootAndTheOthersLiveBelowTheSiteSegment() {
        DocumentationSites sites = new DocumentationSites(properties(Map.of(
                Site.DEFAULT_SITE, site(builder -> {
                }),
                "governance", site(builder -> {
                }))));

        assertThat(sites.find(Site.DEFAULT_SITE).orElseThrow().routePrefix()).isEmpty();
        assertThat(sites.find("governance").orElseThrow().routePrefix()).isEqualTo("/site/governance");
    }

    @Test
    void mainEnvironment_thenServedAtTheRootAndTheOthersBehindTheirPrefix() {
        Site site = new DocumentationSites(new SiteProperties()).find(Site.DEFAULT_SITE).orElseThrow();

        assertThat(site.mainEnvironment().routePrefix()).isEmpty();
        assertThat(site.environments().stream().filter(environment -> !environment.main()))
                .extracting(SiteEnvironment::routePrefix)
                .containsExactly("/dev", "/ref", "/abn");
    }

    /**
     * A site named after an environment of the default site used to fail the startup, because both were served
     * under the same top-level segment. Below {@code /site/} they are two namespaces, and the two coexist.
     */
    @Test
    void construct_whenSiteNamedAfterAnEnvironmentOfTheDefaultSite_thenBothAreServed() {
        SiteProperties properties = properties(Map.of(
                Site.DEFAULT_SITE, site(configured -> {
                }),
                "dev", site(configured -> {
                })));

        DocumentationSites sites = new DocumentationSites(properties);

        assertThat(sites.find("dev").orElseThrow().routePrefix()).isEqualTo("/site/dev");
        assertThat(sites.find(Site.DEFAULT_SITE).orElseThrow().environments())
                .extracting(SiteEnvironment::routePrefix).contains("/dev");
    }

    @Test
    void construct_whenSiteIdIsNoSlug_thenFailsTheStartup() {
        SiteProperties properties = properties(Map.of("Governance Docs", site(configured -> {
        })));

        assertThatThrownBy(() -> new DocumentationSites(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is not a slug");
    }

    /**
     * Which schemes exist is not decided here: the site generator checks a configured name against the
     * stylesheets the template really ships, while the service starts. What is decided here is that there is a
     * name at all - a site with none would reach the template as a blank filename.
     */
    @Test
    void construct_whenNoColorSchemeIsConfigured_thenFailsTheStartup() {
        SiteProperties properties = properties(Map.of(Site.DEFAULT_SITE,
                site(configured -> configured.setColorScheme("  "))));

        assertThatThrownBy(() -> new DocumentationSites(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("colour scheme");
    }

    @Test
    void construct_whenAColorSchemeIsConfigured_thenItIsCarriedThroughUnchanged() {
        SiteProperties properties = properties(Map.of(Site.DEFAULT_SITE,
                site(configured -> configured.setColorScheme("corporate"))));

        assertThat(new DocumentationSites(properties).find(Site.DEFAULT_SITE))
                .get().extracting(Site::colorScheme).isEqualTo("corporate");
    }

    @Test
    void construct_whenNoEnvironmentIsMain_thenFailsTheStartup() {
        SiteProperties properties = properties(Map.of(Site.DEFAULT_SITE, site(configured ->
                configured.setEnvironments(List.of(environment("dev", false, true))))));

        assertThatThrownBy(() -> new DocumentationSites(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("marks 0 environments as 'main'");
    }

    @Test
    void construct_whenTwoEnvironmentsAreLatest_thenFailsTheStartup() {
        SiteProperties properties = properties(Map.of(Site.DEFAULT_SITE, site(configured ->
                configured.setEnvironments(List.of(
                        environment("dev", false, true),
                        environment("ref", false, true),
                        environment("prod", true, false))))));

        assertThatThrownBy(() -> new DocumentationSites(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("marks 2 environments as 'latest'");
    }

    @Test
    void construct_whenOneEnvironmentIsBothMainAndLatest_thenAccepted() {
        SiteProperties properties = properties(Map.of("governance", site(configured ->
                configured.setEnvironments(List.of(environment("prod", true, true))))));

        Site site = new DocumentationSites(properties).find("governance").orElseThrow();

        assertThat(site.mainEnvironment().id()).isEqualTo("prod");
        assertThat(site.latestEnvironment().id()).isEqualTo("prod");
    }

    @Test
    void construct_whenEnvironmentsUnordered_thenSortedByOrder() {
        SiteProperties properties = properties(Map.of("governance", site(configured -> {
            SiteProperties.Environment prod = environment("prod", true, false);
            prod.setOrder(9);
            SiteProperties.Environment dev = environment("dev", false, true);
            dev.setOrder(1);
            configured.setEnvironments(List.of(prod, dev));
        })));

        assertThat(new DocumentationSites(properties).find("governance").orElseThrow().environments())
                .extracting(SiteEnvironment::id).containsExactly("dev", "prod");
    }

    @Test
    void construct_whenNoScheduleConfigured_thenTheSiteIsPublishedOnUploadOnly() {
        SiteProperties properties = properties(Map.of("governance",
                site(configured -> configured.setPublicationSchedule(null))));

        assertThat(new DocumentationSites(properties).find("governance").orElseThrow().schedule()).isEmpty();
    }

    @Test
    void construct_whenNoFaviconConfigured_thenTheLogoIsUsed() {
        SiteProperties properties = properties(Map.of("governance",
                site(configured -> configured.setLogo("classpath:/branding/governance.svg"))));

        Site site = new DocumentationSites(properties).find("governance").orElseThrow();
        assertThat(site.favicon()).isEqualTo("classpath:/branding/governance.svg");
    }

    /**
     * A site is served below {@code /site/}, so nothing it is called can be matched by the API's security chain
     * or disappear behind the management endpoints. These names all used to fail the startup.
     */
    @ParameterizedTest
    @ValueSource(strings = {"api", "actuator", "swagger-ui", "api-docs", "webjars", "error", "assets", "img",
            "site", "default-site", "dev", "prod"})
    void construct_whenTheSiteIsNamedAfterAPathTheServiceAnswersOn_thenItIsAccepted(String id) {
        SiteProperties properties = properties(Map.of(id, site(configured -> {
        })));

        assertThat(new DocumentationSites(properties).find(id).orElseThrow().routePrefix())
                .isEqualTo("/site/" + id);
    }

    /**
     * The environments of the default site are what still takes a top-level segment each, so it is there that
     * the paths the service answers on itself are unusable.
     */
    @ParameterizedTest
    @ValueSource(strings = {"api", "actuator", "assets"})
    void construct_whenAnEnvironmentOfTheDefaultSiteIsNamedAfterAPathTheServiceAnswersOn_thenFailsTheStartup(
            String id) {
        SiteProperties properties = properties(Map.of(Site.DEFAULT_SITE, site(configured ->
                configured.setEnvironments(List.of(environment(id, true, true))))));

        assertThatThrownBy(() -> new DocumentationSites(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(id);
    }

    /**
     * The one reservation the {@code /site/} layout costs: the default site's environment of that name would
     * fight every other site for the segment.
     */
    @Test
    void construct_whenAnEnvironmentOfTheDefaultSiteIsCalledSite_thenFailsTheStartup() {
        SiteProperties properties = properties(Map.of(Site.DEFAULT_SITE, site(configured ->
                configured.setEnvironments(List.of(environment(Site.SITE_SEGMENT, true, true))))));

        assertThatThrownBy(() -> new DocumentationSites(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("every other")
                .hasMessageContaining("/site/");
    }

    /**
     * And the environments of any other site are below {@code /site/<id>/}, where none of those names means
     * anything.
     */
    @ParameterizedTest
    @ValueSource(strings = {"api", "actuator", "site"})
    void construct_whenAnEnvironmentOfANamedSiteIsNamedAfterAPathTheServiceAnswersOn_thenItIsAccepted(
            String id) {
        SiteProperties properties = properties(Map.of("governance", site(configured ->
                configured.setEnvironments(List.of(environment(id, true, true))))));

        assertThat(new DocumentationSites(properties).find("governance").orElseThrow().environments())
                .extracting(SiteEnvironment::id).containsExactly(id);
    }

    /**
     * `default` is the id the site generator's own documentation instance uses, so an environment named after
     * it fails every build of that site on a duplicate plugin id - minutes into a run, not at startup.
     */
    @Test
    void construct_whenAnEnvironmentIsCalledDefault_thenFailsTheStartup() {
        SiteProperties properties = properties(Map.of(Site.DEFAULT_SITE, site(configured ->
                configured.setEnvironments(List.of(environment(Site.DEFAULT_SITE, true, true))))));

        assertThatThrownBy(() -> new DocumentationSites(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("site generator");
    }

    /**
     * `<system>-api` is a natural name for a docs site, and the doc service pins the header skip patterns so
     * that a segment ending in `-api` is not treated as the REST API.
     */
    @Test
    void construct_whenTheSiteNameEndsInApi_thenItIsAccepted() {
        SiteProperties properties = properties(Map.of("orders-api", site(configured -> {
        })));

        assertThat(new DocumentationSites(properties).ids()).contains("orders-api");
    }

    /**
     * A site's build lock is named after it, and the lock table's name column holds 64 characters. Without this
     * the service starts and then fails every build of that site on an insert - the one configuration error
     * this module would otherwise not catch at startup.
     */
    @Test
    void construct_whenTheSiteIdIsTooLongForItsLockName_thenFailsTheStartup() {
        String tooLong = "a".repeat(DocumentationSites.MAX_SITE_ID_LENGTH + 1);
        SiteProperties properties = properties(Map.of(tooLong, site(configured -> {
        })));

        assertThatThrownBy(() -> new DocumentationSites(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lock");
    }

    @Test
    void construct_whenTheSiteIdIsExactlyAsLongAsItMayBe_thenItIsAccepted() {
        String longest = "a".repeat(DocumentationSites.MAX_SITE_ID_LENGTH);

        assertThat(new DocumentationSites(properties(Map.of(longest, site(configured -> {
        })))).ids()).contains(longest);
    }

    /**
     * `static` is one of the site generator's own static directories: an environment named after it would have
     * its Markdown copied verbatim to the site root instead of being rendered.
     */
    @ParameterizedTest
    @ValueSource(strings = {"static", "default"})
    void construct_whenAnEnvironmentIsNamedAfterSomethingTheGeneratorOwns_thenFailsTheStartup(String id) {
        SiteProperties properties = properties(Map.of(Site.DEFAULT_SITE, site(configured ->
                configured.setEnvironments(List.of(environment(id, true, true))))));

        assertThatThrownBy(() -> new DocumentationSites(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(id);
    }

    /**
     * The order sites were configured in survives, so the startup line and the message that lists them read the
     * same on every instance and on every restart.
     */
    @Test
    void all_thenTheSitesComeBackInTheOrderTheyWereConfigured() {
        SiteProperties properties = new SiteProperties();
        java.util.LinkedHashMap<String, SiteProperties.Site> configured = new java.util.LinkedHashMap<>();
        configured.put("zulu", site(site -> {
        }));
        configured.put("alpha", site(site -> {
        }));
        configured.put("mike", site(site -> {
        }));
        properties.setSites(configured);

        DocumentationSites sites = new DocumentationSites(properties);

        assertThat(sites.ids()).containsExactly("zulu", "alpha", "mike");
        assertThat(sites.all()).extracting(Site::id).containsExactly("zulu", "alpha", "mike");
    }

    private static SiteProperties properties(Map<String, SiteProperties.Site> sites) {
        SiteProperties properties = new SiteProperties();
        properties.setSites(sites);
        return properties;
    }

    private static SiteProperties.Site site(java.util.function.Consumer<SiteProperties.Site> customizer) {
        SiteProperties.Site site = new SiteProperties.Site();
        customizer.accept(site);
        return site;
    }

    private static SiteProperties.Environment environment(String id, boolean main, boolean latest) {
        SiteProperties.Environment environment = new SiteProperties.Environment();
        environment.setId(id);
        environment.setMain(main);
        environment.setLatest(latest);
        return environment;
    }
}
