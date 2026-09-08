package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.domain.upload.InvalidUploadException;
import ch.admin.bit.jeap.doc.domain.upload.UploadProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Refuses a validation request whose announced length is larger than a list of paths could be, before that
 * body is read at all.
 * <p>
 * This is the cheap half of the bound: a gigabyte that says so is refused without a byte of it being read.
 * The half that actually holds is {@link PathTreeReader}, which applies the same limit while the body streams
 * and so also covers a request that announces no length. Both read the limit from
 * {@link PathTreeReader#maxBytes}, so there is one number.
 */
@RequiredArgsConstructor
public class ValidationBodySizeInterceptor implements HandlerInterceptor {

    private final UploadProperties properties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        long announced = request.getContentLengthLong();
        long limit = limit();
        if (announced > limit) {
            throw InvalidUploadException.bodyTooLarge(announced, limit);
        }
        return true;
    }

    long limit() {
        return PathTreeReader.maxBytes(properties);
    }
}
