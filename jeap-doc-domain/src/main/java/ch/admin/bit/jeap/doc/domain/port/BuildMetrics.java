package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.BuildTrigger;
import ch.admin.bit.jeap.doc.domain.PartKey;

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
        public void timedOut(String site, BuildTrigger trigger, Duration duration) {
            // measures nothing
        }

        @Override
        public void abandoned(String site, int count) {
            // measures nothing
        }

        @Override
        public void skipped(String site, BuildTrigger trigger) {
            // measures nothing
        }

        @Override
        public void triggered(String site, BuildTrigger trigger, int parts) {
            // measures nothing
        }

        @Override
        public void modelRead(String site, String environment, Duration duration) {
            // measures nothing
        }

        @Override
        public void contended() {
            // measures nothing
        }

        @Override
        public void broken() {
            // measures nothing
        }

        @Override
        public void slotsBusy(int busy) {
            // measures nothing
        }

        @Override
        public void partBuilt(PartKey part, Duration duration) {
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
     * A build that broke. <b>This is what the failure alarm counts</b>, so nothing that is not a defect may be
     * reported here - and a build that ran out of time is reported by {@link #timedOut} instead, which is a
     * defect too and is alarmed on beside this one.
     */
    void failed(String site, BuildTrigger trigger, Duration duration);

    /**
     * A build the instance gave up on because it was stopping. Deliberately not a failure: a deployment landing
     * on a build is not a defect, and the build is asked for again on the way down.
     */
    void aborted(String site, BuildTrigger trigger, Duration duration);

    /**
     * A build given up on because it ran past {@code jeap.doc.build.timeout}. A defect like a failure, and
     * alarmed on beside one - but counted apart, because what it says is <i>this build no longer fits its
     * budget</i> rather than <i>this build is broken</i>, and the two are not put right the same way.
     * <p>
     * Its duration is the timeout, near enough, every single time. That is the second reason for the separate
     * result: mixed into the failures it would drag their mean towards the budget and hide how quick a build
     * that breaks actually is.
     */
    void timedOut(String site, BuildTrigger trigger, Duration duration);

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

    /**
     * A build that was asked for and turned out to have nothing to publish: the content of its part hashed to
     * what is already being served, so the site generator was never started.
     * <p>
     * <b>The number that says whether the split is doing what it is for.</b> A site where nothing has changed
     * should count skips and no builds; builds without skips mean either that everything really is changing or
     * that the digest is not stable.
     * <p>
     * No duration: what a skip cost is writing the content of one part, and the timer that answers <i>what did
     * a build cost</i> must not be diluted by runs that built nothing. The trigger is carried because it is
     * the question a skip raises - whether the skips come from the hourly import or from uploads.
     */
    void skipped(String site, BuildTrigger trigger);

    /**
     * How many parts of a site one run of a trigger asked to be built.
     * <p>
     * <b>The answer to "how much does one change set off".</b> An import reports every part of the site, an
     * upload the one part carrying its system, a forced publication the whole site. It is per <i>run</i>, so
     * the number stands for the last one rather than adding up.
     */
    void triggered(String site, BuildTrigger trigger, int parts);

    /**
     * One part left to another instance, which held its lock. A few are how a fleet shares one queue; a lot of
     * them means the instances are chasing each other rather than spreading out.
     * <p>
     * Untagged, because contention is a property of the instance rather than of a site.
     */
    void contended();

    /**
     * One part whose build threw where its own error handling should have covered it. Counted apart from the
     * parts that owed nothing after all: a run full of broken builds must not report itself as one that found
     * nothing to do.
     */
    void broken();

    /**
     * How many builds this instance is running right now. Reported rather than read, because the adapter that
     * measures it cannot depend on the runner that knows it - the runner already depends on this.
     */
    void slotsBusy(int busy);

    /**
     * What building one part cost, for the part it was - <b>only where the site generator really ran</b>.
     * <p>
     * Reported apart from {@link #succeeded} rather than as a tag on it, and that is the whole point of it: a
     * {@code part} tag on the build timer would multiply that timer by the parts of the site, and the label is
     * <b>unbounded from this service's side</b> - a part is named after a system in the imported architecture
     * model, so the upstream decides how many series there are. Here it costs one series per part.
     * <p>
     * A build the digest skipped is not reported: it takes a second or two and would make a part that costs
     * three quarters of an hour look cheap. Those are counted by {@link #skipped}.
     */
    void partBuilt(PartKey part, Duration duration);
}
