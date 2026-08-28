package ch.admin.bit.jeap.doc.domain;

import java.util.List;

/**
 * Where one documentation site stands, as an operator asks it.
 * <p>
 * It is assembled to answer one question - <i>why is this site not updating?</i> - so it holds what the site is
 * configured to do next to what has actually happened: a site with no schedule that nothing uploads to is
 * working exactly as configured, and a site whose last build failed is not, and the two look the same from
 * outside.
 *
 * @param site      the site as it is configured
 * @param pending   the build owed to it, or null when nothing is owed
 * @param running   the builds of it running right now - normally none or one, and two while an instance that
 *                  lost its lock lease carries on building until another one abandons it
 * @param published the build whose site is being served, or null until one has succeeded
 * @param lastBuild the newest build whatever became of it, or null when the site has never been built. It is
 *                  what makes a failure visible: {@code published} shows the last <i>success</i>, so a site
 *                  whose builds have been failing for a week looks healthy without this
 */
public record SiteStatus(
        Site site,
        BuildRequest pending,
        List<DocumentationBuild> running,
        DocumentationBuild published,
        DocumentationBuild lastBuild) {

    public SiteStatus {
        running = List.copyOf(running);
    }
}
