package ch.admin.bit.jeap.doc.web.site.browser;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.ColorScheme;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A chapter's landing page lists its own contents, read off the sidebar rather than written into the page.
 * <p>
 * The fixture is {@link SiteBrowserTestBase}'s own, unchanged: the glossary chapter of
 * {@link SiteBrowserTestBase#DOCUMENTED_SYSTEM} has nothing generated into it and two uploaded pages, its
 * constraints chapter mixes three uploaded pages with the uploaded microsite, and its introduction chapter has
 * the one page the service generates for a subject the architecture model does not hold. Between the three,
 * every kind of item - generated, uploaded, microsite - appears in a chapter's list at least once, each exactly
 * where {@code ChapterOrder} put it.
 */
class ChapterContentsBrowserIT extends SiteBrowserTestBase {

    private static final String INTRO_ROUTE = "systems/" + DOCUMENTED_SYSTEM + "/system-architecture/intro";
    private static final String CONSTRAINTS_ROUTE =
            "systems/" + DOCUMENTED_SYSTEM + "/system-architecture/constraints";

    private static Locator chapterContents(Page page) {
        return page.locator("h2").filter(new Locator.FilterOptions().setHasText("In this chapter"))
                .locator("xpath=following-sibling::ul[1]");
    }

    /** A chapter with nothing generated into it, and two uploaded pages: free items, ordered by their titles. */
    @Test
    void aChapterOfOnlyUploadedPages_listsThemInTheSidebarsOrder() {
        open("/" + DOCUMENTED_CHAPTER_ROUTE + "/");

        List<String> labels = chapterContents(page).locator("li a").allTextContents();
        assertThat(labels).containsExactly("A backorder and what it is", "Z is for the last word");
        assertNothingWentWrongInTheBrowser();
    }

    /** Each entry is a working link, to the page the sidebar names it for - not only text that looks like one. */
    @Test
    void anEntry_isALinkToItsOwnPage() {
        open("/" + DOCUMENTED_CHAPTER_ROUTE + "/");

        chapterContents(page).locator("li a").filter(new Locator.FilterOptions()
                .setHasText("A backorder and what it is")).click();

        PlaywrightAssertions.assertThat(page.locator("h1"))
                .hasText("A " + UploadedDocumentation.GLOSSARY_WORD + " and what it is");
        assertNothingWentWrongInTheBrowser();
    }

    /** A chapter mixing uploaded pages and the uploaded microsite - one list, by the title or label each shows. */
    @Test
    void aChapterOfUploadedPagesAndAMicrosite_listsBothKinds() {
        open("/" + CONSTRAINTS_ROUTE + "/");

        List<String> labels = chapterContents(page).locator("li a").allTextContents();
        assertThat(labels).containsExactly("Raw HTML as it was uploaded", "Terms of the trade",
                "What was given", UploadedDocumentation.MICROSITE_LABEL);
        assertNothingWentWrongInTheBrowser();
    }

    /** The one page the service generates for a subject the architecture model does not hold. */
    @Test
    void aChapterOfOnlyAGeneratedPage_listsIt() {
        open("/" + INTRO_ROUTE + "/");

        List<String> labels = chapterContents(page).locator("li a").allTextContents();
        assertThat(labels).containsExactly("Not in the Architecture Model");
        assertNothingWentWrongInTheBrowser();
    }

    /** A page that is not a chapter's own landing page carries no flag, and shows no list at all. */
    @Test
    void aPageWithoutTheFlag_showsNoList() {
        open("/" + DOCUMENTED_CHAPTER_ROUTE + "/zzz-first/");

        PlaywrightAssertions.assertThat(page.getByText("In this chapter")).not().isVisible();
        assertNothingWentWrongInTheBrowser();
    }

    /** Server-rendered too, not only after hydration - so a reader with no JavaScript still sees it. */
    @Test
    void theList_isInTheServerRenderedMarkup() {
        String html = page.navigate(url("/" + DOCUMENTED_CHAPTER_ROUTE + "/")).text();

        assertThat(html).contains("In this chapter")
                .contains("A backorder and what it is")
                .contains("Z is for the last word");
    }

    /** Nothing here reads the colour mode, so the same list renders in light mode too. */
    @Test
    void theList_rendersInLightModeToo() {
        page.emulateMedia(new Page.EmulateMediaOptions().setColorScheme(ColorScheme.LIGHT));

        open("/" + DOCUMENTED_CHAPTER_ROUTE + "/");

        List<String> labels = chapterContents(page).locator("li a").allTextContents();
        assertThat(labels).containsExactly("A backorder and what it is", "Z is for the last word");
        assertNothingWentWrongInTheBrowser();
    }
}
