package ch.admin.bit.jeap.doc.web.site.browser;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A library, in a browser: beside the components of its system, with the pages the service generates for one
 * and the chapters its team wrote.
 * <p>
 * No architecture model holds a library, so everything here came out of an upload.
 */
class LibraryBrowserIT extends SiteBrowserTestBase {

    private String librariesGroup() {
        return "/systems/" + DOCUMENTED_SYSTEM
               + "/system-architecture/building-block-view/libraries/";
    }

    @Test
    void theLibrariesGroup_isReadableAndNotOnlyExpandable() {
        Response response = open(librariesGroup());

        assertThat(response.status()).describedAs("a group with no index page is one a reader can only "
                                                  + "expand").isEqualTo(200);
        PlaywrightAssertions.assertThat(page.getByRole(AriaRole.HEADING,
                new Page.GetByRoleOptions().setName("Libraries").setLevel(1))).isVisible();
        // Scoped to the page itself: the sidebar and the pagination link to it too, and Playwright's strict
        // mode refuses a locator that matches three elements.
        PlaywrightAssertions.assertThat(page.getByRole(AriaRole.ARTICLE).getByRole(AriaRole.LINK,
                new Locator.GetByRoleOptions().setName(DOCUMENTED_LIBRARY))).isVisible();
    }

    @Test
    void theLibrary_isInTheSidebarBesideTheComponents() {
        open(librariesGroup());

        Locator menu = page.locator("nav.menu");
        PlaywrightAssertions.assertThat(menu).containsText("Libraries");
        PlaywrightAssertions.assertThat(menu).containsText(DOCUMENTED_LIBRARY);
    }

    @Test
    void theLibrary_hasItsOwnPageAndItsOwnStructure() {
        assertThat(open(librariesGroup() + DOCUMENTED_LIBRARY + "/").status()).isEqualTo(200);
        PlaywrightAssertions.assertThat(page.getByRole(AriaRole.ARTICLE).getByRole(AriaRole.LINK,
                new Locator.GetByRoleOptions().setName("Library Architecture"))).isVisible();

        Response structure = open("/" + DOCUMENTED_LIBRARY_ROUTE + "/");
        assertThat(structure.status()).isEqualTo(200);
        PlaywrightAssertions.assertThat(page.getByRole(AriaRole.HEADING,
                new Page.GetByRoleOptions().setName("Library Architecture - " + DOCUMENTED_LIBRARY)
                        .setLevel(1))).isVisible();
    }

    /** The one page the service writes for a library: what the upload said about it. */
    @Test
    void theOverviewPage_showsWhatTheUploadSaid() {
        Response response = open("/" + DOCUMENTED_LIBRARY_ROUTE + "/intro/library-overview/");

        assertThat(response.status()).isEqualTo(200);
        PlaywrightAssertions.assertThat(page.getByText(UploadedDocumentation.LIBRARY_VERSION)).isVisible();
        PlaywrightAssertions.assertThat(page.getByText(UploadedDocumentation.REVISION).first()).isVisible();
    }

    @Test
    void aChapterTheTeamWrote_isServed() {
        Response response = open("/" + DOCUMENTED_LIBRARY_ROUTE + "/glossary/terms/");

        assertThat(response.status()).isEqualTo(200);
        PlaywrightAssertions.assertThat(page.getByRole(AriaRole.HEADING,
                new Page.GetByRoleOptions().setName("The terms of the client").setLevel(1))).isVisible();
    }
}
