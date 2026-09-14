package ch.admin.bit.jeap.doc.web.site.browser;

import com.microsoft.playwright.Frame;
import com.microsoft.playwright.options.ColorScheme;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An uploaded microsite as a reader meets it: the whole way from the doc workflow's {@code PUT} to a real
 * browser.
 * <p>
 * The files are uploaded through the API and served live from the bucket; the page around them was written
 * into the site this suite built. What only a browser can say is asserted here - that the frame sits inside
 * the site's own navigation, that the documentation inside it has an <b>opaque origin</b>, and that the one
 * script the service injects gives that document a storage object, without which an application like Allure 3
 * never starts.
 */
class MicrositeBrowserIT extends SiteBrowserTestBase {

    /** Where the generated page is served: the chapter, and the microsite's namespace under it. */
    private static final String ROUTE = "systems/" + DOCUMENTED_SYSTEM + "/system-architecture/constraints/"
                                        + "microsites/" + UploadedDocumentation.MICROSITE_TOPIC;

    private static final String HEADING = UploadedDocumentation.ENTRY_POINT_HEADING;
    private static final String NESTED_PAGE = UploadedDocumentation.NESTED_PAGE;
    private static final String NESTED_HEADING = UploadedDocumentation.NESTED_HEADING;
    private static final String NESTED_COLOUR = UploadedDocumentation.NESTED_COLOUR;

    @Autowired
    private MockMvc mockMvc;

    /**
     * Uploaded once for the whole suite, not per test.
     * <p>
     * Every upload writes a new prefix and removes the one it replaced, while the prefix a request resolves
     * to is cached for a few seconds - so uploading per test could point a frame at files that had just
     * been deleted, and fail on timing rather than on anything true.
     */
    private static boolean uploaded;

    @Override
    protected void prepareWhatIsServed() {
        super.prepareWhatIsServed();
        if (!uploaded) {
            uploadTheMicrosite();
            uploaded = true;
        }
    }

    /**
     * The files the frame loads, through the API that publishes them. The row the site was built from names
     * the same set, so the upload is what puts the bytes where the page already points.
     */
    private void uploadTheMicrosite() {
        var request = put("/api/uploads/docs/{uploadId}", UUID.randomUUID())
                .contentType("application/zip").content(UploadedDocumentation.micrositeBundle());
        UploadedDocumentation.micrositeUpload(DOCUMENTED_SYSTEM).forEach(request::param);
        try {
            mockMvc.perform(request.with(authentication(
                            tokenWithRoles(uploadsRole(DOCUMENTED_SYSTEM, "write")))))
                    .andExpect(status().isCreated());
        } catch (Exception e) {
            throw new IllegalStateException("The microsite this test drives could not be uploaded.", e);
        }
    }

    /**
     * The frame the microsite is in.
     * <p>
     * <b>Told from the page by more than the word.</b> The page's own route carries {@code microsites/} too -
     * that is the namespace its slug puts it under - so matching on that alone picks the main document and
     * then asserts the microsite's markup against the site's own chrome.
     */
    private Frame micrositeFrame() {
        // The iframe is lazy, so this waits for it to have loaded before a frame is picked at all.
        page.frameLocator("iframe").locator("body").waitFor();
        String served = "/microsites/" + DOCUMENTED_SYSTEM + "/arc42/";
        Optional<Frame> framed = page.frames().stream()
                .filter(frame -> !frame.equals(page.mainFrame()))
                .filter(frame -> frame.url().contains(served))
                .findFirst();
        assertThat(framed).describedAs("the page frames the microsite served under %s", served).isPresent();
        return framed.orElseThrow();
    }

    /**
     * <b>The reader never leaves the documentation.</b> The navbar and the sidebar of the site are around the
     * frame, which is the whole point of publishing a microsite into a page rather than linking away to it.
     */
    @Test
    void theMicrositePage_showsTheFrameInsideTheSitesOwnNavigation() {
        Response response = open("/" + ROUTE + "/");

        assertThat(response.status()).isEqualTo(200);
        PlaywrightAssertions.assertThat(page.locator("nav.navbar")).isVisible();
        PlaywrightAssertions.assertThat(page.locator("nav.menu")).isVisible();
        Locator frame = page.locator("iframe");
        PlaywrightAssertions.assertThat(frame).isVisible();
        assertThat(frame.getAttribute("sandbox"))
                .describedAs("never allow-same-origin: with it the frame could remove this and reload")
                .contains("allow-scripts").doesNotContain("allow-same-origin");
    }

    @Test
    void theMicrosite_isTheDocumentationThatWasUploaded() {
        open("/" + ROUTE + "/");

        PlaywrightAssertions.assertThat(micrositeFrame().locator("h1")).hasText(HEADING);
    }

    /**
     * <b>What a link into a microsite opens.</b> A deep link is the page's own route plus {@code ?path=} -
     * what a search result will be - and the reader arrives at the file it names, with the navigation around
     * it. Every request of the chain is the doc service's: the page, the file the frame points at, and the
     * stylesheet that file names relative to itself. The team that uploaded it wrote no part of the URL.
     */
    @Test
    void aPathInTheQuery_opensThatPageInTheFrameWithItsOwnAssets() {
        open("/" + ROUTE + "/?path=" + NESTED_PAGE);
        Frame microsite = micrositeFrame();

        // The colour mode is appended after the path, so the URL does not end with it.
        assertThat(microsite.url()).contains("/" + NESTED_PAGE);
        PlaywrightAssertions.assertThat(microsite.locator("h1")).hasText(NESTED_HEADING);
        assertThat(microsite.locator("h1").evaluate("h1 => getComputedStyle(h1).color"))
                .describedAs("the stylesheet the framed page names was served and applied")
                .isEqualTo(NESTED_COLOUR);
    }

    /**
     * <b>And in light mode, where nothing re-renders the page after it hydrates.</b> The page is pre-rendered
     * without the query, so its frame opens the entry point - and React keeps an attribute the server rendered
     * rather than correcting it while it hydrates. This suite runs its browser in dark mode, where the colour
     * mode changes right after hydration and re-renders the frame with the right page, which is exactly why a
     * deep link looked like it worked.
     */
    @Test
    void aPathInTheQuery_opensThatPageInLightModeToo() {
        page.emulateMedia(new Page.EmulateMediaOptions().setColorScheme(ColorScheme.LIGHT));

        open("/" + ROUTE + "/?path=" + NESTED_PAGE);

        PlaywrightAssertions.assertThat(micrositeFrame().locator("h1")).hasText(NESTED_HEADING);
        assertThat(page.getByText("Open in a new tab").getAttribute("href"))
                .describedAs("the link to the page on its own opens the same page the frame shows")
                .endsWith("/" + NESTED_PAGE);
    }

    /** A path edited into one that leaves the microsite opens the entry point instead. */
    @Test
    void aPathThatLeavesTheMicrosite_fallsBackToTheEntryPoint() {
        open("/" + ROUTE + "/?path=../../somewhere-else.html");

        PlaywrightAssertions.assertThat(micrositeFrame().locator("h1")).hasText(HEADING);
    }

    /**
     * <b>The document has an origin of its own and cannot reach the page around it.</b> That is what the
     * sandbox is for, and it is the thing no unit test can show.
     */
    @Test
    void theFramedDocument_hasAnOpaqueOriginAndCannotReachThePage() {
        open("/" + ROUTE + "/");
        Frame microsite = micrositeFrame();

        assertThat(microsite.evaluate("() => window.ORIGIN")).isEqualTo("null");
        assertThat(microsite.evaluate("() => window.REACHED_PARENT")).isEqualTo(false);
    }

    /**
     * <b>And storage works all the same.</b> An opaque origin refuses {@code localStorage} - an application
     * that reads it while it loads never starts - so the service injects one script that gives the document
     * an in-memory one. This is the assertion that says the injection reached the page and ran first.
     */
    @Test
    void theFramedDocument_hasTheStorageTheShimGivesIt() {
        open("/" + ROUTE + "/");
        Frame microsite = micrositeFrame();

        assertThat(microsite.evaluate("() => window.STORAGE_WORKED"))
                .describedAs("the shim ran before the microsite's own script")
                .isEqualTo(true);
        assertThat(microsite.evaluate("() => { sessionStorage.setItem('a', 'b'); "
                                      + "return sessionStorage.getItem('a'); }")).isEqualTo("b");
        assertThat(microsite.evaluate("() => { document.cookie = 'a=b'; return document.cookie; }"))
                .describedAs("and the cookie jar with it").isEqualTo("a=b");
    }
}
