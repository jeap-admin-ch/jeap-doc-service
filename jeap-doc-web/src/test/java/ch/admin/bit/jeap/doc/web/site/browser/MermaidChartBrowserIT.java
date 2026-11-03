package ch.admin.bit.jeap.doc.web.site.browser;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.ColorScheme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Locale;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * The score history chart of the governance site, drawn by Mermaid in the browser. The build only leaves a place
 * for it, so nothing but a browser can tell a chart from one Mermaid refused to parse.
 */
class MermaidChartBrowserIT extends SiteBrowserTestBase {

    /**
     * In both colour modes: the light theme's own first line colour is a grey that barely shows on white, so
     * the line has to carry the colour the chart names. And the days stand upright, or a month of them overlaps.
     */
    @ParameterizedTest
    @EnumSource(value = ColorScheme.class, names = {"LIGHT", "DARK"})
    void aScoreHistory_isDrawnAsAnXyChartWithAVisibleLineAndUprightDays(ColorScheme scheme) {
        page.emulateMedia(new Page.EmulateMediaOptions().setColorScheme(scheme));
        open("/" + CHART_ROUTE + "/");
        assertThat(page.locator("html")).hasAttribute("data-theme", scheme.name().toLowerCase(Locale.ROOT));

        Locator chart = chart();
        assertThat(chart).isVisible();
        assertThat(chart).hasAttribute("aria-roledescription", "xychart");
        // The day is axis text of the drawn chart; the fence itself never reaches the page.
        assertThat(chart).containsText(CHART_DAY);
        assertThat(chart.locator("g.line-plot-0 path")).hasAttribute("stroke", "#3498db");
        assertThat(chart.locator("g.bottom-axis g.label text").first())
                .hasAttribute("transform", Pattern.compile("rotate\\(-90\\)"));
        assertNothingWentWrongInTheBrowser();
    }

    /** The same chart with one keyword wrong is not drawn - so the check above can fail. */
    @Test
    void aChartThatDoesNotParse_isNotDrawn() {
        open("/" + BROKEN_CHART_ROUTE + "/");

        assertThat(page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Try again"))).isVisible();
        assertThat(chart()).hasCount(0);
    }

    private Locator chart() {
        return page.locator("article .docusaurus-mermaid-container svg");
    }
}
