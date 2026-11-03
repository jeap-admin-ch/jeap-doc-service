package ch.admin.bit.jeap.doc.domain.content;

import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.NoArchitectureModel;
import ch.admin.bit.jeap.doc.domain.NoCustomDocumentation;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SiteProperties;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which content source each site has, checked while the service starts.
 */
class ContentSourcesTest {

    @Test
    void of_whenASiteSetsNoContent_thenItIsTheDocumentationOfTheSystems() {
        ContentSources sources = sourcesFor(Map.of(Site.DEFAULT_SITE, site(configured -> {
        })));

        Site site = new DocumentationSites(properties(Map.of(Site.DEFAULT_SITE, site(configured -> {
        })))).find(Site.DEFAULT_SITE).orElseThrow();
        assertThat(sources.of(site).id()).isEqualTo(SystemDocumentationContent.SOURCE_ID);
        assertThat(sources.of(site).publishedByTheArchitectureImport()).isTrue();
    }

    @Test
    void construct_whenASiteNamesASourceNobodyProvides_thenTheStartupFailsNamingTheSiteAndTheValue() {
        assertThatThrownBy(() -> sourcesFor(Map.of("handbook", site(configured -> {
            configured.setPerEnvironment(false);
            configured.setContent("wiki");
        })))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'handbook'")
                .hasMessageContaining("'wiki'")
                .hasMessageContaining(StaticSiteContent.SOURCE_ID);
    }

    @Test
    void construct_whenTheDocumentationOfTheSystemsHasNoEnvironments_thenTheStartupFailsNamingTheSite() {
        assertThatThrownBy(() -> sourcesFor(Map.of("handbook", site(configured ->
                configured.setPerEnvironment(false)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'handbook'")
                .hasMessageContaining("per-environment: false");
    }

    @Test
    void construct_whenAStaticSiteHasEnvironments_thenTheStartupFailsNamingTheSite() {
        assertThatThrownBy(() -> sourcesFor(Map.of("handbook", site(configured ->
                configured.setContent(StaticSiteContent.SOURCE_ID)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'handbook'")
                .hasMessageContaining("per-environment: false");
    }

    @Test
    void construct_whenAStaticSiteHasNoEnvironments_thenItIsOnePartAndNoImportAsksForIt() {
        SiteProperties properties = properties(Map.of("handbook", site(configured -> {
            configured.setPerEnvironment(false);
            configured.setContent(StaticSiteContent.SOURCE_ID);
        })));
        Site handbook = new DocumentationSites(properties).find("handbook").orElseThrow();

        ContentSource source = new ContentSources(sources(), new DocumentationSites(properties)).of(handbook);

        assertThat(source.publishedByTheArchitectureImport()).isFalse();
        assertThat(source.partition().partsOf(handbook)).singleElement()
                .satisfies(part -> assertThat(part.isShell()).isTrue());
    }

    @Test
    void construct_whenTwoSourcesHaveOneId_thenTheStartupFails() {
        List<ContentSource> twice = List.of(new StaticSiteContent(), new StaticSiteContent());

        assertThatThrownBy(() -> new ContentSources(twice, new DocumentationSites(new SiteProperties())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(StaticSiteContent.SOURCE_ID);
    }

    private static ContentSources sourcesFor(Map<String, SiteProperties.Site> sites) {
        return new ContentSources(sources(), new DocumentationSites(properties(sites)));
    }

    private static List<ContentSource> sources() {
        return List.of(new SystemDocumentationContent(new NoArchitectureModel(), new NoCustomDocumentation()),
                new StaticSiteContent());
    }

    private static SiteProperties properties(Map<String, SiteProperties.Site> sites) {
        SiteProperties properties = new SiteProperties();
        properties.setSites(new LinkedHashMap<>(sites));
        return properties;
    }

    private static SiteProperties.Site site(Consumer<SiteProperties.Site> customizer) {
        SiteProperties.Site site = new SiteProperties.Site();
        customizer.accept(site);
        return site;
    }
}
