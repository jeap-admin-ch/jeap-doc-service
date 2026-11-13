package ch.admin.bit.jeap.doc.web.site.browser;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An uploaded page placed next to a page the service generates, in a real build.
 * <p>
 * The fixture is {@link SiteBrowserTestBase}'s own: {@link UploadedDocumentation} uploads two pages into
 * {@link UploadedDocumentation#PLACEMENT_CHAPTER} of {@link SiteBrowserTestBase#DOCUMENTED_SYSTEM}, where the
 * service generates the libraries group. One page names {@code sidebar_before: libraries} and has the title that
 * sorts last. The other names nothing and has the title that sorts first. The positions come from
 * {@code ChapterOrder}; what a reader sees is Docusaurus ordering by them, beside a position the template wrote.
 * <p>
 * Sorted by title, the two uploaded pages would come out the other way round, so only the placement passes.
 */
class SiblingPlacementBrowserIT extends SiteBrowserTestBase {

    private static final String CHAPTER_ROUTE =
            "systems/" + DOCUMENTED_SYSTEM + "/system-architecture/building-block-view";

    private static final List<String> PLACED_ORDER = List.of(UploadedDocumentation.PLACED_PAGE_TITLE,
            UploadedDocumentation.GENERATED_SIBLING_LABEL, UploadedDocumentation.FREE_PAGE_TITLE);

    private static Locator chapterContents(Page page) {
        return page.locator("h2").filter(new Locator.FilterOptions().setHasText("In this chapter"))
                .locator("xpath=following-sibling::ul[1]");
    }

    /** The sidebar category of the chapter, by the link it opens with. */
    private static Locator chapterInTheSidebar(Page page) {
        return page.locator("nav.menu li.theme-doc-sidebar-item-category").filter(new Locator.FilterOptions()
                .setHas(page.locator(":scope > .menu__list-item-collapsible > a[href$='/" + CHAPTER_ROUTE + "/']")));
    }

    @Test
    void aPagePlacedBeforeAGeneratedGroup_comesDirectlyBeforeItInTheChaptersList() {
        open("/" + CHAPTER_ROUTE + "/");

        List<String> labels = chapterContents(page).locator("li a").allTextContents();
        assertThat(labels).containsExactlyElementsOf(PLACED_ORDER);
        assertNothingWentWrongInTheBrowser();
    }

    /** The list is read off the sidebar, and the sidebar is what a reader navigates by - so check it too. */
    @Test
    void aPagePlacedBeforeAGeneratedGroup_comesDirectlyBeforeItInTheSidebar() {
        open("/" + CHAPTER_ROUTE + "/");

        List<String> labels = chapterInTheSidebar(page)
                .locator(":scope > ul > li > a, :scope > ul > li > .menu__list-item-collapsible > a")
                .allTextContents();
        assertThat(labels).containsExactlyElementsOf(PLACED_ORDER);
        assertNothingWentWrongInTheBrowser();
    }
}
