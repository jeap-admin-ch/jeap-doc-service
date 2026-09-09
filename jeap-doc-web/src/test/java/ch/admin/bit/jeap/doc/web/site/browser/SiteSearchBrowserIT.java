package ch.admin.bit.jeap.doc.web.site.browser;

import ch.admin.bit.jeap.doc.domain.BuildProperties;
import ch.admin.bit.jeap.doc.domain.PublicationProperties;
import ch.admin.bit.jeap.doc.domain.SearchIndex;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SiteEnvironment;
import ch.admin.bit.jeap.doc.domain.SitePart;
import ch.admin.bit.jeap.doc.domain.port.BuiltSearchIndex;
import ch.admin.bit.jeap.doc.domain.port.PartPublication;
import ch.admin.bit.jeap.doc.domain.port.SearchIndexRepository;
import ch.admin.bit.jeap.doc.domain.port.SitePublicationStorage;
import ch.admin.bit.jeap.doc.sitegenerator.BuildWorkspaces;
import ch.admin.bit.jeap.doc.sitegenerator.NodeProcess;
import ch.admin.bit.jeap.doc.sitegenerator.PagefindSearchIndexBuilder;
import ch.admin.bit.jeap.doc.sitegenerator.SiteUrls;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The search, driven in a real browser against the running service.
 * <p>
 * <b>Against the service and not the files</b>, for the reason the whole browser suite exists: the
 * {@code Content-Security-Policy} applies to every path of a site, and it is what would silently stop the WASM
 * engine loading or the index being fetched. A suite serving the generated files itself sends no policy and
 * would be green either way.
 * <p>
 * And it types <b>key by key</b> rather than setting a value. The box loads the index when it is first used
 * and searches what has been typed; a value that arrives in one go can land before anything is listening -
 * which is a lesson from the suite this replaces, where the index was empty for four review rounds while every
 * test passed, because asserting that a file exists says nothing about whether typing a word finds anything.
 */
class SiteSearchBrowserIT extends SiteBrowserTestBase {

    /** A word in the body of the guide page and in no title or heading - so finding it needs the whole page. */
    private static final String IN_THE_BODY = "pipeline";

    /** A word that occurs only inside a fenced diagram, which is not documentation and is not indexed. */
    private static final String IN_A_DIAGRAM = "skinparam";

    @Autowired
    private SearchIndexRepository indexes;

    @Autowired
    private SitePublicationStorage publication;

    private static boolean indexed;

    @Override
    protected void prepareWhatIsServed() {
        super.prepareWhatIsServed();
        indexOnce();
    }

    @Test
    void searchBox_thenItIsInTheNavbar() {
        open("/");

        assertThat(searchBox()).isVisible();
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * The whole page is indexed. A reader who remembers a word from the middle of a page has to find the page
     * it is on, which is what the headings-only index measured for the alternative would not have given them.
     */
    @Test
    void searchBox_whenAWordFromTheBodyIsTyped_thenTheHitLeadsToThePageItIsOn() {
        open("/");

        typeIntoTheSearchBox(IN_THE_BODY);

        assertThat(firstHit()).isVisible();
        firstHit().click();
        page.waitForURL(url("/" + GUIDE_ROUTE + "/"));
        assertThat(guideTitle()).isVisible();
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * <b>A hit is followed with a page load, never through the router.</b>
     * <p>
     * The index is one over the whole site while a site is published as one Docusaurus build per part, so a
     * hit is routinely a page the build the reader is in has no route for - and the router would answer it
     * with that build's own "Page Not Found" instead of the page. It is the same rule the generator follows
     * when it rewrites a link that leaves a part to {@code pathname://}.
     * <p>
     * Asserted by whether the document survived rather than by publishing a site in two parts: what a second
     * part would add is a second Docusaurus build of three quarters of a minute, and the thing that goes
     * wrong is one navigation either way.
     */
    @Test
    void searchBox_whenAHitIsFollowed_thenTheBrowserLoadsThePageRatherThanRoutingToIt() {
        open("/");
        page.evaluate("() => { window.thisDocumentIsStillHere = true; }");

        typeIntoTheSearchBox(IN_THE_BODY);
        assertThat(firstHit()).isVisible();
        firstHit().click();
        page.waitForURL(url("/" + GUIDE_ROUTE + "/"));

        assertNull(page.evaluate("() => window.thisDocumentIsStillHere ?? null"),
                "the hit was routed to rather than loaded, so a hit in another part would have 404ed");
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * <b>The search is the tree the reader is in.</b> The environments hold the same pages, so a query answered
     * over all of them offers the same page once per environment and leaves the reader to find the tree they
     * were already in. The guide page says which copy it is, so the hit can be checked rather than the URL
     * guessed at.
     */
    @Test
    void searchBox_whenReadingAnEnvironment_thenOnlyThatEnvironmentsCopyIsFound() {
        SiteEnvironment dev = environmentNamed("dev");
        open("/" + dev.id() + "/" + GUIDE_ROUTE + "/");

        typeIntoTheSearchBox(IN_THE_BODY);
        assertThat(firstHit()).isVisible();
        firstHit().click();

        page.waitForURL(url("/" + dev.id() + "/" + GUIDE_ROUTE + "/"));
        assertThat(page.getByText(guideMarkerOf(dev))).isVisible();
        assertNothingWentWrongInTheBrowser();
    }

    /** And at the site root it is the main environment, which is the tree with no prefix of its own. */
    @Test
    void searchBox_whenAtTheSiteRoot_thenTheMainEnvironmentsCopyIsFound() {
        open("/");

        typeIntoTheSearchBox(IN_THE_BODY);
        assertThat(firstHit()).isVisible();
        firstHit().click();

        page.waitForURL(url("/" + GUIDE_ROUTE + "/"));
        assertThat(page.getByText(guideMarkerOf(mainEnvironment()))).isVisible();
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * The two halves together: the switcher changes the tree and the search follows it without being told.
     * Neither feature is worth much if it stops at the other one's edge.
     */
    @Test
    void searchBox_whenTheEnvironmentIsSwitchedFirst_thenTheSearchFollowsIt() {
        SiteEnvironment dev = environmentNamed("dev");
        open("/" + GUIDE_ROUTE + "/");

        switcher().click();
        environmentLink(dev).click();
        page.waitForURL(url("/" + dev.id() + "/" + GUIDE_ROUTE + "/"));

        typeIntoTheSearchBox(IN_THE_BODY);
        assertThat(firstHit()).isVisible();
        firstHit().click();

        page.waitForURL(url("/" + dev.id() + "/" + GUIDE_ROUTE + "/"));
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * Nobody searches for {@code skinparam}. The plugin this replaces indexed every diagram on the site,
     * because it worked on the rendered HTML and had to be told about them as CSS selectors.
     */
    @Test
    void searchBox_whenAWordFromADiagramIsTyped_thenNothingIsFound() {
        open("/");

        typeIntoTheSearchBox(IN_A_DIAGRAM);

        assertThat(page.getByText("No page of", new Page.GetByTextOptions().setExact(false))).isVisible();
        assertNothingWentWrongInTheBrowser();
    }

    /** Escape closes the results, which is how a keyboard user leaves an open menu. */
    @Test
    void searchBox_whenOpenedAndEscapeIsPressed_thenTheResultsClose() {
        open("/");

        typeIntoTheSearchBox(IN_THE_BODY);
        assertThat(firstHit()).isVisible();

        page.keyboard().press("Escape");

        assertThat(firstHit()).isHidden();
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * <b>A hit leads to the page's route, not to its path.</b> The chapter folders are numbered on disk and
     * Docusaurus serves them without the number, so an index that took the path for the route offered a dead
     * link for every generated chapter - which is what a deployed site showed and no test here did, until this
     * fixture grew a numbered folder.
     */
    @Test
    void searchBox_whenTheHitIsInANumberedChapter_thenItLeadsToThePageRatherThanTo404() {
        open("/");

        typeIntoTheSearchBox(COMPONENT_PAGE_WORD);

        assertThat(firstHit()).isVisible();
        firstHit().click();
        page.waitForURL(url("/" + COMPONENT_PAGE_ROUTE + "/"));
        assertThat(page.getByRole(AriaRole.HEADING,
                new Page.GetByRoleOptions().setName("6. Runtime View"))).isVisible();
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * <b>And a hit says where it is.</b> Every component of a system has a page called "6. Runtime View", so
     * the title alone cannot tell a reader which one they have found; the system and the component do.
     */
    @Test
    void searchBox_thenAHitNamesTheSystemAndTheComponentItIsIn() {
        open("/");

        typeIntoTheSearchBox(COMPONENT_PAGE_WORD);

        assertThat(firstHit()).isVisible();
        assertThat(firstHit()).containsText(SYSTEM);
        assertThat(firstHit()).containsText(COMPONENT);
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * <b>And that location stays on one line.</b> A trail that wraps reads as two hits rather than as one
     * location, and in the navbar's dropdown a system and a component of a real platform are around fifty
     * characters together - which is what the dropdown is now wide enough for.
     * <p>
     * Asserted by the rule rather than by the height: this fixture's names are short and would fit on one line
     * whatever the rule said, so a measurement here would pass over the change that broke it.
     */
    @Test
    void searchBox_thenWhereAHitIsStaysOnOneLine() {
        open("/");

        typeIntoTheSearchBox(COMPONENT_PAGE_WORD);

        assertThat(firstHit()).isVisible();
        Locator trail = firstHit().locator("[class*=where]").first();
        assertThat(trail).isVisible();
        assertEquals("nowrap", trail.evaluate("trail => getComputedStyle(trail).flexWrap"),
                "the trail may not wrap");
        assertEquals("nowrap",
                trail.evaluate("trail => getComputedStyle(trail.firstElementChild).whiteSpace"),
                "a step of the trail may not wrap");
        assertNothingWentWrongInTheBrowser();
    }

    /** The words that matched are marked in the excerpt, which is what says why a page is in the list. */
    @Test
    void searchBox_thenTheMatchedWordIsMarkedInTheExcerpt() {
        open("/");

        typeIntoTheSearchBox(COMPONENT_PAGE_WORD);

        assertThat(firstHit()).isVisible();
        assertThat(firstHit().locator("mark").first()).hasText(COMPONENT_PAGE_WORD);
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * The results page carries a box of its own, so that a reader who has arrived there can go on searching
     * rather than go back to the navbar. What they type becomes the query in the URL, so a result set stays a
     * link somebody can share.
     */
    @Test
    void searchPage_whenAQueryIsTypedIntoItsOwnBox_thenTheUrlAndTheResultsFollow() {
        open("/search/?q=" + IN_THE_BODY);
        assertThat(resultsPageBox()).hasValue(IN_THE_BODY);

        resultsPageBox().fill("");
        resultsPageBox().pressSequentially(COMPONENT_PAGE_WORD,
                new Locator.PressSequentiallyOptions().setDelay(40));

        page.waitForURL("**/search/?q=" + COMPONENT_PAGE_WORD + "**");
        assertThat(page.locator("a[href='/" + COMPONENT_PAGE_ROUTE + "/']").first()).isVisible();
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * <b>And it has no environment control of its own.</b> The navbar's switcher is the site's one, this page
     * included: a second one beside it would say the same thing twice, and the two could disagree.
     */
    @Test
    void searchPage_thenTheEnvironmentIsSwitchedInTheNavbarAndNowhereElse() {
        SiteEnvironment dev = environmentNamed("dev");

        open("/search/?q=" + IN_THE_BODY + "&env=" + dev.id());

        assertThat(page.locator("#search-environment")).hasCount(0);
        // The switcher shows the environment the results are of, although the path is the site root's.
        assertThat(switcher()).containsText(dev.label());
        assertNothingWentWrongInTheBrowser();
    }

    /** And switching it there stays on the results page, with the query kept and the tree changed. */
    @Test
    void searchPage_whenTheEnvironmentIsSwitchedInTheNavbar_thenTheSameQueryIsAnsweredForThatTree() {
        SiteEnvironment dev = environmentNamed("dev");
        open("/search/?q=" + COMPONENT_PAGE_WORD);

        switcher().click();
        environmentLink(dev).click();

        page.waitForURL("**/search/?q=" + COMPONENT_PAGE_WORD + "&env=" + dev.id());
        assertThat(page.locator("a[href='/" + dev.id() + "/" + COMPONENT_PAGE_ROUTE + "/']").first()).isVisible();
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * The search page is the shell part's, at the site root, and it takes its scope from the query rather than
     * from the path it is on - which is the one thing about it that is not like every other page.
     */
    @Test
    void searchPage_whenOpenedForAnEnvironment_thenItFindsThatEnvironmentsCopy() {
        SiteEnvironment dev = environmentNamed("dev");

        open("/search/?q=" + IN_THE_BODY + "&env=" + dev.id());

        // By where the result leads rather than by what it is called: a result's title comes from the page's
        // front matter, and this fixture's guide page has none - so its title is its file name, which is the
        // documented fallback and not what this test is about.
        Locator result = page.locator("a[href='/" + dev.id() + "/" + GUIDE_ROUTE + "/']").first();
        assertThat(result).isVisible();

        // And it is followed, in this tab: the results are plain anchors rather than router links, for the
        // reason the navbar's box loads rather than routes - a result belongs to any part of the site.
        result.click();

        page.waitForURL(url("/" + dev.id() + "/" + GUIDE_ROUTE + "/"));
        assertThat(page.getByText(guideMarkerOf(dev))).isVisible();
        assertNothingWentWrongInTheBrowser();
    }

    private Locator searchBox() {
        return page.locator("input.navbar__search-input").first();
    }

    /** The results page's own box, which is the one that is not the navbar's. */
    private Locator resultsPageBox() {
        return page.locator("input[type=search]:not(.navbar__search-input)").first();
    }

    /**
     * Puts a word into the box a key at a time, which is what opens the results: the box loads its index when
     * it is first used and searches what has been typed, and it debounces - so this waits for a hit rather
     * than assuming one is there.
     */
    private void typeIntoTheSearchBox(String word) {
        searchBox().click();
        searchBox().pressSequentially(word, new Locator.PressSequentiallyOptions().setDelay(40));
    }

    /** The first result in the dropdown. */
    private Locator firstHit() {
        return page.getByRole(AriaRole.OPTION).first();
    }

    private Locator guideTitle() {
        return page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("The upload guide"));
    }

    private Locator switcher() {
        return page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Switch environment").setExact(false));
    }

    private Locator environmentLink(SiteEnvironment environment) {
        // Scoped to the navbar: the footer's Environments group links to the same environments by the same
        // names, and an unscoped role-and-name query matches both.
        return page.locator(".navbar").getByRole(AriaRole.LINK,
                new Locator.GetByRoleOptions().setName(environment.label()).setExact(false));
    }

    private SiteEnvironment environmentNamed(String id) {
        return environments().stream().filter(environment -> environment.id().equals(id)).findFirst()
                .orElseThrow();
    }

    private SiteEnvironment mainEnvironment() {
        return environments().stream().filter(SiteEnvironment::main).findFirst().orElseThrow();
    }

    /**
     * Builds a real index over the same pages the served site was built from, and publishes it as the site's
     * current one - once for the whole JVM, as the site itself is.
     * <p>
     * It goes through {@link PagefindSearchIndexBuilder}, which writes the content itself: that is what makes
     * this an index of the same documentation rather than of a fixture that happens to look like it.
     */
    private void indexOnce() {
        synchronized (SiteSearchBrowserIT.class) {
            if (indexed) {
                return;
            }
            Site site = defaultSite();
            BuiltSearchIndex built = indexBuilder().build(site, SitePart.wholeSiteOf(site));
            long id = indexes.start(site.id(), "browser-test", Instant.now());
            String prefix = SearchIndex.prefixOf(site.id(), id);
            publication.publish(new PartPublication(prefix, prefix), built.directory());
            indexes.published(id, prefix, built.records(), Instant.now());
            indexBuilder().discard(built);
            indexed = true;
        }
    }

    private PagefindSearchIndexBuilder indexBuilder() {
        BuildProperties properties = new BuildProperties();
        properties.setNodeModulesDirectory(Path.of("target/site-install/node_modules").toAbsolutePath());
        properties.setWorkspaceDirectory(searchWorkspace());
        PublicationProperties publication = new PublicationProperties();
        publication.setUrl("http://localhost");
        SiteUrls urls = new SiteUrls(publication, "");
        return new PagefindSearchIndexBuilder(properties, new BuildWorkspaces(properties),
                fixtureSources(urls, properties), new NodeProcess(properties),
                new DefaultResourceLoader(), Clock.systemUTC());
    }

    private static Path searchWorkspace() {
        try {
            return Files.createTempDirectory("jeap-doc-search-index-test");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
