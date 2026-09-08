package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.domain.upload.InvalidUploadException;
import ch.admin.bit.jeap.doc.domain.upload.UploadProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The bound on a validation request's body, applied to the length it announces.
 * <p>
 * This is the cheap half: a body that says it is a gigabyte is refused without a byte of it being read. That
 * the same limit also holds while a body streams is {@code PathTreeReaderTest}'s business.
 */
class ValidationBodySizeInterceptorTest {

    private final UploadProperties properties = new UploadProperties();
    private final ValidationBodySizeInterceptor interceptor = new ValidationBodySizeInterceptor(properties);

    private boolean preHandle(long contentLength) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/uploads/docs/validation");
        // The mock derives the length from its content, so the content is what carries it.
        request.setContent(new byte[(int) contentLength]);
        return interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
    }

    /** Derived from the cap, so there is one property to set wrong rather than two that have to agree. */
    @Test
    void theLimitIsWhatTheCappedNumberOfPathsCouldBe() {
        assertThat(interceptor.limit()).isEqualTo(10_000L * (1024 + 8) + 1024);

        properties.getValidation().setMaxPaths(10);

        assertThat(interceptor.limit()).describedAs("and it follows the cap").isEqualTo(10L * 1032 + 1024);
    }

    @Test
    void aBodyWithinTheLimit_isRead() {
        properties.getValidation().setMaxPaths(1);

        assertThatCode(() -> preHandle(interceptor.limit())).doesNotThrowAnyException();
        assertThat(preHandle(0)).isTrue();
    }

    @Test
    void aBodyOverTheLimit_isRefusedBeforeItIsRead() {
        properties.getValidation().setMaxPaths(1);

        assertThatThrownBy(() -> preHandle(interceptor.limit() + 1))
                .isInstanceOf(InvalidUploadException.class)
                .hasFieldOrPropertyWithValue("code", InvalidUploadException.Code.SIZE_LIMIT_EXCEEDED)
                .hasMessageContaining("announces");
    }

    /**
     * <b>A request that announces no length is let through here.</b> There is nothing to compare, so it is
     * the read that has to bound it - see {@code PathTreeReader}.
     */
    @Test
    void aRequestThatAnnouncesNoLength_isLeftToTheRead() {
        MockHttpServletRequest chunked = new MockHttpServletRequest("POST", "/api/uploads/docs/validation");

        assertThat(chunked.getContentLengthLong()).describedAs("as a chunked request arrives").isEqualTo(-1);
        assertThat(interceptor.preHandle(chunked, new MockHttpServletResponse(), new Object())).isTrue();
    }
}
