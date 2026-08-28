package ch.admin.bit.jeap.doc.metrics;

import ch.admin.bit.jeap.doc.domain.DocumentationType;
import ch.admin.bit.jeap.doc.domain.InvalidUploadException;
import ch.admin.bit.jeap.doc.domain.port.UploadMetrics;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the doc service reports about the documentation it receives.
 * <p>
 * One timer covers all three outcomes an upload can have, because a timer with a histogram already publishes its
 * count: {@code jeap_doc_upload_seconds_count{result="failed"}} is the counter, and the same meter says how long
 * receiving one takes. A repetition is neither a success nor a failure and is counted as itself - hiding it
 * inside the successes would misreport how much a pipeline actually sends.
 * <p>
 * What this cannot see is an upload rejected <b>before</b> it got here: a typo in a workflow configuration never
 * reaches the domain. Those are counted where they are answered, and counted <b>only</b> there - the rule is one
 * count per outcome, the same rule the logging already follows.
 * <p>
 * The meters themselves are resolved once per combination of tags and kept, rather than rebuilt on every upload:
 * a registry lookup is cheap but a builder is not, and an upload is measured on the request thread.
 */
@Component
@RequiredArgsConstructor
public class MicrometerUploadMetrics implements UploadMetrics {

    static final String UPLOAD = "jeap.doc.upload";
    static final String BYTES = "jeap.doc.upload.bytes";
    static final String NO_REASON = "none";

    private final MeterRegistry registry;

    /**
     * The meters in use, by the tags that tell them apart. {@link java.util.concurrent.ConcurrentHashMap} rather
     * than a field per meter, because the combinations are known only as uploads arrive - there is one per
     * result, reason and documentation type.
     */
    private final Map<UploadTags, Timer> uploads = new ConcurrentHashMap<>();
    private final Map<String, DistributionSummary> sizes = new ConcurrentHashMap<>();

    /**
     * An upload that stored what it brought.
     */
    @Override
    public void stored(DocumentationType type, long sizeInBytes, Duration duration) {
        recordUpload("stored", NO_REASON, type, duration);
        sizes.computeIfAbsent(tagOf(type), typeTag -> DistributionSummary.builder(BYTES)
                        .description("Size of the documentation bundles that were stored")
                        .baseUnit("bytes")
                        .tag("type", typeTag)
                        .register(registry))
                .record(sizeInBytes);
    }

    /**
     * An upload that repeated one already stored: idempotent, and nothing was written.
     */
    @Override
    public void repeated(DocumentationType type, Duration duration) {
        recordUpload("repeated", NO_REASON, type, duration);
    }

    /**
     * An upload the doc service refused after it had reached the domain.
     */
    @Override
    public void failed(DocumentationType type, InvalidUploadException.Code reason, Duration duration) {
        recordUpload("failed", tagOf(reason), type, duration);
    }

    private void recordUpload(String result, String reason, DocumentationType type, Duration duration) {
        uploads.computeIfAbsent(new UploadTags(result, reason, tagOf(type)), tags -> Timer.builder(UPLOAD)
                        .description("Uploads of documentation: how many, how long, and how they ended")
                        .tag("result", tags.result())
                        .tag("reason", tags.reason())
                        .tag("type", tags.type())
                        .publishPercentileHistogram()
                        .register(registry))
                .record(duration);
    }

    /** What tells two upload timers apart, and therefore what they are cached by. */
    private record UploadTags(String result, String reason, String type) {
    }

    private static String tagOf(DocumentationType type) {
        return type == null ? "unknown" : type.name().toLowerCase(Locale.ROOT);
    }

    private static String tagOf(InvalidUploadException.Code code) {
        return code == null ? NO_REASON : code.name().toLowerCase(Locale.ROOT);
    }
}
