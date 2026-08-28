package ch.admin.bit.jeap.doc.web.site;

import ch.admin.bit.jeap.doc.domain.Site;

/**
 * One file of one documentation site, as a request addresses it.
 *
 * @param site the site being served
 * @param file the path of the file within that site's published output
 */
public record SitePath(Site site, String file) {

    /** What a directory is served with, and what the site generator emits for every route. */
    public static final String INDEX = "index.html";

    /** What a site's own not-found page is called. */
    public static final String NOT_FOUND = "404.html";

    /**
     * Whether this addresses a directory - which is what a path with no file extension in its last segment is,
     * and what has to be redirected to its trailing-slash form.
     */
    public boolean looksLikeADirectory() {
        int lastSlash = file.lastIndexOf('/');
        String lastSegment = lastSlash < 0 ? file : file.substring(lastSlash + 1);
        return !lastSegment.contains(".");
    }

    /**
     * Whether this addresses the site's own front page. A front page that is not there says something different
     * from any other missing file: the site as a whole is not being served.
     */
    public boolean isSiteRoot() {
        return INDEX.equals(file);
    }
}
