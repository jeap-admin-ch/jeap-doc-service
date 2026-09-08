package ch.admin.bit.jeap.doc.web.site.browser;

import ch.admin.bit.jeap.doc.web.DocServiceIntegrationTestBase;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.ColorScheme;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A real browser over the running service, and nothing about what it is pointed at.
 * <p>
 * Two suites need this and need different sites: one drives the site template over a fixture built once
 * ({@link SiteBrowserTestBase}), the other watches a landscape change and rebuild
 * ({@link LandscapeChangeBrowserIT}). What they share is the browser.
 * <p>
 * <b>Chrome is a precondition of this build</b>, in the way Node and Docker are: the browser comes from the
 * machine, as it does in {@code jeap-error-handling-service}, and the CI image is the one with the browsers in
 * it. It takes both settings below to mean that - the channel says which browser to launch, and the driver
 * would still fetch its own bundles without being told not to. A suite that skipped itself where no browser is
 * present would be green because it ran nothing, on exactly the pipeline that is meant to catch this.
 */
@Slf4j
public abstract class BrowserTestBase extends DocServiceIntegrationTestBase {

    private static Playwright playwright;
    private static Browser browser;

    @Value("${local.server.port}")
    private int port;

    protected BrowserContext context;
    protected Page page;

    /** What the browser logged as an error while the current test ran - including the status of a failed load. */
    protected final List<String> consoleErrors = Collections.synchronizedList(new ArrayList<>());

    /**
     * Uncaught exceptions of the page itself. Kept apart from the console, because a console error can be the
     * browser reporting a status a test asked for, while an uncaught exception never is.
     */
    protected final List<String> pageErrors = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    static void startBrowser() {
        // Nothing is downloaded: the channel below takes the Chrome that is installed, and without this the
        // driver would still fetch its own browser bundles on the first call - hundreds of megabytes on every
        // fresh CI container, and a hard failure where the network does not allow it.
        playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setChannel("chrome"));
    }

    @AfterAll
    static void stopBrowser() {
        if (browser != null) {
            browser.close();
            browser = null;
        }
        if (playwright != null) {
            playwright.close();
            playwright = null;
        }
    }

    /** What a suite has to do before each test to have something to open. */
    protected void prepareWhatIsServed() {
        // Nothing by default: a suite that publishes its own site overrides this.
    }

    @BeforeEach
    void openPage() {
        prepareWhatIsServed();
        consoleErrors.clear();
        pageErrors.clear();
        // The reader prefers dark. Nothing here is about the palette, but it makes the colour-mode test
        // meaningful - a chosen mode has to outlast a system preference that disagrees with it - and it means
        // every other test runs over the dark tokens rather than never over them.
        context = browser.newContext(new Browser.NewContextOptions()
                .setLocale("en-US")
                .setColorScheme(ColorScheme.DARK));
        page = context.newPage();
        page.setDefaultTimeout(20_000);
        PlaywrightAssertions.setDefaultAssertionTimeout(15_000);
        page.onConsoleMessage(message -> {
            log.info("Browser console: {}: {}", message.type(), message.text());
            if ("error".equals(message.type())) {
                consoleErrors.add(message.text());
            }
        });
        page.onPageError(error -> {
            log.warn("Browser page error: {}", error);
            pageErrors.add(error);
        });
    }

    @AfterEach
    void closePage() {
        if (context != null) {
            context.close();
            context = null;
        }
    }

    /**
     * The site as the reader reaches it: through the service, not from a directory.
     */
    protected String url(String path) {
        return "http://localhost:" + port + path;
    }

    /**
     * Opens a path of the site and waits until the React application has taken over - every assertion here is
     * about what the application does, and the server-rendered markup would answer some of them wrongly.
     *
     * @return what the service answered for the document itself, after any redirect it was sent through
     */
    protected Response open(String path) {
        Response response = page.navigate(url(path));
        page.waitForFunction("() => document.documentElement.dataset.hasHydrated === 'true'");
        return response;
    }

    /**
     * That the browser reported nothing while the test ran. Cheap, and the only thing that would catch a
     * Content-Security-Policy violation, a chunk that failed to load or a React error boundary.
     */
    protected void assertNothingWentWrongInTheBrowser() {
        assertThat(pageErrors).describedAs("what the page threw while the test ran").isEmpty();
        assertThat(consoleErrors).describedAs("what the browser logged while the test ran").isEmpty();
    }
}
