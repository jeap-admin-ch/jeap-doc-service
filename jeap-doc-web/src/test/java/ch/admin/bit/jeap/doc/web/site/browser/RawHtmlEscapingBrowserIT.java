package ch.admin.bit.jeap.doc.web.site.browser;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Raw HTML in an uploaded page is shown as text and never applied.
 * <p>
 * The site's policy allows inline script (`'unsafe-inline'`), because the colour mode needs it - so what keeps
 * an uploaded {@code <script>} from running is that it never becomes markup in the first place. That happens in
 * the site template ({@code plugins/remark-escape-raw-html}), and <b>only a browser says whether it worked</b>:
 * a build with the plugin and a build without it both succeed, and both write a page.
 */
class RawHtmlEscapingBrowserIT extends SiteBrowserTestBase {

    /** Where the uploaded page carrying the tags is served - chapter 2 of the system nothing generates. */
    private static final String ROUTE = "systems/" + DOCUMENTED_SYSTEM + "/system-architecture/constraints/"
                                        + UploadedDocumentation.RAW_HTML_PAGE + "/";

    @Test
    void anUploadedScript_isShownAsTextAndNeverRuns() {
        Response response = open("/" + ROUTE);

        assertThat(response.status()).isEqualTo(200);
        PlaywrightAssertions.assertThat(page.getByText("<script>window.MARKER_SCRIPT = 1</script>"))
                .isVisible();
        assertThat(page.evaluate("() => window.MARKER_SCRIPT"))
                .describedAs("the uploaded script must not have run").isNull();
    }

    @Test
    void anUploadedStyle_isShownAsTextAndStylesNothing() {
        open("/" + ROUTE);

        PlaywrightAssertions.assertThat(page.getByText("<style>body { outline: 7px solid red }</style>"))
                .isVisible();
        assertThat(page.evaluate("() => getComputedStyle(document.body).outlineWidth"))
                .describedAs("the uploaded style must not have been applied").isNotEqualTo("7px");
    }

    /**
     * A frame is what an uploaded page would reach for to put something else inside the documentation - the
     * service's own pages included.
     */
    @Test
    void anUploadedIframe_isShownAsTextAndFramesNothing() {
        open("/" + ROUTE);

        PlaywrightAssertions.assertThat(page.getByText("<iframe id=\"injected-frame\" src=\"/\"></iframe>"))
                .isVisible();
        assertThat(page.locator("#injected-frame").count())
                .describedAs("the uploaded iframe must not be in the page").isZero();
    }

    /**
     * Escaping raw HTML must not cost the Markdown a team actually writes. The admonition and the diagram are
     * the two constructs that look like markup and are not.
     */
    @Test
    void theMarkdownAroundIt_stillRenders() {
        open("/" + ROUTE);

        PlaywrightAssertions.assertThat(page.locator(".theme-admonition")).isVisible();
        PlaywrightAssertions.assertThat(page.getByText("An admonition still renders.")).isVisible();
        Locator diagram = page.locator("[data-plantuml-diagram] svg:has(text)").first();
        PlaywrightAssertions.assertThat(diagram).isVisible();
        assertThat(pageErrors).isEmpty();
    }
}
