package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.UploadMetrics;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * What the domain reports about its uploads, kept rather than measured - the meters themselves are tested in
 * the metrics adapter.
 */
public class RecordingUploadMetrics implements UploadMetrics {

    public final List<String> results = new ArrayList<>();

    @Override
    public void stored(DocumentationType type, long sizeInBytes, Duration duration) {
        results.add("stored:" + type + ":" + sizeInBytes);
    }

    @Override
    public void repeated(DocumentationType type, Duration duration) {
        results.add("repeated:" + type);
    }

    @Override
    public void failed(DocumentationType type, InvalidUploadException.Code reason, Duration duration) {
        results.add("failed:" + type + ":" + reason);
    }
}
