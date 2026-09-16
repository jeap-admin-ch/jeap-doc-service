package ch.admin.bit.jeap.doc.web.site.browser;

import com.microsoft.playwright.Locator;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The versions of a message as a reader sees them: a row per version, a sub-row per schema, and each schema
 * folded in its cell. The table is built by the site from the generator's directive, so only a browser says
 * that it came out as a table at all.
 */
class GroupedTableBrowserIT extends SiteBrowserTestBase {

    private Locator versions() {
        open("/" + MESSAGE_REACTIONS_ROUTE + "/");
        return page.locator("article table.groupedTable");
    }

    @Test
    void theVersions_areARowPerVersionWithASubRowPerSchema() {
        Locator table = versions();

        assertThat(table.locator("thead th")).hasCount(3);
        assertThat(table.locator("tbody tr")).hasCount(3);
        Locator firstVersion = table.locator("tbody tr").nth(0).locator("td");
        assertThat(firstVersion).hasCount(3);
        assertThat(firstVersion.nth(0)).hasText("1.0.0");
        assertThat(firstVersion.nth(1)).hasText("Value");
        Locator secondVersion = table.locator("tbody tr").nth(1).locator("td");
        assertThat(secondVersion.nth(0)).hasText("2.0.0");
        assertThat(secondVersion.nth(0)).hasAttribute("rowspan", "2");
        assertThat(secondVersion.nth(1)).hasText("Key");
        // The value row of 2.0.0 shares the version cell above it, and has no column more.
        assertThat(table.locator("tbody tr").nth(2).locator("td")).hasCount(2);
        assertThat(table.locator("tbody tr").nth(2))
                .containsText("Avro Schema Compatibility with Version 1.0.0: BACKWARD");
        assertNothingWentWrongInTheBrowser();
    }

    /** No row stripes: a spanning cell would otherwise take the colour of whichever row it starts in. */
    @Test
    void everyVersionCell_hasTheSameBackground() {
        Locator table = versions();

        String first = background(table.locator("tbody tr").nth(0).locator("td").nth(0));
        String second = background(table.locator("tbody tr").nth(1).locator("td").nth(0));

        assertThat(second).isEqualTo(first);
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void aSchema_isFoldedFirstInItsCellAndOpensWithinThePage() {
        Locator table = versions();
        Locator cell = table.locator("tbody tr").nth(2).locator("td").last();
        Locator fold = cell.locator("details");

        assertThat(cell.locator(":scope > *").first()).hasAttribute("class",
                java.util.regex.Pattern.compile("details"));
        assertThat(cell.getByText("aFieldWithAVeryLongName", new Locator.GetByTextOptions())).not().isVisible();

        fold.locator("summary").click();

        assertThat(cell.getByText("aFieldWithAVeryLongName", new Locator.GetByTextOptions())).isVisible();
        double article = width(page.locator("article"));
        assertThat(width(table)).describedAs("the table stays inside the page when a fold opens")
                .isLessThanOrEqualTo(article + 1);
        assertNothingWentWrongInTheBrowser();
    }

    private static String background(Locator cell) {
        return (String) cell.evaluate("element => getComputedStyle(element).backgroundColor");
    }

    private static double width(Locator element) {
        return ((Number) element.evaluate("element => element.getBoundingClientRect().width")).doubleValue();
    }
}
