package ch.admin.bit.jeap.doc.domain;

/**
 * Where a site's search index is served from, and which paths belong to it.
 * <p>
 * <b>A prefix of its own, and deliberately not {@link SharedAssets}.</b> The shared prefix is written by every
 * part build under the rule that every part writes the same bytes under the same name; an index has one writer,
 * one lifecycle and an identifier, and folding it in would quietly weaken a rule the parts depend on.
 * <p>
 * <b>And a prefix per index rather than one per site.</b> Every file Pagefind writes carries a content hash in
 * its name, so a browser that has loaded the entry manifest of one index goes on to ask for a metadata file and
 * index chunks by names that exist only under that index's prefix. Writing a new index over the old one would
 * turn the next keystroke of a reader who is already searching into a 404 - so an index is written under a new
 * prefix and one row decides which is current, exactly as a part publication is.
 */
public final class SearchIndex {

    /**
     * The directory of the site the index is served under. It is also the directory the indexer writes, and the
     * two have to agree: the client resolves everything else relative to where it was loaded from.
     */
    public static final String DIRECTORY = "pagefind";

    private SearchIndex() {
    }

    /**
     * Whether a path within a site is part of its search index.
     *
     * @param pathWithinSite the path below the site's root, without a leading slash
     */
    public static boolean holds(String pathWithinSite) {
        return pathWithinSite.startsWith(DIRECTORY + "/");
    }

    /** Where one index of a site is published. */
    public static String prefixOf(String site, long indexId) {
        return site + "/search/" + indexId;
    }
}
