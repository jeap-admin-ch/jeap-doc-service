package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.Site;

import java.time.Instant;
import java.util.Set;

/**
 * Turns the documentation the doc service wants to publish into a static site.
 * <p>
 * The domain decides <b>what the documentation contains</b>; this decides <b>how a site is produced from it</b>.
 * Nothing about the site generator reaches the domain, and nothing about which pages exist reaches the adapter.
 */
public interface SiteBuilder {

    /**
     * Generates the site of one build: prepares a workspace named after the build, writes the content of the
     * site into it, installs the site template over that content and runs the generator.
     *
     * @param buildId     the identifier of the build, which the workspace is named after
     * @param site        the site to generate
     * @param generatedAt when this build started, as the generated pages report it
     * @return what was produced
     * @throws SiteBuildException when the site could not be generated - the reason is what an operator reads on
     *                            the failed build
     */
    BuiltSite generate(long buildId, Site site, Instant generatedAt);

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
}
