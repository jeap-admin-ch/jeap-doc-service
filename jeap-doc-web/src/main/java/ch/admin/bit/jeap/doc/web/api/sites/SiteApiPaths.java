package ch.admin.bit.jeap.doc.web.api.sites;

/**
 * The paths the site administration endpoints are served at.
 * <p>
 * A site is the thing an operator acts on - it is asked to be published, and it is asked what it has been doing -
 * so the builds hang below the site they belong to rather than forming a family of their own.
 */
public final class SiteApiPaths {

    /**
     * The documentation sites of this instance.
     */
    public static final String SITES = "/api/sites";

    /**
     * One site.
     */
    public static final String SITE = SITES + "/{site}";

    /**
     * The builds of one site.
     */
    public static final String BUILDS = SITE + "/builds";

    private SiteApiPaths() {
    }
}
