package ch.admin.bit.jeap.doc.web.api.upload;

import ch.admin.bit.jeap.doc.domain.InvalidUploadException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Where the accepted size ends. The limit is a boundary the service is judged by, and it is enforced by a
 * library, so the two cases around it are pinned here instead of being assumed.
 */
class UploadBodiesTest {

    private static final int LIMIT = 64;

    @Test
    void limitedTo_whenTheBodyIsExactlyTheLimit_thenReadToItsEnd() throws IOException {
        byte[] body = new byte[LIMIT];

        assertThat(read(UploadBodies.limitedTo(new ByteArrayInputStream(body), LIMIT))).isEqualTo(LIMIT);
    }

    @Test
    void limitedTo_whenTheBodyIsOneByteLonger_thenRejected() {
        byte[] body = new byte[LIMIT + 1];

        assertThatThrownBy(() -> read(UploadBodies.limitedTo(new ByteArrayInputStream(body), LIMIT)))
                .isInstanceOfSatisfying(InvalidUploadException.class,
                        e -> assertThat(e.getCode()).isEqualTo(InvalidUploadException.Code.SIZE_LIMIT_EXCEEDED))
                .hasMessageContaining(String.valueOf(LIMIT));
    }

    /**
     * The bundle is read in buffers, but a reader that asks byte by byte may not get past the limit either.
     * <p>
     * The rejection comes with the read that finds the limit exceeded, not with the byte that exceeds it - which
     * is enough, because a bundle is always read to its end: the spooling of an upload copies until the stream
     * says it is over, and that last read is the one that fails.
     */
    @Test
    void limitedTo_whenReadByteByByte_thenRejectedBeyondTheLimit() {
        InputStream body = UploadBodies.limitedTo(new ByteArrayInputStream(new byte[LIMIT + 1]), LIMIT);

        assertThatThrownBy(() -> {
            while (body.read() != -1) {
                // read to the end, as everything that consumes a bundle does
            }
        }).isInstanceOfSatisfying(InvalidUploadException.class,
                e -> assertThat(e.getCode()).isEqualTo(InvalidUploadException.Code.SIZE_LIMIT_EXCEEDED));
    }

    @Test
    void limitedTo_whenTheBodyIsEmpty_thenReadToItsEnd() throws IOException {
        assertThat(read(UploadBodies.limitedTo(new ByteArrayInputStream(new byte[0]), LIMIT))).isZero();
    }

    private static long read(InputStream body) throws IOException {
        try (body) {
            long count = 0;
            byte[] buffer = new byte[16];
            int read;
            while ((read = body.read(buffer)) != -1) {
                count += read;
            }
            return count;
        }
    }
}
