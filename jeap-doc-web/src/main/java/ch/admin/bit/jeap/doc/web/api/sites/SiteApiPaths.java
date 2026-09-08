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
     * The builds of one site - every part of it.
     */
    public static final String BUILDS = SITE + "/builds";

    /**
     * The parts one site is published as. A site is generated as several Docusaurus builds, and this is where
     * an operator sees them: what each carries, what is published for it and whether it is owed a build.
     */
    public static final String PARTS = SITE + "/parts";

    /** The builds of one part. There is no resource for the part itself: the index above carries them all. */
    public static final String PART_BUILDS = PARTS + "/{part}/builds";

    /** One build of a site, by the identifier the history and the log lines name it with. */
    public static final String BUILD = BUILDS + "/{buildId}";


    private SiteApiPaths() {
    }
}
