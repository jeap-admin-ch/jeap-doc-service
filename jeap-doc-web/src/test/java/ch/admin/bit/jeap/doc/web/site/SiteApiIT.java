package ch.admin.bit.jeap.doc.web.site;

import ch.admin.bit.jeap.doc.domain.BuildTrigger;
import ch.admin.bit.jeap.doc.domain.DocumentationBuild;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import ch.admin.bit.jeap.doc.domain.port.SitePublicationStorage;
import ch.admin.bit.jeap.doc.web.DocServiceIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The documentation as a browser sees it.
 * <p>
 * Two rules are pinned here rather than anywhere else, because this is where they are visible at the same time:
 * the documentation is served to anyone, and the API is not.
 */
class SiteApiIT extends DocServiceIntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocumentationBuildRepository builds;

    @Autowired
    private SitePublicationStorage publication;

    private long publishedBuildId;

    @BeforeEach
    void publishASite(@org.junit.jupiter.api.io.TempDir Path site) throws IOException {
        DocumentationBuild build = builds.start(Site.DEFAULT_SITE, BuildTrigger.SCHEDULE, "test", Instant.now());
        Files.writeString(site.resolve("index.html"), "<html><body>Documentation</body></html>",
                StandardCharsets.UTF_8);
        Files.writeString(site.resolve("404.html"), "<html><body>Not found here</body></html>",
                StandardCharsets.UTF_8);
        Files.createDirectories(site.resolve("assets/js"));
        Files.writeString(site.resolve("assets/js/main.abc123.js"), "console.log('hello')", StandardCharsets.UTF_8);
        Files.createDirectories(site.resolve("dev"));
        Files.writeString(site.resolve("dev/index.html"), "<html><body>Development</body></html>",
                StandardCharsets.UTF_8);
        Files.createDirectories(site.resolve("systems/orders/api"));
        Files.writeString(site.resolve("systems/orders/api/index.html"), "<html><body>The API of orders</body></html>",
                StandardCharsets.UTF_8);

        String prefix = Site.DEFAULT_SITE + "/" + build.id();
        publication.publish(prefix, site);
        builds.succeeded(build.id(), prefix, 3, 100, 10, null, Instant.now());
        publishedBuildId = build.id();
    }

    @Test
    void get_whenTheRoot_thenTheDocumentationWithoutAToken() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Documentation")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-cache")));
    }

    @Test
    void get_whenAnEnvironmentOfTheSite_thenItsOwnTree() throws Exception {
        mockMvc.perform(get("/dev/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Development")));
    }

    /**
     * The site generator writes content-hashed names under assets/, so the same URL never means two things.
     */
    @Test
    void get_whenAHashedAsset_thenItMayBeKeptForGood() throws Exception {
        mockMvc.perform(get("/assets/js/main.abc123.js"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("immutable")));
    }

    @Test
    void get_whenARouteWithoutItsTrailingSlash_thenRedirectedToTheCanonicalForm() throws Exception {
        mockMvc.perform(get("/dev"))
                .andExpect(status().isMovedPermanently())
                .andExpect(redirectedUrl("/dev/"));
    }

    @Test
    void get_whenThePageDoesNotExist_thenTheSitesOwnNotFoundPage() throws Exception {
        mockMvc.perform(get("/systems/nothing/index.html"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Not found here")));
    }

    /**
     * The documentation is open and the API is not - which is the whole of the decision, in one place.
     */
    @Test
    void get_whenTheApi_thenStillAuthenticated() throws Exception {
        mockMvc.perform(get("/api/uploads/docs/8f1c9a2e-6a1a-4a5f-9a5e-2b0f9a3c1d77?system=orders"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * A documentation page may perfectly well live at .../api/ - it is an ordinary arc42 URL, and the natural
     * home of an uploaded API microsite. Deciding what is the API by asking whether the URL *contains* "/api/"
     * would answer such a page with 401.
     */
    @Test
    void get_whenAPageLivesUnderAnApiSegment_thenItIsStillOpenDocumentation() throws Exception {
        mockMvc.perform(get("/systems/orders/api/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("The API of orders")));
    }

    /**
     * The documentation is the last handler in the chain, not the first: a catch-all matched ahead of Spring's
     * resource handlers would answer for the Swagger UI's assets, and the UI this service advertises would not
     * load.
     */
    @Test
    void get_whenASwaggerUiAsset_thenTheResourceHandlerServesItRatherThanTheSite() throws Exception {
        mockMvc.perform(get("/swagger-ui/swagger-ui.css"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Not found here"))));
    }

    /**
     * Everything but the hashed assets is asked to revalidate on every request, so answering the revalidation
     * with the whole document again would be most of the traffic this serves.
     */
    @Test
    void get_whenTheReaderAlreadyHoldsThisVersion_thenNotModifiedAndNoBody() throws Exception {
        String entityTag = mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("ETag");

        mockMvc.perform(get("/").header("If-None-Match", entityTag))
                .andExpect(status().isNotModified())
                .andExpect(content().string(""));
    }

    /**
     * Only GET and HEAD reach the documentation. A misdirected write would otherwise be told the documentation
     * is missing rather than what is wrong with it.
     */
    @Test
    void post_whenNotTheApi_thenMethodNotAllowedRatherThanTheSite() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/dev/"))
                .andExpect(status().isMethodNotAllowed());
    }
    /**
     * A 301 is cached for ever, so the canonical form is checked before the reader is sent to it - a typo that
     * redirected would keep redirecting from the reader's cache long after the site had changed.
     */
    @Test
    void get_whenARouteWithoutATrailingSlashDoesNotExist_thenNotFoundRatherThanARedirect() throws Exception {
        mockMvc.perform(get("/a-route-nobody-generated"))
                .andExpect(status().isNotFound());
    }

    /**
     * The query goes with the redirect: /search?q=upload is a link readers share, and dropping it would land
     * them on an empty search box - permanently, since the redirect is cached.
     */
    @Test
    void get_whenARouteWithAQueryIsRedirected_thenTheQuerySurvives() throws Exception {
        mockMvc.perform(get("/dev?q=upload"))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "/dev/?q=upload"));
    }

    /**
     * A HEAD answers with the headers of the object and no body - and, more to the point, without dragging the
     * body across the network from the object storage first.
     */
    @Test
    void head_whenAPageExists_thenTheHeadersWithoutABody() throws Exception {
        mockMvc.perform(head("/"))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(content().string(""));
    }

    /**
     * A published site whose objects the bucket's lifecycle rule expired is not a wrong URL - the documentation
     * is simply not there any more, and answering 404 would send an operator looking for a typo.
     */
    @Test
    void get_whenTheSiteIsPublishedButItsObjectsAreGone_thenServiceUnavailableRatherThanNotFound() throws Exception {
        publication.delete(Site.DEFAULT_SITE + "/" + publishedBuildId);

        mockMvc.perform(get("/"))
                .andExpect(status().isServiceUnavailable());
    }

    /**
     * A page other than the front page is still a 404 when it is gone: only the site as a whole being absent
     * says something different.
     */
    @Test
    void get_whenOnePageIsGoneButTheSiteIsThere_thenNotFound() throws Exception {
        mockMvc.perform(get("/a-page-that-was-never-generated.html"))
                .andExpect(status().isNotFound());
    }

    /**
     * The header carries a list, and a proxy may have weakened the tag on the way - so it is compared entry by
     * entry and without the weak marker. Replaying the exact tag is the only shape a naive comparison gets
     * right, so the other three are what pin the code.
     */
    /**
     * The service sends a weak tag, so that the container may compress the response. A cache that stored the
     * strong form, or a proxy that strengthened it, still has to be answered with a 304.
     */
    @Test
    void get_whenTheTagIsTheStrongFormOfTheSame_thenStillNotModified() throws Exception {
        String strong = etagOfTheRoot().substring("W/".length());

        mockMvc.perform(get("/").header(HttpHeaders.IF_NONE_MATCH, strong))
                .andExpect(status().isNotModified());
    }

    @Test
    void get_whenTheTagIsOneOfAList_thenStillNotModified() throws Exception {
        String etag = etagOfTheRoot();

        mockMvc.perform(get("/").header(HttpHeaders.IF_NONE_MATCH, "\"something-else\", " + etag))
                .andExpect(status().isNotModified());
    }

    @Test
    void get_whenAnythingWillDo_thenStillNotModified() throws Exception {
        mockMvc.perform(get("/").header(HttpHeaders.IF_NONE_MATCH, "*"))
                .andExpect(status().isNotModified());
    }

    /**
     * And the negative, so the comparison cannot degenerate into "any header means unchanged".
     */
    @Test
    void get_whenTheReaderHoldsADifferentVersion_thenTheDocumentIsSent() throws Exception {
        mockMvc.perform(get("/").header(HttpHeaders.IF_NONE_MATCH, "\"a-version-that-was-never-published\""))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Documentation")));
    }

    /**
     * The path becomes an S3 key by concatenation, over a bucket that also holds the uploaded bundles. The
     * container already refuses what would escape the application root; the handler refuses it too, so the rule
     * does not live in another component.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/../uploads/docs/1/1/bundle.zip",
            "/dev/../../index.html",
            "/dev/%2e%2e/index.html"})
    void get_whenThePathTriesToLeaveTheSite_thenNothingIsServed(String path) throws Exception {
        // Built as a URI so MockMvc does not re-encode the escapes. Which of the two refuses it is not the
        // point and is not stable - the container rejects a URI that would leave the application root before
        // the handler ever sees it, and the handler refuses the rest. That the handler refuses what reaches it
        // is asserted as a unit, in SiteRequestHandlerTest.
        mockMvc.perform(get(java.net.URI.create(path)))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .describedAs("%s must not be served", path)
                        .isIn(400, 404))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("bundle"))));
    }

    private String etagOfTheRoot() throws Exception {
        return mockMvc.perform(get("/")).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
    }

}
