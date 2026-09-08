package ch.admin.bit.jeap.doc.web.api.architecture;

/**
 * The paths the architecture import endpoints are served at.
 * <p>
 * <b>Its own root, and not below {@code /api/sites}.</b> An import belongs to an environment rather than to a
 * site: one doc service reads an architecture repository per stage, and every site that carries that
 * environment is generated from the same import. Hanging it under a site would say the opposite.
 */
public final class ArchitectureApiPaths {

    /** The architecture repositories this instance reads. */
    public static final String ARCHITECTURE = "/api/architecture";

    /**
     * The imports of every environment. A {@code POST} here asks for all of them, which is what to use after
     * changing an architecture repository's content by hand.
     */
    public static final String IMPORTS = ARCHITECTURE + "/imports";

    /** The environments an architecture repository is configured for, with what their last import did. */
    public static final String ENVIRONMENTS = ARCHITECTURE + "/environments";

    /** The imports of one environment. */
    public static final String ENVIRONMENT_IMPORTS = ENVIRONMENTS + "/{environment}/imports";

    private ArchitectureApiPaths() {
    }
}
