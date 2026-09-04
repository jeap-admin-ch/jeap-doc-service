package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.upload.DocumentationType;
import ch.admin.bit.jeap.doc.domain.upload.InvalidUploadException;

import java.time.Duration;

/**
 * What the doc service reports about the documentation it receives.
 * <p>
 * A port rather than a metrics library in the domain: what is said here is <i>an upload was stored</i>, and how
 * that becomes a meter - its name, its tags, which registry it lands in - is the adapter's business.
 * <p>
 * <b>One count per outcome.</b> Every upload that reaches the domain reports exactly once, and an upload
 * rejected before it got here - a typo in a workflow configuration never reaches the domain - is counted where
 * it is answered, and only there.
 */
public interface UploadMetrics {

    /**
     * An upload that stored what it brought.
     */
    void stored(DocumentationType type, long sizeInBytes, Duration duration);

    /**
     * An upload that repeated one already stored: idempotent, and nothing was written. Neither a success nor a
     * failure - hiding it inside the successes would misreport how much a pipeline actually sends.
     */
    void repeated(DocumentationType type, Duration duration);

    /**
     * An upload the doc service refused after it had reached the domain.
     */
    void failed(DocumentationType type, InvalidUploadException.Code reason, Duration duration);
}
