package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.domain.upload.InvalidUploadException;
import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import ch.admin.bit.jeap.doc.domain.upload.UploadProperties;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two bounds on a validation request's body, both applied while it is read.
 * <p>
 * Two of the bodies here never end, which is what a chunked request can be. A bound that is only applied
 * after the read would leave those cases running until the heap is gone.
 */
class PathTreeReaderTest {

    private final UploadProperties properties = new UploadProperties();
    private final PathTreeReader reader = new PathTreeReader(properties);

    private static InputStream json(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aPathTree_isRead() {
        assertThat(reader.read(json("{\"paths\": [\"1-intro/goals.md\", \"5-building-block-view/design.md\"]}"),
                SourceFormat.MARKDOWN))
                .containsExactly("1-intro/goals.md", "5-building-block-view/design.md");
    }

    @Test
    void aBodyThatNamesNoPaths_isAnEmptyTree() {
        assertThat(reader.read(json("{}"), SourceFormat.MARKDOWN)).isEmpty();
        assertThat(reader.read(json("{\"paths\": []}"), SourceFormat.MARKDOWN)).isEmpty();
        assertThat(reader.read(json("{\"other\": {\"nested\": [1, 2]}}"), SourceFormat.MARKDOWN))
                .describedAs("a property this endpoint does not read is skipped")
                .isEmpty();
    }

    @Test
    void exactlyTheCappedNumberOfPaths_isRead() {
        properties.getValidation().setMaxPaths(2);

        assertThat(reader.read(json("{\"paths\": [\"a\", \"b\"]}"), SourceFormat.MARKDOWN))
                .containsExactly("a", "b");
    }

    /**
     * <b>A body that announces no length is bounded all the same.</b> Chunked encoding carries no
     * {@code Content-Length}, so nothing can refuse it up front: what holds is that the stream is cut while it
     * is read.
     */
    @Test
    void aBodyWithNoEndToIt_isCutAtTheByteLimit() {
        properties.getValidation().setMaxPaths(2);
        CountingStream body = new CountingStream(endless("{\"paths\": [\"", "a"));

        assertThatThrownBy(() -> reader.read(body, SourceFormat.MARKDOWN))
                .isInstanceOf(InvalidUploadException.class)
                .hasFieldOrPropertyWithValue("code", InvalidUploadException.Code.SIZE_LIMIT_EXCEEDED);
        assertThat(body.read).isLessThanOrEqualTo(PathTreeReader.maxBytes(properties, SourceFormat.MARKDOWN) + 1);
    }

    /**
     * <b>The cap on the number of paths is applied while the array is read.</b> A body of millions of
     * one-character paths is well within the byte limit and is millions of strings in the heap, so counting
     * them after the read bounds nothing - which is why this endless array is answered on its element count
     * and not on its length.
     */
    @Test
    void moreElementsThanTheCap_isRefusedWhileTheArrayIsRead() {
        properties.getValidation().setMaxPaths(2);
        CountingStream body = new CountingStream(endless("{\"paths\": [", "\"a\","));

        assertThatThrownBy(() -> reader.read(body, SourceFormat.MARKDOWN))
                .isInstanceOf(InvalidUploadException.class)
                .hasFieldOrPropertyWithValue("code", InvalidUploadException.Code.TOO_MANY_PATHS)
                .hasMessageContaining("more than 2 paths");
        assertThat(body.read).isLessThanOrEqualTo(PathTreeReader.maxBytes(properties, SourceFormat.MARKDOWN) + 1);
    }

    /**
     * A body a workflow hand-built wrongly is the most likely mistake of all, and the answer has to carry the
     * {@code code} the endpoint's contract tells a pipeline to read.
     */
    @Test
    void aBodyThatIsNotAReadablePathTree_isRefusedWithACode() {
        assertThat(unreadable("")).describedAs("no body at all")
                .isEqualTo(InvalidUploadException.Code.INVALID_PARAMETER_VALUE);
        assertThat(unreadable("{\"paths\": [")).describedAs("cut off")
                .isEqualTo(InvalidUploadException.Code.INVALID_PARAMETER_VALUE);
        assertThat(unreadable("{\"paths\": {}}")).describedAs("paths that are not a list")
                .isEqualTo(InvalidUploadException.Code.INVALID_PARAMETER_VALUE);
        assertThat(unreadable("{\"paths\": [\"1-intro/goals.md\", null]}"))
                .describedAs("a path that is not a string")
                .isEqualTo(InvalidUploadException.Code.INVALID_PARAMETER_VALUE);
        assertThat(unreadable("[]")).describedAs("a list where the tree was expected")
                .isEqualTo(InvalidUploadException.Code.INVALID_PARAMETER_VALUE);
    }

    /** An HTML set is many more files, so its body may be larger - and is still bounded. */
    @Test
    void theBoundFollowsTheSourceFormat() {
        assertThat(PathTreeReader.maxBytes(properties, SourceFormat.HTML))
                .isGreaterThan(PathTreeReader.maxBytes(properties, SourceFormat.MARKDOWN));

        properties.getValidation().setMaxMicrositePaths(3);

        assertThat(reader.read(json("{\"paths\": [\"a\", \"b\", \"c\"]}"), SourceFormat.HTML))
                .hasSize(3);
        assertThatThrownBy(() ->
                reader.read(json("{\"paths\": [\"a\", \"b\", \"c\", \"d\"]}"), SourceFormat.HTML))
                .isInstanceOf(InvalidUploadException.class)
                .hasFieldOrPropertyWithValue("code", InvalidUploadException.Code.TOO_MANY_PATHS);
    }

    private InvalidUploadException.Code unreadable(String body) {
        try {
            reader.read(json(body), SourceFormat.MARKDOWN);
        } catch (InvalidUploadException e) {
            assertThat(e.getMessage()).describedAs("and nothing of the parser's own text is echoed")
                    .isEqualTo("The request body is not a readable path tree.");
            return e.getCode();
        }
        throw new AssertionError("'" + body + "' was read as a path tree");
    }

    /** A prefix and then that chunk for ever, as a request that keeps sending arrives. */
    private static InputStream endless(String prefix, String chunk) {
        byte[] start = prefix.getBytes(StandardCharsets.UTF_8);
        byte[] repeated = chunk.getBytes(StandardCharsets.UTF_8);
        return new InputStream() {
            private long position;

            @Override
            public int read() {
                if (position < start.length) {
                    return start[(int) position++] & 0xff;
                }
                long offset = (position++ - start.length) % repeated.length;
                return repeated[(int) offset] & 0xff;
            }
        };
    }

    /** Counts what was taken from the stream, so a test can say the read stopped and not only that it failed. */
    private static final class CountingStream extends InputStream {

        private final InputStream delegate;
        private long read;

        private CountingStream(InputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                read++;
            }
            return value;
        }
    }
}
