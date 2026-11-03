package ch.admin.bit.jeap.doc.domain.governance;

/**
 * Where a page of the governance site is served, as a link written on another page.
 * <p>
 * Root-relative, and carrying no environment prefix: the governance site has none
 * ({@code per-environment: false}). Every segment is a slug - see {@code Slugs.toSlug} - which this class does
 * not derive; it only joins the ones it is given.
 */
public final class GovernancePaths {

    public static final String SYSTEMS_SEGMENT = "systems";
    public static final String COMPONENTS_SEGMENT = "components";
    public static final String RULES_SEGMENT = "rules";

    private GovernancePaths() {
    }

    /** The system scores overview, at the site root. */
    public static String overview() {
        return "/";
    }

    public static String systems() {
        return "/" + SYSTEMS_SEGMENT + "/";
    }

    public static String system(String systemSlug) {
        return systems() + systemSlug + "/";
    }

    public static String component(String systemSlug, String componentSlug) {
        return system(systemSlug) + COMPONENTS_SEGMENT + "/" + componentSlug + "/";
    }

    public static String rules() {
        return "/" + RULES_SEGMENT + "/";
    }

    public static String rule(String ruleSlug) {
        return rules() + ruleSlug + "/";
    }
}
