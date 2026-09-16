package ch.admin.bit.jeap.doc.web.site.browser;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A fold as the generator writes one: closed when the page loads, open on a click, and a diagram inside it
 * drawn when it is opened.
 */
class DetailsBrowserIT extends SiteBrowserTestBase {

    @Test
    void aFold_isClosedUntilItsSummaryIsClicked() {
        open("/" + FOLD_ROUTE + "/");

        Locator fold = foldNamed("Version 1.0.0");
        assertThat(fold.getAttribute("open")).isNull();
        PlaywrightAssertions.assertThat(page.getByText("record FoldedKey")).not().isVisible();

        fold.locator("summary").click();

        PlaywrightAssertions.assertThat(page.getByText("record FoldedKey")).isVisible();
        assertNothingWentWrongInTheBrowser();
    }

    /** The diagram plugin draws when a diagram comes into view, so a folded one is drawn once it is opened. */
    @Test
    void aFoldedDiagram_isDrawnWhenTheFoldIsOpened() {
        open("/" + FOLD_ROUTE + "/");

        for (String name : new String[]{"PlantUML", "GraphViz"}) {
            Locator fold = foldNamed(name);
            fold.locator("summary").click();
            Locator drawn = fold.locator("[data-plantuml-diagram] svg:has(text)").first();
            PlaywrightAssertions.assertThat(drawn).isVisible();
            @SuppressWarnings("unchecked")
            Map<String, Object> box = (Map<String, Object>) drawn.evaluate(
                    "svg => { const r = svg.getBoundingClientRect(); return {width: r.width, height: r.height}; }");
            assertThat(((Number) box.get("width")).doubleValue()).describedAs(name).isPositive();
            assertThat(((Number) box.get("height")).doubleValue()).describedAs(name).isPositive();
        }
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void aFoldsSummary_isTextAndRunsNothing() {
        open("/" + FOLD_ROUTE + "/");

        PlaywrightAssertions.assertThat(page.getByText("<script>window.MARKER_FOLD = 1</script>")).isVisible();
        assertThat(page.evaluate("() => window.MARKER_FOLD")).isNull();
        assertNothingWentWrongInTheBrowser();
    }

    private Locator foldNamed(String summary) {
        return page.locator("article details").filter(new Locator.FilterOptions()
                .setHas(page.locator("summary", new com.microsoft.playwright.Page.LocatorOptions()
                        .setHasText(summary))));
    }
}
