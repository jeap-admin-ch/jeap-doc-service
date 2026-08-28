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
     * A build that produced a site and published it.
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
     * Builds found still marked as running although the instance that started them is gone. One is a container
     * that was killed rather than stopped; a stream of them is a build that kills whatever runs it.
     */
    void abandoned(String site, int count);
}
