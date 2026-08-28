package ch.admin.bit.jeap.doc.web.site;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The answer for a documentation site that is not there yet.
 * <p>
 * A site that has never been generated is not a wrong URL, and neither is one whose objects the bucket's
 * lifecycle rule has expired: the documentation is on its way, and saying so is more use to the reader than a
 * 404 that invites them to look for a typo. The status says the same thing to everything that is not a reader.
 */
@Slf4j
final class NotGeneratedYetResponse {

    /** The page itself, beside this class - markup belongs in a file, not in a string literal. */
    private static final String RESOURCE = "not-generated-yet.html";

    /** How long a browser is asked to wait before trying a site that has not been generated yet. */
    private static final String RETRY_AFTER_SECONDS = "30";

    /** Read once when the class loads, so a page missing from the artifact fails here and not on a request. */
    private static final String PAGE = read();

    private NotGeneratedYetResponse() {
    }

    static void writeTo(String siteId, HttpServletResponse response) throws IOException {
        log.debug("The documentation site {} has not been generated yet.", siteId);
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        response.setContentType(MediaType.TEXT_HTML_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8);
        response.getWriter().write(PAGE);
    }

    private static String read() {
        try (InputStream page = NotGeneratedYetResponse.class.getResourceAsStream(RESOURCE)) {
            if (page == null) {
                throw new IllegalStateException(
                        "The page %s is missing from the jeap-doc-web artifact.".formatted(RESOURCE));
            }
            return new String(page.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("The page %s could not be read.".formatted(RESOURCE), e);
        }
    }
}
