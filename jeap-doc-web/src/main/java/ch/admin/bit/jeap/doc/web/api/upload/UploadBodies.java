package ch.admin.bit.jeap.doc.web.api.upload;

import ch.admin.bit.jeap.doc.domain.upload.InvalidUploadException;
import org.apache.commons.io.input.BoundedInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * The body of an upload, as the service is willing to read it.
 */
public final class UploadBodies {

    private UploadBodies() {
    }

    /**
     * The body with the accepted size enforced while it is read, so a bundle that announces less than it sends
     * cannot fill the heap of the service either.
     * <p>
     * The stream is bounded one byte beyond the limit, because that is the first byte that makes a bundle larger
     * than the limit - a bundle of exactly the accepted size is still accepted.
     *
     * @param body  the body of the request
     * @param limit the number of bytes an upload may send
     * @return the body, failing with {@link InvalidUploadException.Code#SIZE_LIMIT_EXCEEDED} beyond the limit
     */
    public static InputStream limitedTo(InputStream body, long limit) {
        try {
            return BoundedInputStream.builder()
                    .setInputStream(body)
                    .setMaxCount(limit + 1)
                    .setOnMaxCount((max, count) -> {
                        throw InvalidUploadException.tooLarge(limit);
                    })
                    .get();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
