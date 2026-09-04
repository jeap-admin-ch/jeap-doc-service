package ch.admin.bit.jeap.doc.metrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.distribution.pause.PauseDetector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A registry that remembers whether a timer asked for a percentile histogram.
 * <p>
 * <b>Reading the buckets back would prove nothing.</b> A {@link SimpleMeterRegistry} does not support
 * aggregable percentiles, so {@code takeSnapshot().histogramCounts()} is empty whether or not
 * {@code publishPercentileHistogram()} was called - an assertion over it passes on either state. What decides
 * the series a Prometheus scrape carries is the configuration the builder handed over, so that is what this
 * captures.
 */
class RecordingHistogramConfig extends SimpleMeterRegistry {

    private final Map<String, DistributionStatisticConfig> configs = new LinkedHashMap<>();

    @Override
    protected Timer newTimer(Meter.Id id, DistributionStatisticConfig config, PauseDetector pauseDetector) {
        configs.put(id.getName(), config);
        return super.newTimer(id, config, pauseDetector);
    }

    /** Whether the timer of that name asked for the sixty-seven buckets a percentile histogram is. */
    boolean publishesHistogram(String meter) {
        DistributionStatisticConfig config = configs.get(meter);
        if (config == null) {
            throw new AssertionError("No timer named " + meter + " was registered; there are " + configs.keySet());
        }
        return Boolean.TRUE.equals(config.isPercentileHistogram());
    }
}
