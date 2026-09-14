package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.domain.upload.InvalidUploadException;
import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
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
        long limit = limit(formatOf(request));
        if (announced > limit) {
            throw InvalidUploadException.bodyTooLarge(announced, limit);
        }
        return true;
    }

    long limit(SourceFormat sourceFormat) {
        return PathTreeReader.maxBytes(properties, sourceFormat);
    }

    /**
     * The format the request names, and HTML - the larger bound - for anything else.
     * <p>
     * This runs before the handler has bound a parameter, so it is deliberately lenient: what refuses a
     * body exactly is the read, which is given the format the controller parsed. Guessing the smaller
     * bound here would answer "too large" to an upload whose real problem is its parameters.
     */
    private static SourceFormat formatOf(HttpServletRequest request) {
        return SourceFormatDto.MARKDOWN.parameterValue().equals(request.getParameter("source-format"))
                ? SourceFormat.MARKDOWN
                : SourceFormat.HTML;
    }
}
