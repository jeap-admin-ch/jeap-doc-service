package ch.admin.bit.jeap.doc.web.site;

import ch.admin.bit.jeap.doc.domain.port.StoredObject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;

/**
 * Writes one file of a published site to the reader, with the headers that decide how long they keep it.
 * <p>
 * Which file to send is {@link SiteRequestHandler}'s question; everything about the answer is this one's. The
 * two rules it exists for are that a hashed asset may be kept for good while a page may not, and that a reader
 * who already holds the current version is told so rather than sent the document again.
 */
@Slf4j
final class SiteContentResponse {

    /**
     * The site generator writes content-hashed names under this prefix, so the same URL never means two things
     * and a browser can keep them for good.
     */
    static final String IMMUTABLE_PREFIX = "assets/";

    private static final Duration IMMUTABLE_MAX_AGE = Duration.ofDays(365);
    private static final String IMMUTABLE_CACHE_CONTROL =
            "public, max-age=" + IMMUTABLE_MAX_AGE.toSeconds() + ", immutable";

    /** Everything else is replaced by the next build under the same URL, so it has to be asked about first. */
    private static final String REVALIDATE_CACHE_CONTROL = "no-cache";

    private SiteContentResponse() {
    }

    static void write(StoredObject object, String file, HttpStatus status, HttpServletRequest request,
                      HttpServletResponse response) throws IOException {
        String entityTag = EntityTags.weak(object.entityTag());
        if (entityTag != null) {
            response.setHeader(HttpHeaders.ETAG, entityTag);
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL,
                file.startsWith(IMMUTABLE_PREFIX) ? IMMUTABLE_CACHE_CONTROL : REVALIDATE_CACHE_CONTROL);

        // Everything but the hashed assets is asked to revalidate on every request, so answering the
        // revalidation with the whole document again would be most of the traffic this serves.
        if (status == HttpStatus.OK && entityTag != null && EntityTags.unchanged(request, entityTag)) {
            response.setStatus(HttpStatus.NOT_MODIFIED.value());
            closeQuietly(object);
            return;
        }

        response.setStatus(status.value());
        response.setContentLengthLong(object.sizeInBytes());
        if (object.contentType() != null) {
            response.setContentType(object.contentType());
        }
        if (HttpMethod.HEAD.matches(request.getMethod())) {
            // The container would discard the body anyway, but only after every byte of it had crossed the
            // network from the object storage. A HEAD is asked exactly to avoid that.
            closeQuietly(object);
            return;
        }
        try (InputStream content = object.content()) {
            StreamUtils.copy(content, response.getOutputStream());
        }
    }

    /** The object holds an open connection to the storage; a 304 sends none of it, but it still has to close. */
    private static void closeQuietly(StoredObject object) {
        try {
            object.content().close();
        } catch (IOException e) {
            log.debug("The unread body of a not-modified response could not be closed.", e);
        }
    }

}
