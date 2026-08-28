package ch.admin.bit.jeap.doc.domain;

/**
 * Where a documentation build stands.
 */
public enum BuildState {

    /**
     * The build is running. One instance holds the lock of its site while it is in this state - so a directory
     * named after a running build is a directory that is in use, which is what the workspace clean-up goes by.
     */
    RUNNING,

    /**
     * The build produced a site and published it. **The newest succeeded build of a site is the published one**:
     * moving a build into this state is the moment its site becomes the one that is served.
     */
    SUCCEEDED,

    /**
     * The build did not finish. Nothing a reader sees changed: the site published before it is still the one
     * being served, and what went wrong is on the build.
     */
    FAILED,

    /**
     * The instance running the build disappeared and its lock has since expired, so the next build of that site
     * marked it as such. It is what turns a row that would otherwise be {@code RUNNING} for ever into a fact,
     * and what lets the workspace of that build be removed.
     */
    ABANDONED,

    /**
     * The instance running the build was stopping and gave it up deliberately, before it could finish. It is
     * <b>not a failure</b>: nothing about the generator is wrong, the site published before it is still served,
     * and the build was asked for again on the way down, so another instance runs it within a poll interval.
     * <p>
     * Kept apart from {@link #FAILED} because the alarm is on failures: a deployment landing on a build would
     * otherwise page somebody, and the one signal that means <i>the generator is broken</i> would stop meaning
     * it. Kept apart from {@link #ABANDONED} because that is a verdict passed later, by another run, on an
     * instance that vanished - this one is known at the time, by the instance itself.
     */
    ABORTED;

    /**
     * Whether a build in this state is over, whichever way it went.
     */
    public boolean isFinished() {
        return this != RUNNING;
    }
}
