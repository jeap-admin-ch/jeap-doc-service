package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SitePart;

import java.time.Instant;
import java.util.Set;

/**
 * Turns the documentation the doc service wants to publish into a static site.
 * <p>
 * The domain decides <b>what the documentation contains</b>; this decides <b>how a site is produced from it</b>.
 * Nothing about the site generator reaches the domain, and nothing about which pages exist reaches the adapter.
 * <p>
 * A site is produced one <b>part</b> at a time, and in two steps: the content of a part is written and hashed,
 * and only then - if that hash is not what is already published - is the site generator started. Writing the
 * content is seconds and the generator is minutes, which is what makes a part per system affordable.
 */
public interface SiteBuilder {

    /**
     * Writes the content of one part into a workspace named after the build, and reports what it hashed to.
     * <p>
     * Nothing is generated yet: this is the cheap half, and its digest is what decides whether the expensive
     * half runs at all.
     *
     * @param buildId     the identifier of the build, which the workspace is named after
     * @param site        the site the part belongs to
     * @param part        the part to write
     * @param generatedAt when this build started, as the generated pages report it
     * @throws SiteBuildException when the content could not be written
     */
    PreparedPart prepare(long buildId, Site site, SitePart part, Instant generatedAt);

    /**
     * Installs the site template over a prepared part's content and runs the generator over it.
     *
     * @return what was produced
     * @throws SiteBuildException when the site could not be generated - the reason is what an operator reads on
     *                            the failed build
     */
    BuiltSite generate(PreparedPart prepared);

    /**
     * Gives up on the build running right now, so that an instance being stopped ends it in a second rather
     * than at its timeout. Does nothing on an instance that is not building.
     * <p>
     * What it does <b>not</b> do is interrupt the thread running the build. That thread has a terminal state to
     * write, a lock to give back and a request to put back, and it needs a working database connection for all
     * three; the generator is stopped underneath it instead, so that {@link #generate} fails the ordinary way
     * and the caller stays in control.
     */
    void abortCurrentBuild();

    /**
     * Removes the workspace of a build that has finished.
     */
    void discard(long buildId);

    /**
     * Removes every workspace that does not belong to a build that is still running, and reports how many there
     * were.
     * <p>
     * A workspace is named after its build, and the database says which builds are running - so this needs to
     * know nothing about which instance left what behind. That is what makes it safe while other instances are
     * building, and what gets the leftovers of an instance that never comes back removed by whichever instance
     * builds next.
     *
     * @param runningBuildIds the builds that are still running, whichever instance is running them
     */
    int sweepWorkspaces(Set<Long> runningBuildIds);

    /**
     * Writes what the run cost into the generated site, before it is published.
     * <p>
     * The one thing that happens between the generator and the upload. The page describing the documentation
     * was written at the start of the run and cannot carry these numbers, so they are published beside it and
     * fetched - see {@link DocumentationStatus}.
     * <p>
     * <b>It must not fail a build that has otherwise succeeded.</b> A site published without its numbers is a
     * site with one table missing; a build failed over them is no site at all.
     */
    void describeRun(BuiltSite generated, DocumentationStatus status);
}
