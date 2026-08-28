package ch.admin.bit.jeap.doc.domain;

/**
 * One environment of a documentation site: a tree of its own inside the generated site, showing that
 * environment's state of the documented systems.
 * <p>
 * Exactly one environment of a site is the {@link #main} one - it is served at the site root, it is the only
 * one search engines are invited to index, and it is what a link without a prefix opens. Exactly one is the
 * {@link #latest} one, which is where the documentation of a component's current state goes before it is
 * deployed anywhere. They may be the same environment, and usually are not.
 *
 * @param id        the identifier of the environment, and the path segment it is served under
 * @param shortName the abbreviation the switcher and the banner show, e.g. {@code DEV}
 * @param label     the name a reader sees
 * @param order     where the environment appears in the switcher
 * @param main      whether this is the environment served at the site root
 * @param latest    whether this is where the documentation of not-yet-deployed components goes
 */
public record SiteEnvironment(
        String id,
        String shortName,
        String label,
        int order,
        boolean main,
        boolean latest) {

    /**
     * The path segment this environment is served under - {@code /dev}, and nothing at all for the main
     * environment, which owns the site root.
     */
    public String routePrefix() {
        return main ? "" : "/" + id;
    }
}
