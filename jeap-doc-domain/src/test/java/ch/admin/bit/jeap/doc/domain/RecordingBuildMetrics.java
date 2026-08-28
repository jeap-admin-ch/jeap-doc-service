package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.BuildMetrics;
import ch.admin.bit.jeap.doc.domain.port.BuiltSite;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * What the domain reports about its builds, kept rather than measured.
 * <p>
 * The domain says <i>this build was aborted</i>; that it becomes a meter with a {@code result} tag is the
 * metrics adapter's business, and is tested there. What matters here is that the right thing is said - not
 * counting a build the instance gave up on as a failure is the difference between a quiet deployment and a page.
 */
public class RecordingBuildMetrics implements BuildMetrics {

    public final List<String> results = new ArrayList<>();
    public final List<String> abandoned = new ArrayList<>();

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
    public void abandoned(String site, int count) {
        abandoned.add(site + ":" + count);
    }
}
