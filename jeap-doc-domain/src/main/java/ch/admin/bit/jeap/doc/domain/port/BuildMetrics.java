package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.BuildTrigger;

import java.time.Duration;

/**
 * What the documentation generator reports about itself.
 * <p>
 * A port rather than a metrics library in the domain: what is said here is <i>this build succeeded, and it
 * produced this much</i>. The staleness signals an operator alarms on - how long ago a site was last published,
 * how long a request has been waiting - are not reported through this: they are read from the database by the
 * adapter, so that they survive a restart and read the same on every instance.
 */
public interface BuildMetrics {

    /**
     * A generator that measures nothing.
     * <p>
     * For tests that need the port satisfied but are not about the meters. It is here because it was written
     * out at three places, and every method added to this port had to be added to all three.
     */
    BuildMetrics NONE = new BuildMetrics() {

        @Override
        public void succeeded(String site, BuildTrigger trigger, Duration duration, BuiltSite generated) {
            // measures nothing
        }

        @Override
        public void failed(String site, BuildTrigger trigger, Duration duration) {
            // measures nothing
        }

        @Override
        public void aborted(String site, BuildTrigger trigger, Duration duration) {
            // measures nothing
        }

        @Override
        public void abandoned(String site, int count) {
            // measures nothing
        }

        @Override
        public void modelRead(String site, String environment, Duration duration) {
            // measures nothing
        }

    };

    /**
     * A build that produced a site and published it. What it documented - {@link BuiltSite#documentedSystems}
     * among it - is reported from here and from nowhere earlier, so that a build failing after the model was
     * read leaves the gauges of the last successful build where they were.
     */
    void succeeded(String site, BuildTrigger trigger, Duration duration, BuiltSite generated);

    /**
     * A build that did not finish. <b>This is what the failure alarm counts</b>, so nothing that is not a defect
     * may be reported here.
     */
    void failed(String site, BuildTrigger trigger, Duration duration);

    /**
     * A build the instance gave up on because it was stopping. Deliberately not a failure: a deployment landing
     * on a build is not a defect, and the build is asked for again on the way down.
     */
    void aborted(String site, BuildTrigger trigger, Duration duration);

    /**
     * One read of the stored architecture model of an environment: how long it took.
     * <p>
     * Against the build timer, it answers how much of a build is spent loading the landscape out of the
     * database. There is nothing to report about whether it worked: a build makes no call to the architecture
     * repository, and a read that fails fails the build. How many systems it found is not reported here either
     * - the build can still fail after it - but with {@link #succeeded}.
     */
    void modelRead(String site, String environment, Duration duration);

    /**
     * Builds found still marked as running although the instance that started them is gone. One is a container
     * that was killed rather than stopped; a stream of them is a build that kills whatever runs it.
     */
    void abandoned(String site, int count);
}
