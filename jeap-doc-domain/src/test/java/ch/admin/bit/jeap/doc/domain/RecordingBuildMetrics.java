package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.BuildMetrics;
import ch.admin.bit.jeap.doc.domain.port.BuiltSite;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What the domain reports about its builds, kept rather than measured.
 * <p>
 * The domain says <i>this build was aborted</i>; that it becomes a meter with a {@code result} tag is the
 * metrics adapter's business, and is tested there. What matters here is that the right thing is said - not
 * counting a build the instance gave up on as a failure is the difference between a quiet deployment and a page.
 */
public class RecordingBuildMetrics implements BuildMetrics {

    // Synchronized: a build pass runs several parts at once, and each of them reports what it did.
    public final List<String> results = java.util.Collections.synchronizedList(new ArrayList<>());
    public final List<String> abandoned = java.util.Collections.synchronizedList(new ArrayList<>());
    /** One entry per run of a trigger: the site, what asked, and how many parts it asked for. */
    public final List<String> triggered = java.util.Collections.synchronizedList(new ArrayList<>());

    @Override
    public void succeeded(String site, BuildTrigger trigger, Duration duration, BuiltSite generated) {
        results.add("succeeded:" + site + ":" + trigger);
    }

    @Override
    public void failed(String site, BuildTrigger trigger, Duration duration) {
        results.add("failed:" + site + ":" + trigger);
    }

    @Override
    public void aborted(String site, BuildTrigger trigger, Duration duration) {
        results.add("aborted:" + site + ":" + trigger);
    }

    @Override
    public void timedOut(String site, BuildTrigger trigger, Duration duration) {
        results.add("timed-out:" + site + ":" + trigger);
    }

    @Override
    public void skipped(String site, BuildTrigger trigger) {
        results.add("skipped:" + site + ":" + trigger);
    }

    @Override
    public void triggered(String site, BuildTrigger trigger, int parts) {
        triggered.add(site + ":" + trigger + ":" + parts);
    }

    /** Every value the number of running builds took, in order - so a test can see the slots fill and empty. */
    public final List<Integer> slotsBusy = java.util.Collections.synchronizedList(new ArrayList<>());

    /** Parts left to another instance, and parts whose build threw - counted as they are settled. */
    public final AtomicInteger contended = new AtomicInteger();
    public final AtomicInteger broken = new AtomicInteger();

    @Override
    public void contended() {
        contended.incrementAndGet();
    }

    @Override
    public void broken() {
        broken.incrementAndGet();
    }

    @Override
    public void slotsBusy(int busy) {
        slotsBusy.add(busy);
    }

    /** One entry per part whose site generator really ran - the skipped ones are not in here. */
    public final List<String> partsBuilt = java.util.Collections.synchronizedList(new ArrayList<>());

    @Override
    public void partBuilt(ch.admin.bit.jeap.doc.domain.PartKey part, Duration duration) {
        partsBuilt.add(part.toString());
    }

    @Override
    public void abandoned(String site, int count) {
        abandoned.add(site + ":" + count);
    }

    @Override
    public void modelRead(String site, String environment, Duration duration) {
        results.add("model-read:" + environment);
    }
}
