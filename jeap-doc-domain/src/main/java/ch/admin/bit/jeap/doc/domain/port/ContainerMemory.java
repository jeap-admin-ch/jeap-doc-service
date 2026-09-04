package ch.admin.bit.jeap.doc.domain.port;

import java.util.Optional;

/**
 * How much memory the container this service runs in has used, and how much it may use.
 * <p>
 * A documentation build is the largest thing the doc service does, and almost none of it is the JVM: the site
 * generator is a child process whose bundler allocates outside any heap this service can see. So the number a
 * container is sized from is the container's own, and it is read where the kernel keeps it.
 * <p>
 * <b>The peak is not sampled.</b> The kernel maintains a high-water mark per control group, which is both
 * cheaper and more exact than any thread watching from inside could be - it misses nothing between two
 * readings. What this port adds is a beginning and an end, so that the mark can be attributed to one build.
 */
@FunctionalInterface
public interface ContainerMemory {

    /** A container whose memory cannot be read - off Linux, and in a test that is not about memory. */
    ContainerMemory NONE = () -> Measurement.NONE;

    /**
     * Begins measuring one build: resets the kernel's high-water mark where the kernel allows it, and
     * remembers where it stood where it does not.
     */
    Measurement measure();

    /**
     * One build's measurement, asked for what it found once the build has ended. Nothing is held open by it.
     * <p>
     * <b>Two readings taken at different moments may differ.</b> The kernel's mark only rises, so a reading
     * after the upload of a site is higher than one taken before it - the upload streams the whole site, and
     * the page cache counts towards the control group. A caller that reports one build in several places
     * therefore reads this <b>once</b> and passes the value on, or it publishes two numbers for one build.
     */
    @FunctionalInterface
    interface Measurement {

        Measurement NONE = Optional::empty;

        /** The highest the container has been since this measurement began, or empty where that is not known. */
        Optional<Peak> peak();
    }

    /**
     * What one build did to the memory of its container.
     *
     * @param usedBytes  the highest usage; where {@code exact} is false, an upper bound rather than the peak
     * @param limitBytes what the container is killed at, or -1 where nothing names a limit
     * @param exact      whether this is <b>this build's</b> peak. False where the kernel's mark could not be
     *                   reset and this build stayed below an earlier one: the peak is then only known to be at
     *                   most {@code usedBytes}
     */
    record Peak(long usedBytes, long limitBytes, boolean exact) {
    }
}
