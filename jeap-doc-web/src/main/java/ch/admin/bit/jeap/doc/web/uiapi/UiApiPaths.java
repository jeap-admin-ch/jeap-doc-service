package ch.admin.bit.jeap.doc.web.uiapi;

import ch.admin.bit.jeap.doc.domain.DocumentationLiveStatus;

/**
 * The API the pages of the documentation sites read in the reader's browser.
 * <p>
 * <b>A root of its own, beside {@code /api} and not below it.</b> {@code /api} is called by pipelines and other
 * services and needs a bearer token without exception; a page has no token to send. So what a page reads is
 * open - and read-only - and it lives where that rule can be one security filter chain rather than a list of
 * exceptions carved into the other one. Nothing below this root may answer what the sites themselves would not
 * show.
 */
public final class UiApiPaths {

    public static final String UI_API = "/ui-api";

    /** The system is a query parameter, as everywhere else in the API: a name may hold what a path cannot. */
    public static final String GOVERNANCE_EXCERPT = UI_API + "/excerpts/governance/systems";

    /** Spelled in the domain, because the site generator writes it into the About page as well. */
    public static final String LIVE_STATUS = DocumentationLiveStatus.PATH;

    private UiApiPaths() {
    }
}
