package ch.admin.bit.jeap.doc.domain;

import java.util.Set;

/**
 * The files of a generated site that every part emits identically, and that are therefore published once for
 * the whole site rather than once per part.
 * <p>
 * <b>This is what lets a part own several URL subtrees.</b> A Docusaurus build puts everything it emits under
 * its own base URL, so two parts of one site under one base URL share the namespace below {@code assets/} - and
 * a request for {@code /assets/js/main.a1b2c3.js} could not otherwise be resolved to a part.
 * <p>
 * Measured rather than assumed. Two parts of one site, built from the same template with disjoint content, emit
 * 195 files each and share 98 names; every one of the 91 shared names under {@code assets/} and {@code img/} is
 * <b>byte-identical</b>. The shared chunks are the framework and the theme, the per-page chunks carry a content
 * hash in their names, and the fixed-name files - the site's own logo, the diagram plugin's version-pinned
 * directory - are the same file by construction. The seven names that differ are the site-wide pages and files
 * ({@code index.html}, {@code 404.html}, {@code sitemap.xml} and their like), and those belong to the shell part
 * anyway.
 * <p>
 * The one thing that follows: the fixed-name files are identical only while every part is built from the same
 * template, so a new version of the service or of the template has to rebuild every part. The content digest
 * covers the version for exactly that reason.
 */
public final class SharedAssets {

    /**
     * The top-level directories of a generated site that are shared. Everything else belongs to the part that
     * owns the path.
     */
    public static final Set<String> DIRECTORIES = Set.of("assets", "img");

    private SharedAssets() {
    }

    /**
     * Whether a path within a site is one of the shared files.
     *
     * @param pathWithinSite the path below the site's root, without a leading slash
     */
    public static boolean holds(String pathWithinSite) {
        int slash = pathWithinSite.indexOf('/');
        return slash > 0 && DIRECTORIES.contains(pathWithinSite.substring(0, slash));
    }

    /**
     * Where the shared files of a site are published. One prefix per site, written by every part build: what
     * lands there is the same bytes under the same name, so a build overwriting another's file writes what was
     * already there.
     */
    public static String prefixOf(String site) {
        return site + "/shared";
    }
}
