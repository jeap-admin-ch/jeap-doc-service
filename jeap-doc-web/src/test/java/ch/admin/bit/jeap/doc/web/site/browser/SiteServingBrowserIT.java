package ch.admin.bit.jeap.doc.web.site.browser;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What only the running service shows: the answers {@code SiteRequestHandler} gives a browser loading a
 * generated site.
 * <p>
 * The status codes and header values are unit-tested elsewhere. Here they are met the way a reader meets them -
 * followed, cached and revalidated by a real browser over a real socket, against a site the generator produced
 * rather than against a fixture written to suit the assertion.
 */
class SiteServingBrowserIT extends SiteBrowserTestBase {

    /**
     * Every route of the site is generated with a trailing slash, and a hand-typed or hand-shortened URL has
     * none. The redirect is permanent and therefore cached for ever, which is why it is only ever sent for a
     * route that exists - and why a browser following it has to land on the page rather than on another
     * redirect.
     */
    @Test
    void route_whenTypedWithoutItsTrailingSlash_thenTheReaderLandsOnThePage() {
        Response response = open("/" + GUIDE_ROUTE);

        PlaywrightAssertions.assertThat(page).hasURL(url("/" + GUIDE_ROUTE + "/"));
        assertThat(response.status()).isEqualTo(200);
        PlaywrightAssertions.assertThat(page.getByRole(AriaRole.HEADING,
                new Page.GetByRoleOptions().setName("The upload guide").setLevel(1))).isVisible();
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * A path no site holds gets the site's own not-found page, with the status that says what it is. An empty
     * 404 would be the fallback for a site published before it had one; this site has one, and a reader should
     * see it with the navigation still around it.
     */
    @Test
    void unknownPath_thenTheSitesOwnNotFoundPageIsServedWithStatus404() {
        Response response = open("/there-is-no-such-page/");

        assertThat(response.status()).isEqualTo(404);
        PlaywrightAssertions.assertThat(page.getByText("Page Not Found")).isVisible();
        // The site's own page, not the container's: the navigation is still there.
        assertThat(switcherIsPresent()).isTrue();
        // Not assertNothingWentWrongInTheBrowser(): the browser logs the status of the document it asked for,
        // and here that status is the point - it is asserted above, from the response rather than from the
        // wording Chrome happens to log. An uncaught exception is never expected, so that half still holds.
        assertThat(pageErrors).describedAs("what the not-found page threw").isEmpty();
    }

    /**
     * The two caching rules the site depends on. The generator writes content-hashed names under
     * {@code assets/}, so those may be kept for good; everything else is replaced by the next build under the
     * same URL and must not be, or a reader is served yesterday's documentation after a deployment.
     */
    @Test
    void caching_thenThePagesRevalidateAndOnlyTheHashedAssetsAreKept() {
        List<Response> responses = Collections.synchronizedList(new ArrayList<>());
        page.onResponse(responses::add);

        Response document = open("/" + GUIDE_ROUTE + "/");

        assertThat(document.headers().get("cache-control")).isEqualTo("no-cache");
        assertThat(document.headers().get("etag")).startsWith("W/\"");
        List<Response> assets = responses.stream()
                .filter(response -> response.url().contains("/assets/"))
                .toList();
        assertThat(assets).describedAs("the hashed assets of the page").isNotEmpty();
        assertThat(assets).allSatisfy(asset ->
                assertThat(asset.headers().get("cache-control")).contains("immutable"));
    }

    /**
     * What the {@code no-cache} above buys: the reader holds the version they were given, asks whether it is
     * still current, and is told so without the document crossing the network again. Everything but the hashed
     * assets does this on every request, so answering it with the whole page would be most of what this serves.
     */
    @Test
    void revalidation_whenTheReaderAlreadyHoldsTheVersion_thenNothingIsSentAgain() {
        Response document = open("/" + GUIDE_ROUTE + "/");
        String entityTag = document.headers().get("etag");

        // From the page itself, so the request carries the site's origin and the service's own
        // Content-Security-Policy applies to it - the same connection the search index is fetched over.
        Object status = page.evaluate("""
                async (tag) => {
                    const response = await fetch(location.href, {headers: {'If-None-Match': tag}});
                    return response.status;
                }""", entityTag);

        assertThat(status).isEqualTo(304);
        assertNothingWentWrongInTheBrowser();
    }

    private boolean switcherIsPresent() {
        return page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Switch environment").setExact(false)).count() == 1;
    }
}
