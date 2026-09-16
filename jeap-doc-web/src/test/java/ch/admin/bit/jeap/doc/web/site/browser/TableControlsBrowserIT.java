package ch.admin.bit.jeap.doc.web.site.browser;

import com.microsoft.playwright.Locator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sorting and filtering the tables of a page, done by {@code src/clientModules/tableControls.js} in the site
 * template. Every table gets sort buttons; one with more than 15 rows also gets a filter.
 */
class TableControlsBrowserIT extends SiteBrowserTestBase {

    private static final String TERMS_ROUTE = "/systems/" + DOCUMENTED_SYSTEM + "/system-architecture/constraints/"
            + UploadedDocumentation.TERMS_PAGE + "/";

    private Locator relations() {
        return page.locator("article table").nth(0);
    }

    private Locator reactions() {
        return page.locator("article table").nth(1);
    }

    private Locator filterOf(Locator table) {
        return table.locator("xpath=preceding-sibling::*[1][contains(@class, 'jeapTableFilter')]");
    }

    private static List<String> column(Locator table, int column) {
        return table.locator("tbody tr:not([hidden])").all().stream()
                .map(row -> row.locator("td").nth(column).innerText().trim())
                .toList();
    }

    @Test
    void everyHeader_getsASortButton_andOnlyTheLongTableAFilter() {
        open("/" + TABLES_ROUTE + "/");

        assertThat(relations().locator("thead th button.jeapTableSort")).hasCount(5);
        assertThat(relations().locator("thead th").nth(0).locator("button"))
                .hasAttribute("aria-label", "Sort by From");
        assertThat(reactions().locator("thead th button.jeapTableSort")).hasCount(3);
        assertThat(filterOf(relations())).hasCount(1);
        assertThat(filterOf(relations()).locator("input")).hasAttribute("placeholder", "Filter " + RELATIONS + " rows");
        assertThat(filterOf(relations()).locator(".jeapTableFilterCount")).hasText(RELATIONS + " rows");
        assertThat(filterOf(reactions())).hasCount(0);
        assertThat(page.locator("article .jeapTableFilter")).hasCount(1);
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void aHeader_sortsAscendingThenDescendingThenAsWritten() {
        open("/" + TABLES_ROUTE + "/");
        Locator header = relations().locator("thead th").nth(2);
        List<String> written = column(relations(), 2);

        header.locator("button").click();
        assertThat(header).hasAttribute("aria-sort", "ascending");
        assertThat(column(relations(), 2)).isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);

        header.locator("button").click();
        assertThat(header).hasAttribute("aria-sort", "descending");
        assertThat(column(relations(), 2)).isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER.reversed());

        header.locator("button").click();
        assertThat(header).not().hasAttribute("aria-sort", java.util.regex.Pattern.compile(".*"));
        assertThat(column(relations(), 2)).isEqualTo(written);
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void sortingAnotherColumn_takesTheSortFromTheFirst() {
        open("/" + TABLES_ROUTE + "/");

        relations().locator("thead th").nth(2).locator("button").click();
        relations().locator("thead th").nth(3).locator("button").click();

        assertThat(relations().locator("thead th[aria-sort]")).hasCount(1);
        assertThat(relations().locator("thead th").nth(3)).hasAttribute("aria-sort", "ascending");
        assertNothingWentWrongInTheBrowser();
    }

    /** As text, 987 would come after 2'150 - a count is compared by its value. */
    @Test
    void aColumnOfNumbers_isRightAlignedAndSortedByValue() {
        open("/" + TABLES_ROUTE + "/");
        Locator header = reactions().locator("thead th").nth(2);

        assertThat(header).hasClass(java.util.regex.Pattern.compile("jeapTableNumeric"));
        assertThat(reactions().locator("tbody td.jeapTableNumeric")).hasCount(TIMES_OBSERVED.size());
        assertThat((String) reactions().locator("tbody td").nth(2)
                .evaluate("cell => getComputedStyle(cell).textAlign")).isEqualTo("right");
        assertThat(reactions().locator("thead th.jeapTableNumeric")).hasCount(1);

        header.locator("button").click();
        header.locator("button").click();

        assertThat(column(reactions(), 2)).containsExactly("12'408", "2'150", "987", "96", "31", "4");
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void theFilter_keepsTheRowsWithEveryWord_andMarksThem() {
        open("/" + TABLES_ROUTE + "/");
        Locator input = filterOf(relations()).locator("input");

        input.fill("orders PUBLISHES");

        Locator shown = relations().locator("tbody tr:not([hidden])");
        int count = shown.count();
        assertThat(count).isPositive().isLessThan(RELATIONS);
        for (Locator row : shown.all()) {
            assertThat(row.innerText().toLowerCase()).contains("orders", "publishes");
        }
        assertThat(filterOf(relations()).locator(".jeapTableFilterCount")).hasText(count + " of " + RELATIONS + " rows");
        assertThat(shown.first().locator("mark.jeapTableMark").first()).isVisible();
        assertThat(relations().locator("tbody tr[hidden] mark")).hasCount(0);
        // A match inside a link stays inside the link.
        Locator link = relations().locator("tbody tr:not([hidden]) td:first-child a:has(mark)").first();
        assertThat(link).hasAttribute("href", java.util.regex.Pattern.compile("#"));
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void aFilterWithoutMatch_saysSo_andEscapeShowsEveryRowAgain() {
        open("/" + TABLES_ROUTE + "/");
        Locator input = filterOf(relations()).locator("input");

        input.fill("<b>nothing</b>");

        assertThat(relations().locator("tbody tr:not([hidden]):not(.jeapTableEmpty)")).hasCount(0);
        Locator empty = relations().locator("tbody tr.jeapTableEmpty");
        assertThat(empty).hasText("No row contains “<b>nothing</b>”.");
        assertThat(empty.locator("b")).hasCount(0);
        assertThat(filterOf(relations()).locator(".jeapTableFilterCount")).hasText("0 of " + RELATIONS + " rows");

        input.press("Escape");

        assertThat(input).hasValue("");
        assertThat(relations().locator("tbody tr.jeapTableEmpty")).hasCount(0);
        assertThat(relations().locator("tbody tr:not([hidden])")).hasCount(RELATIONS);
        assertThat(filterOf(relations()).locator(".jeapTableFilterCount")).hasText(RELATIONS + " rows");
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void theClearButton_removesTheFilterAndItsMarks_andReturnsToTheInput() {
        open("/" + TABLES_ROUTE + "/");
        Locator input = filterOf(relations()).locator("input");
        Locator clear = filterOf(relations()).locator("button.jeapTableFilterClear");
        String written = relations().locator("tbody").innerHTML();
        assertThat(clear).isHidden();

        input.fill("intake");
        assertThat(clear).isVisible();
        clear.click();

        assertThat(input).isFocused();
        assertThat(clear).isHidden();
        assertThat(relations().locator("mark")).hasCount(0);
        // Both sides without the hidden attributes: a row the filter hid, and the rest of a collapsed cell,
        // which carries one before the filter runs as well.
        assertThat(relations().locator("tbody").innerHTML().replace(" hidden=\"\"", ""))
                .isEqualTo(written.replace(" hidden=\"\"", ""));
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void sortingWhileFiltered_keepsTheFilter_andTheEmptyRowLast() {
        open("/" + TABLES_ROUTE + "/");
        Locator input = filterOf(relations()).locator("input");

        input.fill("calls");
        int shown = relations().locator("tbody tr:not([hidden])").count();
        relations().locator("thead th").nth(0).locator("button").click();
        assertThat(relations().locator("tbody tr:not([hidden])")).hasCount(shown);
        assertThat(column(relations(), 0)).isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);

        input.fill("nothing-like-this");
        relations().locator("thead th").nth(0).locator("button").click();
        assertThat(relations().locator("tbody tr").last()).hasClass("jeapTableEmpty");
        assertNothingWentWrongInTheBrowser();
    }

    /** Docusaurus calls the client modules again on a route update; a table must not get a second set. */
    @Test
    void aRouteUpdateOnThePage_keepsTheSortAndAddsNothing() {
        open("/" + TABLES_ROUTE + "/");
        relations().locator("thead th").nth(1).locator("button").click();
        List<String> sorted = column(relations(), 1);
        String before = page.url();

        relations().locator("tbody a").first().click();
        page.waitForURL(url -> !url.equals(before));

        assertThat(page.locator("article .jeapTableFilter")).hasCount(1);
        assertThat(relations().locator("thead th").nth(0).locator("button")).hasCount(1);
        assertThat(relations().locator("thead th").nth(1)).hasAttribute("aria-sort", "ascending");
        assertThat(column(relations(), 1)).isEqualTo(sorted);
        assertNothingWentWrongInTheBrowser();
    }

    /** The rows were rendered by React, and moved by the module: rendering the page again must not undo it. */
    @Test
    void switchingTheColourMode_keepsTheSortAndTheFilter() {
        open("/" + TABLES_ROUTE + "/");
        relations().locator("thead th").nth(3).locator("button").click();
        filterOf(relations()).locator("input").fill("publishes");
        List<String> shown = column(relations(), 3);
        int hidden = relations().locator("tbody tr[hidden]").count();

        page.getByLabel("Switch between dark and light mode", new com.microsoft.playwright.Page.GetByLabelOptions()
                .setExact(false)).click();
        assertThat(page.locator("html")).hasAttribute("data-theme", "light");

        assertThat(page.locator("article .jeapTableFilter")).hasCount(1);
        assertThat(filterOf(relations()).locator("input")).hasValue("publishes");
        assertThat(relations().locator("tbody tr[hidden]")).hasCount(hidden);
        assertThat(column(relations(), 3)).isEqualTo(shown);
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void navigatingAwayAndBack_showsTheTableAsWritten_withOneFilter() {
        open("/" + TABLES_ROUTE + "/");
        List<String> written = column(relations(), 0);
        relations().locator("thead th").nth(0).locator("button").click();
        relations().locator("thead th").nth(0).locator("button").click();
        filterOf(relations()).locator("input").fill("intake");

        page.getByText("The page with folds").click();
        page.waitForURL(url -> url.endsWith("/" + FOLD_ROUTE + "/"));
        page.goBack();
        page.waitForURL(url -> url.endsWith("/" + TABLES_ROUTE + "/"));

        assertThat(page.locator("article .jeapTableFilter")).hasCount(1);
        assertThat(relations().locator("thead th[aria-sort]")).hasCount(0);
        assertThat(relations().locator("tbody tr:not([hidden])")).hasCount(RELATIONS);
        assertThat(column(relations(), 0)).isEqualTo(written);
        assertThat(relations().locator("thead th").nth(0).locator("button.jeapTableSort")).hasCount(1);
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void sortingAnUploadedTable_keepsTheLinkInItsCell() {
        open(TERMS_ROUTE);
        Locator table = page.locator("article table");

        table.locator("thead th").nth(1).locator("button").click();
        table.locator("thead th").nth(1).locator("button").click();

        Locator link = table.locator("a[href='https://www.bazg.admin.ch']");
        assertThat(link).hasCount(1);
        assertThat(link).hasText("customs office");
        assertThat(link.locator("xpath=ancestor::tr/td[1]")).hasText("Customs declaration");
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void anUploadedTable_isSortableAndFilterableToo() {
        open(TERMS_ROUTE);
        Locator table = page.locator("article table");
        Locator input = filterOf(table).locator("input");

        input.fill("customs");

        assertThat(filterOf(table).locator(".jeapTableFilterCount")).hasText("2 of 18 rows");
        assertThat(table.locator("tbody tr:not([hidden]) a[href='https://www.bazg.admin.ch']")).hasCount(1);

        input.press("Escape");
        table.locator("thead th").nth(2).locator("button").click();
        List<String> since = column(table, 2);
        assertThat(since.indexOf("1.9")).describedAs("1.9 comes before 1.10").isLessThan(since.indexOf("1.10"));
        assertThat(since.getFirst()).isEqualTo("1.0");
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * <b>A long cell shows its first three and a chip.</b> The cell stays complete in the page - the filter
     * and the search index read it whole - and the reader opens the rest where they want it.
     */
    @Test
    void aCellOfManyCounterparts_showsThreeAndAChipThatOpensTheRest() {
        open("/" + TABLES_ROUTE + "/");
        Locator cell = relations().locator("tbody tr").first().locator("td").nth(4);
        Locator chip = cell.locator("button.jeapCellMore");

        assertThat(chip).hasText("+2 more");
        assertThat(chip).hasAttribute("aria-expanded", "false");
        // The pact above the line belongs to its caller: it is part of that item, not an item of its own.
        assertThat(cell.locator("sup a")).hasText("pact");
        assertThat(cell.locator(".jeapCellRest")).isHidden();
        assertThat(cell.getByText(WIDE_CALLERS.get(2), new Locator.GetByTextOptions().setExact(true)))
                .isVisible();

        chip.click();

        assertThat(chip).hasText("show fewer");
        assertThat(chip).hasAttribute("aria-expanded", "true");
        assertThat(cell.locator("a[href='#" + WIDE_CALLERS.getLast() + "']")).isVisible();

        chip.click();

        assertThat(chip).hasText("+2 more");
        assertThat(cell.locator(".jeapCellRest")).isHidden();
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * <b>A cell of prose is not a list.</b> A description reading "orders, invoices, deliveries" has commas
     * and three of them would be items only to something splitting text; a separator is a child of the cell
     * that is nothing but a comma, which is what a generated list of links leaves behind.
     */
    @Test
    void aCellOfProse_isNotCollapsedHoweverManyCommasItHas() {
        open("/" + TABLES_ROUTE + "/");
        Locator descriptions = page.locator("article table").nth(2);

        assertThat(descriptions.locator("tbody td").nth(1)).hasText(PROSE_DESCRIPTION);
        assertThat(descriptions.locator("button.jeapCellMore")).hasCount(0);
        org.assertj.core.api.Assertions
                .assertThat(page.locator("article button.jeapCellMore").count())
                .describedAs("the one cell on this page that is a list of counterparts")
                .isEqualTo(1);
        assertNothingWentWrongInTheBrowser();
    }

    /** Filtering for a name the chip hides opens that cell, or the reader sees a row and not what they typed. */
    @Test
    void theFilter_opensACellThatHidesAMatch_andClosesItAgain() {
        open("/" + TABLES_ROUTE + "/");
        Locator input = filterOf(relations()).locator("input");
        Locator cell = relations().locator("tbody tr").first().locator("td").nth(4);

        input.fill(WIDE_CALLERS.getLast());

        assertThat(relations().locator("tbody tr:not([hidden])")).hasCount(1);
        Locator marked = cell.locator(".jeapCellRest mark.jeapTableMark");
        assertThat(marked).hasCount(1);
        assertThat(marked).isVisible();
        assertThat(cell.locator("button.jeapCellMore")).hasText("show fewer");

        input.press("Escape");

        assertThat(cell.locator(".jeapCellRest")).isHidden();
        assertThat(cell.locator("button.jeapCellMore")).hasText("+2 more");
        assertNothingWentWrongInTheBrowser();
    }

    /** Sorting one sub-row would tear it from its version. */
    @Test
    void aTableWithMergedCells_isLeftAlone() {
        open("/" + MESSAGE_REACTIONS_ROUTE + "/");

        Locator versions = page.locator("article table.groupedTable");
        assertThat(versions).hasCount(1);
        assertThat(versions.locator("button.jeapTableSort")).hasCount(0);
        assertThat(page.locator("article .jeapTableFilter")).hasCount(0);
        assertThat(versions).not().hasAttribute("data-jeap-doc-table-controls", "true");
        assertNothingWentWrongInTheBrowser();
    }
}
