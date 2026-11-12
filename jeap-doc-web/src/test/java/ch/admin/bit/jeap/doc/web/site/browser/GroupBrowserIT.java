package ch.admin.bit.jeap.doc.web.site.browser;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A folder directly below a chapter that holds a page is a group: its own landing page, its own pages, and a
 * sidebar category that starts collapsed.
 * <p>
 * The fixture is {@link SiteBrowserTestBase}'s own: {@link UploadedDocumentation} uploads a group named
 * {@link UploadedDocumentation#GROUP_FOLDER} into {@link UploadedDocumentation#GROUP_CHAPTER} of
 * {@link SiteBrowserTestBase#DOCUMENTED_SYSTEM}, with an uploaded landing page and two pages of its own -
 * written the way {@code CustomPagesWriter} really writes a group, copied by hand.
 */
class GroupBrowserIT extends SiteBrowserTestBase {

    private static final String CHAPTER_ROUTE =
            "systems/" + DOCUMENTED_SYSTEM + "/system-architecture/architecture-decision-records";
    private static final String GROUP_ROUTE = CHAPTER_ROUTE + "/" + UploadedDocumentation.GROUP_FOLDER;

    private static final Pattern COLLAPSED = Pattern.compile("menu__list-item--collapsed");

    /** The list item of the category whose link ends with the given path. */
    private static Locator categoryOf(Locator sidebar, String hrefEnd) {
        return sidebar.locator("li.theme-doc-sidebar-item-category").filter(new Locator.FilterOptions()
                .setHas(sidebar.page().locator(":scope > .menu__list-item-collapsible > a[href$='" + hrefEnd + "']")));
    }

    private static Locator chapterContents(com.microsoft.playwright.Page page) {
        return page.locator("h2").filter(new Locator.FilterOptions().setHasText("In this chapter"))
                .locator("xpath=following-sibling::ul[1]");
    }

    @Test
    void theGroup_startsCollapsedInTheSidebar() {
        open("/" + CHAPTER_ROUTE + "/");

        Locator sidebar = page.locator("nav.menu");
        Locator group = categoryOf(sidebar, "/" + UploadedDocumentation.GROUP_FOLDER + "/");
        PlaywrightAssertions.assertThat(group).isVisible();
        PlaywrightAssertions.assertThat(group).hasClass(COLLAPSED);
        PlaywrightAssertions.assertThat(sidebar.locator("a").filter(
                new Locator.FilterOptions().setHasText(UploadedDocumentation.GROUP_PAGE_ONE_TITLE))).not().isVisible();
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void openingTheGroup_showsItsOwnPages() {
        open("/" + CHAPTER_ROUTE + "/");
        Locator sidebar = page.locator("nav.menu");
        Locator group = categoryOf(sidebar, "/" + UploadedDocumentation.GROUP_FOLDER + "/");

        group.locator(":scope > .menu__list-item-collapsible > button.menu__caret").click();

        PlaywrightAssertions.assertThat(group).not().hasClass(COLLAPSED);
        PlaywrightAssertions.assertThat(sidebar.locator("a").filter(
                new Locator.FilterOptions().setHasText(UploadedDocumentation.GROUP_PAGE_ONE_TITLE))).isVisible();
        assertNothingWentWrongInTheBrowser();
    }

    /** The group's landing page is a team's own, but the site generator still lists its pages below it. */
    @Test
    void theGroupsLandingPage_listsItsOwnPagesInTitleOrder() {
        open("/" + GROUP_ROUTE + "/");

        PlaywrightAssertions.assertThat(page.locator("h1")).hasText(UploadedDocumentation.GROUP_LABEL);
        List<String> labels = chapterContents(page).locator("li a").allTextContents();
        assertThat(labels).containsExactly(UploadedDocumentation.GROUP_PAGE_ONE_TITLE,
                UploadedDocumentation.GROUP_PAGE_TWO_TITLE);
        assertNothingWentWrongInTheBrowser();
    }

    /** Each entry of the group's own list is a working link into the group, not the chapter around it. */
    @Test
    void anEntryOfTheGroupsList_isALinkToItsOwnPage() {
        open("/" + GROUP_ROUTE + "/");

        chapterContents(page).locator("li a").filter(new Locator.FilterOptions()
                .setHasText(UploadedDocumentation.GROUP_PAGE_ONE_TITLE)).click();

        PlaywrightAssertions.assertThat(page.locator("h1")).hasText(UploadedDocumentation.GROUP_PAGE_ONE_TITLE);
        assertThat(page.url()).endsWith("/" + GROUP_ROUTE + "/" + UploadedDocumentation.GROUP_PAGE_ONE + "/");
        assertNothingWentWrongInTheBrowser();
    }

    /** The chapter's own landing page lists the group once, by its label - not its two pages separately. */
    @Test
    void theChaptersOwnLandingPage_listsTheGroupAsOneEntry() {
        open("/" + CHAPTER_ROUTE + "/");

        List<String> labels = chapterContents(page).locator("li a").allTextContents();
        assertThat(labels).containsExactly(UploadedDocumentation.GROUP_LABEL);
        assertNothingWentWrongInTheBrowser();
    }
}
