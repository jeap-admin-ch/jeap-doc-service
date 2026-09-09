package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SitePart;

/**
 * Builds one search index over the whole of a documentation site.
 * <p>
 * <b>It is not part of a build.</b> A site is published as one Docusaurus build per part, and an index over one
 * part would let a reader inside one system search only that system - which is why the site had no search
 * between the modularization and this. So the index is produced on its own, out of the same content the parts
 * are written from, and it spans all of them.
 * <p>
 * The domain decides what is indexed and when; how an index is produced, and what it is made of, is this
 * adapter's business and reaches nothing else.
 */
public interface SearchIndexBuilder {

    /**
     * Writes the content of the given part into a tree of its own and indexes it.
     *
     * @param site the site being indexed
     * @param part what to index - {@link SitePart#wholeSiteOf}, so that a reader in one system finds a page in
     *             another
     * @throws SiteBuildException when the index could not be built - the reason is what an operator reads
     */
    BuiltSearchIndex build(Site site, SitePart part);

    /**
     * Removes what {@link #build} left behind, once it has been published or given up on.
     */
    void discard(BuiltSearchIndex index);
}
