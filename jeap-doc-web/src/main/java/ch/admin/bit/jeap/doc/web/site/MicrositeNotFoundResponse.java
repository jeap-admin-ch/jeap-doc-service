package ch.admin.bit.jeap.doc.web.site;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * What a reader sees when a microsite has no such file.
 * <p>
 * <b>A page of the service's own, and not the site's 404.</b> This is read inside the frame, where the site's
 * not-found page would draw a second navbar and a second sidebar into a box a few hundred pixels wide. It says
 * what is missing and offers the way back to the microsite's own entry point.
 */
final class MicrositeNotFoundResponse {

    private MicrositeNotFoundResponse() {
    }

    static void writeTo(MicrositePath microsite, HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.NOT_FOUND.value());
        response.setContentType(MediaType.TEXT_HTML_VALUE + ";charset=UTF-8");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
        byte[] page = ("""
                <!doctype html>
                <html lang="en">
                <head><meta charset="utf-8"><title>Not in this documentation</title></head>
                <body style="font-family: system-ui, sans-serif; margin: 2rem; line-height: 1.5">
                <h1 style="font-size: 1.25rem">This documentation has no such file</h1>
                <p>The page <code>%s</code> is not part of what was uploaded here.</p>
                <p><a href="%s">Back to the start of this documentation</a></p>
                </body>
                </html>
                """).formatted(escaped(microsite.file()), entryPointFrom(microsite.file()))
                .getBytes(StandardCharsets.UTF_8);
        response.setContentLength(page.length);
        response.getOutputStream().write(page);
    }

    /**
     * The way back to the entry point, relative to the file that is missing: the frame is served under the
     * microsite's own prefix, and the page has no way of knowing what that prefix is called.
     */
    private static String entryPointFrom(String file) {
        long depth = file.chars().filter(character -> character == '/').count();
        return "../".repeat((int) depth) + MicrositePath.INDEX;
    }

    /** The path is part of a URL a reader chose, so it is written as text and never as markup. */
    private static String escaped(String file) {
        return file.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
