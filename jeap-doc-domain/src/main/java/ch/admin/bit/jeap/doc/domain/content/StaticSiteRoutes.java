package ch.admin.bit.jeap.doc.domain.content;

import ch.admin.bit.jeap.doc.domain.template.DocumentationPaths;
import ch.admin.bit.jeap.doc.domain.template.NumberPrefixes;
import ch.admin.bit.jeap.doc.domain.template.ReservedNames;

import java.util.Locale;

/**
 * The route a page of a static site is published at, as Docusaurus derives it.
 * <p>
 * <b>Which page is a folder's own is decided on the names as they are written.</b> Docusaurus serves an
 * {@code index}, a {@code README} and a page named like its folder at the folder's route, comparing the file name
 * and the folder name before any number prefix comes off - so {@code 01-guides/01-guides.md} is the folder's
 * page and {@code 01-guides/guides.md} is not. The prefixes come off only afterwards, from every segment of the
 * route.
 * <p>
 * <b>Here rather than beside either of its readers</b>: the upload validation refuses two pages at one route,
 * and the site generator has to know whether a tree brings its own root page - and the two must not disagree.
 */
public final class StaticSiteRoutes {

    private StaticSiteRoutes() {
    }

    /**
     * The route of a page, without slashes at either end and in lower case, so that routes differing in case
     * count as one - the root page's is the empty one.
     *
     * @param path a Markdown page of the tree, relative to its root, separated by {@code /}
     */
    public static String routeOf(String path) {
        int slash = path.lastIndexOf('/');
        String name = path.substring(slash + 1);
        String document = name.substring(0, name.length() - DocumentationPaths.MARKDOWN_EXTENSION.length() - 1);
        String folder = slash < 0 ? "" : path.substring(0, slash);
        String folderRoute = NumberPrefixes.strippedFromEverySegment(folder).toLowerCase(Locale.ROOT);
        if (isTheFoldersOwnPage(document, folder.substring(folder.lastIndexOf('/') + 1))) {
            return folderRoute;
        }
        String documentRoute = NumberPrefixes.stripped(document).toLowerCase(Locale.ROOT);
        return folderRoute.isEmpty() ? documentRoute : folderRoute + "/" + documentRoute;
    }

    /** Whether a Markdown page of the tree is published at its root, replacing the generated root page. */
    public static boolean isTheRootPage(String path) {
        return path.indexOf('/') < 0
               && path.toLowerCase(Locale.ROOT).endsWith("." + DocumentationPaths.MARKDOWN_EXTENSION)
               && routeOf(path).isEmpty();
    }

    /** {@code isCategoryIndex} of Docusaurus: the raw names, compared in any case. */
    private static boolean isTheFoldersOwnPage(String document, String folderName) {
        String lower = document.toLowerCase(Locale.ROOT);
        return ReservedNames.LANDING_PAGE_NAMES.contains(lower)
               || (!folderName.isEmpty() && lower.equals(folderName.toLowerCase(Locale.ROOT)));
    }
}
