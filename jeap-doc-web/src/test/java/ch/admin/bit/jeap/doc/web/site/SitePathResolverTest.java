package ch.admin.bit.jeap.doc.web.site;

import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SiteProperties;
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
        assertThat(singleSite.resolve("/dev/systems/wvs/")).get()
                .extracting(SitePath::file).isEqualTo("dev/systems/wvs/index.html");
    }

    @Test
    void resolve_whenAFile_thenThatFile() {
        assertThat(singleSite.resolve("/assets/js/main.abc123.js")).get()
                .extracting(SitePath::file).isEqualTo("assets/js/main.abc123.js");
    }

    /**
     * The environments of the default site are served under their own segment, and they are not sites - so a
     * path that starts with one belongs to the default site. It is why a site may not be named after one.
     */
    @Test
    void resolve_whenTheFirstSegmentIsAnEnvironment_thenTheDefaultSiteServesIt() {
        assertThat(severalSites.resolve("/dev/")).get().satisfies(path -> {
            assertThat(path.site().id()).isEqualTo(Site.DEFAULT_SITE);
            assertThat(path.file()).isEqualTo("dev/index.html");
        });
    }

    @Test
    void resolve_whenTheFirstSegmentNamesASite_thenThatSiteServesTheRest() {
        assertThat(severalSites.resolve("/governance/dev/systems/")).get().satisfies(path -> {
            assertThat(path.site().id()).isEqualTo("governance");
            assertThat(path.file()).isEqualTo("dev/systems/index.html");
        });
    }

    @Test
    void resolve_whenASiteWithoutATrailingSlash_thenItsIndex() {
        assertThat(severalSites.resolve("/governance")).get().satisfies(path -> {
            assertThat(path.site().id()).isEqualTo("governance");
            assertThat(path.file()).isEqualTo("index.html");
        });
    }

    @Test
    void resolve_whenTheFirstSegmentIsNoSite_thenItIsAPathOfTheDefaultSite() {
        assertThat(severalSites.resolve("/systems/wvs/")).get().satisfies(path -> {
            assertThat(path.site().id()).isEqualTo(Site.DEFAULT_SITE);
            assertThat(path.file()).isEqualTo("systems/wvs/index.html");
        });
    }

    @Test
    void resolve_whenThereIsNoDefaultSiteAndNothingMatches_thenNothingServesIt() {
        SitePathResolver onlyGovernance = new SitePathResolver(new DocumentationSites(properties("governance")));

        assertThat(onlyGovernance.resolve("/systems/")).isEmpty();
        assertThat(onlyGovernance.resolve("/governance/")).isPresent();
    }

    @Test
    void looksLikeADirectory_thenOnlyWhenTheLastSegmentHasNoExtension() {
        assertThat(singleSite.resolve("/systems/wvs").orElseThrow().looksLikeADirectory()).isTrue();
        assertThat(singleSite.resolve("/sitemap.xml").orElseThrow().looksLikeADirectory()).isFalse();
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
