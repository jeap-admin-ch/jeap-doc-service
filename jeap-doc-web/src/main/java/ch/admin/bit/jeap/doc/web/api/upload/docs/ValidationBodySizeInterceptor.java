package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.domain.upload.InvalidUploadException;
import ch.admin.bit.jeap.doc.domain.upload.UploadProperties;
import ch.admin.bit.jeap.doc.domain.upload.validation.StructureValidation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Refuses a validation request whose body is larger than a list of paths could be, <b>before that body is
 * read</b>.
 * <p>
 * {@code max-paths} alone does not bound anything: it is checked in the handler, and by then Jackson has
 * deserialized the whole array into the heap. On a container that is also running a Docusaurus build - which
 * is what the memory of this service is sized for - a request nobody meant to send is an instance that runs
 * out of it. So the announced length is compared with what the cap allows and the rest is never read.
 * <p>
 * <b>The bound is derived, not configured.</b> At most {@code max-paths} paths of
 * {@link StructureValidation#MAX_PATH_LENGTH} characters, plus the quoting and the commas around them: one
 * property to set wrong instead of two that have to agree.
 * <p>
 * <b>A request that announces no length is let through.</b> Chunked encoding carries no
 * {@code Content-Length}, and refusing it outright would refuse a legitimate client to close a gap this
 * cannot close anyway - counting the bytes of a chunked body needs a wrapper around the stream. Every HTTP
 * client a doc pipeline uses sends a length for a JSON body, so this covers what actually arrives; the
 * residue is written down rather than papered over.
 */
@RequiredArgsConstructor
public class ValidationBodySizeInterceptor implements HandlerInterceptor {

    /** What one path costs in JSON beyond its characters: two quotes, a comma, and room for escaping. */
    static final int JSON_OVERHEAD_PER_PATH = 8;

    /** Room for the object around the array - the key, the brackets, whitespace a client may have added. */
    static final int ENVELOPE = 1024;

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
        return (long) properties.getValidation().getMaxPaths()
               * (StructureValidation.MAX_PATH_LENGTH + JSON_OVERHEAD_PER_PATH) + ENVELOPE;
    }
}
