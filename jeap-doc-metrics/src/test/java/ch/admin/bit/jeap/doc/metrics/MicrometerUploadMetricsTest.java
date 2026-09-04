package ch.admin.bit.jeap.doc.metrics;

import ch.admin.bit.jeap.doc.domain.upload.DocumentationType;
import ch.admin.bit.jeap.doc.domain.upload.InvalidUploadException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerUploadMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerUploadMetrics metrics = new MicrometerUploadMetrics(registry);

    @Test
    void stored_thenTheTimerCountsItAndTheSizeIsRecorded() {
        metrics.stored(DocumentationType.COMPONENT_DOCS, 4096, Duration.ofMillis(120));

        assertThat(registry.get(MicrometerUploadMetrics.UPLOAD)
                .tag("result", "stored")
                .tag("reason", MicrometerUploadMetrics.NO_REASON)
                .tag("type", "component_docs")
                .timer().count()).isOne();
        assertThat(registry.get(MicrometerUploadMetrics.BYTES).summary().totalAmount()).isEqualTo(4096);
    }

    /**
     * A repetition is neither a success nor a failure: counting it as a success would misreport how much a
     * pipeline actually sends.
     */
    @Test
    void repeated_thenCountedAsItselfRatherThanAsASuccess() {
        metrics.repeated(DocumentationType.SYSTEM_DOCS, Duration.ofMillis(5));

        assertThat(registry.get(MicrometerUploadMetrics.UPLOAD).tag("result", "repeated").timer().count()).isOne();
        assertThat(registry.find(MicrometerUploadMetrics.UPLOAD).tag("result", "stored").timer()).isNull();
        // Nothing was written, so nothing is added to the size of what was stored.
        assertThat(registry.find(MicrometerUploadMetrics.BYTES).summary()).isNull();
    }

    @Test
    void failed_thenTheReasonIsTheProblemCode() {
        metrics.failed(DocumentationType.LIBRARY_DOCS, InvalidUploadException.Code.STORAGE_FAILED,
                Duration.ofMillis(9));

        assertThat(registry.get(MicrometerUploadMetrics.UPLOAD)
                .tag("result", "failed")
                .tag("reason", "storage_failed")
                .timer().count()).isOne();
    }

    @Test
    void failed_whenTheTypeIsNotKnownYet_thenTaggedAsSuch() {
        metrics.failed(null, InvalidUploadException.Code.MISSING_PARAMETER, Duration.ofMillis(1));

        assertThat(registry.get(MicrometerUploadMetrics.UPLOAD).tag("type", "unknown").timer().count()).isOne();
    }

    /**
     * The upload timer <b>keeps</b> its buckets, unlike the build and import timers: seconds is the scale an
     * upload takes, which is the range Micrometer's default histogram covers, so its quantiles mean something.
     */
    @Test
    void upload_thenTheTimerPublishesHistogramBuckets() {
        RecordingHistogramConfig recording = new RecordingHistogramConfig();

        new MicrometerUploadMetrics(recording).stored(DocumentationType.SYSTEM_DOCS, 4096, Duration.ofSeconds(2));

        assertThat(recording.publishesHistogram(MicrometerUploadMetrics.UPLOAD)).isTrue();
    }
}
