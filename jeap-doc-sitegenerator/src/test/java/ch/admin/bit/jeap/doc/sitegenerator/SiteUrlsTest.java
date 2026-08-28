package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.PublicationProperties;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SiteEnvironment;
import ch.admin.bit.jeap.doc.domain.SiteProperties;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SiteUrlsTest {

    private static final Site DEFAULT_SITE =
            new DocumentationSites(new SiteProperties()).find(Site.DEFAULT_SITE).orElseThrow();

    @Test
    void baseUrl_whenServedAtTheRoot_thenJustTheSlash() {
        assertThat(urls("", "https://doc.example.ch").baseUrl(DEFAULT_SITE)).isEqualTo("/");
    }

    @Test
    void baseUrl_whenTheServiceHasAContextPath_thenBelowIt() {
        assertThat(urls("/docs", "https://doc.example.ch").baseUrl(DEFAULT_SITE)).isEqualTo("/docs/");
    }

    @Test
    void baseUrl_whenTheContextPathIsWrittenLoosely_thenItStillAgreesWithWhatIsServed() {
        assertThat(urls("docs/", "https://doc.example.ch").baseUrl(DEFAULT_SITE)).isEqualTo("/docs/");
        assertThat(urls("/", "https://doc.example.ch").baseUrl(DEFAULT_SITE)).isEqualTo("/");
    }

    @Test
    void baseUrl_whenTheSiteIsNotTheDefaultOne_thenUnderItsOwnSegment() {
        SiteProperties properties = new SiteProperties();
        properties.setSites(java.util.Map.of("governance", new SiteProperties.Site()));
        Site governance = new DocumentationSites(properties).find("governance").orElseThrow();

        assertThat(urls("/docs", "https://doc.example.ch").baseUrl(governance)).isEqualTo("/docs/governance/");
    }

    @Test
    void url_thenWithoutATrailingSlash() {
        assertThat(urls("", "https://doc.example.ch/").url()).isEqualTo("https://doc.example.ch");
    }

    /**
     * The generated site carries this origin in its sitemap and its metadata, and the site generator refuses an
     * empty one - which without this check would surface minutes into a build instead of in the deployment.
     */
    @Test
    void construct_whenNoUrlIsConfigured_thenTheServiceDoesNotStart() {
        assertThatThrownBy(() -> urls("/docs", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jeap.doc.publication.url");
    }

    /**
     * The value is an origin, not a URL with a path: a path given here is doubled in the sitemap and in every
     * canonical URL, or fails the Docusaurus build minutes into a run.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "https://doc.example.ch/docs",
            "https://doc.example.ch/docs/",
            "https://doc.example.ch?tenant=jme",
            "https://doc.example.ch#top",
            "doc.example.ch",
            "not a url at all"})
    void construct_whenTheUrlIsNotAnOrigin_thenTheServiceDoesNotStart(String url) {
        assertThatThrownBy(() -> urls("", url))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jeap.doc.publication.url");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "https://doc.example.ch",
            "https://doc.example.ch/",
            "http://localhost:8080"})
    void construct_whenTheUrlIsAnOrigin_thenItIsAccepted(String url) {
        assertThatCode(() -> urls("", url)).doesNotThrowAnyException();
    }

    /**
     * Validated and published have to be the same value: checking a stripped copy and then handing the
     * unstripped one to the site generator would let whitespace through the check that exists to stop it.
     */
    @Test
    void url_thenWhatIsPublishedIsWhatWasValidated() {
        assertThat(urls("", "  https://doc.example.ch/  ").url()).isEqualTo("https://doc.example.ch");
        assertThat(urls("", "https://doc.example.ch").url()).isEqualTo("https://doc.example.ch");
    }

    private static SiteUrls urls(String contextPath, String url) {
        PublicationProperties properties = new PublicationProperties();
        properties.setUrl(url);
        return new SiteUrls(properties, contextPath);
    }
}
