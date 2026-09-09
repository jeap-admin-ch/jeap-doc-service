package ch.admin.bit.jeap.doc.domain;

import java.util.List;

/**
 * One part of a documentation site: what is generated, published and served as a unit.
 * <p>
 * A site is published as several Docusaurus builds rather than one, because one build of a whole landscape no
 * longer fits. A part is a <b>set of whole URL subtrees</b> of the site, and that is not a preference: a build
 * puts every asset it emits under its own base URL, so a request has to resolve to exactly one part.
 * <p>
 * Which parts a site has is decided by its {@link SitePartition}. Nothing else builds one.
 *
 * @param key           which part of which site this is
 * @param documents     what this part documents, for a log line and for the API - <i>the system orders</i>, or
 *                      <i>the site itself</i>
 * @param tree          the path within one environment's tree that this part carries, without slashes at
 *                      either end - {@code systems/orders}, and empty for a part that carries a whole
 *                      environment tree. It is what the part's Docusaurus build mounts its content at
 * @param carriesSystems whether the documentation of the systems of those environments is written into this
 *                      part, or into parts of their own. A part per system carries one system and therefore
 *                      says yes; the shell of a site cut on the system says no, and writes only the site's own
 *                      pages
 * @param environments  the environments this part carries, by id. A part per system carries every environment
 *                      of its site; a part per environment carries one
 * @param routePrefixes the URL subtrees this part owns, each with a leading and a trailing slash and relative
 *                      to the site's own root. <b>Empty for the shell part</b>, which owns whatever no other
 *                      part claims
 */
public record SitePart(PartKey key, String documents, String tree, boolean carriesSystems,
                       List<String> environments, List<String> routePrefixes) {

    /**
     * The identifier of the part that owns the site's own pages - its root page, the systems index, the page
     * about the documentation - and everything no other part claims.
     * <p>
     * It cannot collide with a part named after something in the model, because those identifiers are prefixed
     * with what they document ({@code system-<slug>}).
     */
    public static final String SHELL = "shell";

    public SitePart {
        tree = tree == null ? "" : tree;
        environments = environments == null ? List.of() : List.copyOf(environments);
        routePrefixes = routePrefixes == null ? List.of() : List.copyOf(routePrefixes);
    }

    public String site() {
        return key.site();
    }

    /** The part's identifier within its site. It names a lock, a row and a path of the administration API. */
    public String id() {
        return key.part();
    }

    /**
     * Whether this part carries whole environment trees rather than one subtree of them - which is what says
     * it writes the site's own pages: the root page of each environment, its systems index and the page about
     * the documentation.
     */
    public boolean carriesWholeEnvironments() {
        return tree.isEmpty();
    }

    /** Whether this part carries the given environment's tree. */
    public boolean carries(String environment) {
        return environments.contains(environment);
    }

    /** Whether this is the part that owns the site's own pages and the remainder of its paths. */
    public boolean isShell() {
        return key.isShell();
    }

    /**
     * Whether a path within the site is one of this part's. The shell owns nothing this way - it is what is
     * left when no other part matched.
     *
     * @param pathWithinSite the path below the site's root, with a leading slash. A fragment or a query is
     *                       not part of the route and is cut off first
     */
    public boolean owns(String pathWithinSite) {
        String path = route(pathWithinSite);
        return routePrefixes.stream().anyMatch(path::startsWith);
    }

    /**
     * The route a path names, ending in a slash so that a prefix cannot match half a segment.
     * <p>
     * <b>A fragment and a query come off first.</b> A link written into a page carries them -
     * {@code /systems/orders#context} - and putting the slash behind the fragment makes a string no prefix of
     * this part can match. Both directions of that are wrong: a part would rewrite a link to a page of its
     * own out of its build's link check, and the shell would leave a link into another part inside it, which
     * fails the build on a route the shell does not have.
     */
    private static String route(String pathWithinSite) {
        int hash = pathWithinSite.indexOf('#');
        int query = pathWithinSite.indexOf('?');
        int end;
        if (hash < 0) {
            end = query;
        } else if (query < 0) {
            end = hash;
        } else {
            end = Math.min(hash, query);
        }
        String path = end < 0 ? pathWithinSite : pathWithinSite.substring(0, end);
        return path.endsWith("/") ? path : path + "/";
    }

    /**
     * How long the longest prefix of this part is, so that the most specific part wins where two could serve a
     * path. Zero for the shell.
     */
    public int specificity() {
        return routePrefixes.stream().mapToInt(String::length).max().orElse(0);
    }
}
