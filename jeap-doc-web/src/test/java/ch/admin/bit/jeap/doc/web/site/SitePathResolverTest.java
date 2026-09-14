package ch.admin.bit.jeap.doc.web.site;

import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SiteProperties;
import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SitePathResolverTest {

    private final SitePathResolver singleSite = new SitePathResolver(new DocumentationSites(new SiteProperties()));

    private final SitePathResolver severalSites =
            new SitePathResolver(new DocumentationSites(properties(Site.DEFAULT_SITE, "governance")));

    @Test
    void resolve_whenTheRoot_thenTheDefaultSitesIndex() {
        assertThat(singleSite.resolve("/")).get().satisfies(path -> {
            assertThat(path.site().id()).isEqualTo(Site.DEFAULT_SITE);
            assertThat(path.file()).isEqualTo("index.html");
        });
    }

    @Test
    void resolve_whenADirectory_thenItsIndex() {
        assertThat(singleSite.resolve("/dev/systems/orders/")).get()
                .extracting(SitePath::file).isEqualTo("dev/systems/orders/index.html");
    }

    @Test
    void resolve_whenAFile_thenThatFile() {
        assertThat(singleSite.resolve("/assets/js/main.abc123.js")).get()
                .extracting(SitePath::file).isEqualTo("assets/js/main.abc123.js");
    }

    /**
     * The environments of the default site are served under their own top-level segment, and they are not
     * sites - so a path that starts with one belongs to the default site.
     */
    @Test
    void resolve_whenTheFirstSegmentIsAnEnvironment_thenTheDefaultSiteServesIt() {
        assertThat(severalSites.resolve("/dev/")).get().satisfies(path -> {
            assertThat(path.site().id()).isEqualTo(Site.DEFAULT_SITE);
            assertThat(path.file()).isEqualTo("dev/index.html");
        });
    }

    @Test
    void resolve_whenBelowTheSiteSegment_thenThatSiteServesTheRest() {
        assertThat(severalSites.resolve("/site/governance/dev/systems/")).get().satisfies(path -> {
            assertThat(path.site().id()).isEqualTo("governance");
            assertThat(path.file()).isEqualTo("dev/systems/index.html");
        });
    }

    @Test
    void resolve_whenASiteWithoutATrailingSlash_thenItsIndex() {
        assertThat(severalSites.resolve("/site/governance")).get().satisfies(path -> {
            assertThat(path.site().id()).isEqualTo("governance");
            assertThat(path.file()).isEqualTo("index.html");
        });
    }

    /**
     * The whole point of the {@code /site/} segment: a site's id is in a namespace of its own, so a top-level
     * segment that happens to be the name of a site is still a path within the default site - and a site may
     * therefore be called anything, including after an environment or after a path the service answers on.
     */
    @Test
    void resolve_whenTheFirstSegmentIsTheNameOfASite_thenItIsStillAPathOfTheDefaultSite() {
        assertThat(severalSites.resolve("/governance/dev/")).get().satisfies(path -> {
            assertThat(path.site().id()).isEqualTo(Site.DEFAULT_SITE);
            assertThat(path.file()).isEqualTo("governance/dev/index.html");
        });
    }

    @Test
    void resolve_whenTheFirstSegmentIsNoSite_thenItIsAPathOfTheDefaultSite() {
        assertThat(severalSites.resolve("/systems/orders/")).get().satisfies(path -> {
            assertThat(path.site().id()).isEqualTo(Site.DEFAULT_SITE);
            assertThat(path.file()).isEqualTo("systems/orders/index.html");
        });
    }

    /**
     * The segment on its own, and a site nobody configured: both are the default site's business, so they
     * produce that site's 404 rather than nothing at all.
     */
    @Test
    void resolve_whenTheSiteSegmentNamesNoSite_thenTheDefaultSiteServesIt() {
        assertThat(severalSites.resolve("/site/")).get().satisfies(path -> {
            assertThat(path.site().id()).isEqualTo(Site.DEFAULT_SITE);
            assertThat(path.file()).isEqualTo("site/index.html");
        });
        assertThat(severalSites.resolve("/site/unknown/")).get()
                .extracting(SitePath::file).isEqualTo("site/unknown/index.html");
    }

    /**
     * The default site owns the root and is not reachable below the segment as well: a second URL for every one
     * of its pages is a duplicate for a search engine, and a base URL the generated site knows nothing about.
     */
    @Test
    void resolve_whenTheSiteSegmentNamesTheDefaultSite_thenItIsAPathWithinIt() {
        assertThat(severalSites.resolve("/site/default/")).get().satisfies(path -> {
            assertThat(path.site().id()).isEqualTo(Site.DEFAULT_SITE);
            assertThat(path.file()).isEqualTo("site/default/index.html");
        });
    }

    @Test
    void resolve_whenThereIsNoDefaultSiteAndNothingMatches_thenNothingServesIt() {
        SitePathResolver onlyGovernance = new SitePathResolver(new DocumentationSites(properties("governance")));

        assertThat(onlyGovernance.resolve("/systems/")).isEmpty();
        assertThat(onlyGovernance.resolve("/governance/")).isEmpty();
        assertThat(onlyGovernance.resolve("/site/governance/")).isPresent();
    }

    @Test
    void looksLikeADirectory_thenOnlyWhenTheLastSegmentHasNoExtension() {
        assertThat(singleSite.resolve("/systems/orders").orElseThrow().looksLikeADirectory()).isTrue();
        assertThat(singleSite.resolve("/sitemap.xml").orElseThrow().looksLikeADirectory()).isFalse();
    }

    @Test
    void resolveMicrosite_whenASystemsOwn_thenItsSetAndFile() {
        assertThat(singleSite.resolveMicrosite(
                "/microsites/orders/arc42/6-runtime-view/inbox/assets/app.js")).get().satisfies(path -> {
            assertThat(path.key().system()).isEqualTo("orders");
            assertThat(path.key().name()).isNull();
            assertThat(path.key().template()).isEqualTo("arc42");
            assertThat(path.key().location()).isEqualTo("6-runtime-view");
            assertThat(path.key().topic()).isEqualTo("inbox");
            assertThat(path.file()).isEqualTo("assets/app.js");
        });
    }

    /** A component called after a template would otherwise be indistinguishable from a system's own. */
    @Test
    void resolveMicrosite_whenAComponentsOrALibrarys_thenTheKindIsNamedInThePath() {
        assertThat(singleSite.resolveMicrosite(
                "/microsites/orders/components/orders-intake/arc42/6-runtime-view/inbox/"))
                .get().satisfies(path -> {
                    assertThat(path.key().kind()).isEqualTo(SubjectKind.COMPONENT);
                    assertThat(path.key().name()).isEqualTo("orders-intake");
                    assertThat(path.file()).describedAs("a microsite is opened at its entry point")
                            .isEqualTo("index.html");
                });
        assertThat(singleSite.resolveMicrosite(
                "/microsites/orders/libraries/orders-client/arc42/6-runtime-view/inbox"))
                .get().satisfies(path -> {
                    assertThat(path.key().kind()).isEqualTo(SubjectKind.LIBRARY);
                    assertThat(path.key().name()).isEqualTo("orders-client");
                    assertThat(path.file()).isEqualTo("index.html");
                });
    }

    @Test
    void resolveMicrosite_whenBelowASiteOfItsOwn_thenThatSiteServesIt() {
        assertThat(severalSites.resolveMicrosite(
                "/site/governance/microsites/orders/arc42/6-runtime-view/inbox/index.html"))
                .get().satisfies(path -> assertThat(path.site().id()).isEqualTo("governance"));
    }

    /** Everything else is a path of a generated site, and is resolved as one. */
    @Test
    void resolveMicrosite_whenThePathIsNotOne_thenNothing() {
        assertThat(singleSite.resolveMicrosite("/systems/orders/")).isEmpty();
        assertThat(singleSite.resolveMicrosite("/microsites/"))
                .describedAs("the segment alone names no set").isEmpty();
        assertThat(singleSite.resolveMicrosite("/microsites/orders/arc42/6-runtime-view"))
                .describedAs("and a path that stops before the topic names none either").isEmpty();
    }

    private static SiteProperties properties(String... ids) {
        SiteProperties properties = new SiteProperties();
        Map<String, SiteProperties.Site> sites = new LinkedHashMap<>();
        for (String id : ids) {
            sites.put(id, new SiteProperties.Site());
        }
        properties.setSites(sites);
        return properties;
    }
}
