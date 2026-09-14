package ch.admin.bit.jeap.doc.web.site;

import ch.admin.bit.jeap.doc.domain.port.StoredObject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Writes one file of an uploaded microsite to the reader.
 * <p>
 * Three things make this different from a file of a generated site. Every page is given the storage shim, so
 * that an application with an opaque origin starts at all; everything that is not a page is offered as an
 * attachment, so that a file a browser would render as a document is downloaded instead; and the entity tag is
 * <b>derived</b>, because the bytes served are no longer the bytes stored.
 * <p>
 * What is <b>not</b> different: everything after the injected tag is byte for byte what was uploaded.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class MicrositeResponse {

    /** A microsite is replaced under the same URL by the next upload, so it has to be asked about first. */
    private static final String CACHE_CONTROL = "no-cache";

    /**
     * How far into a document the head is looked for. The response must not be buffered whole - a page of a
     * generated report can be megabytes - and a document whose head is not in this window is served unchanged.
     */
    static final int HEAD_WINDOW = 8192;

    /** What the search looks for, lower case: it compares ignoring ASCII case. */
    private static final byte[] HEAD = "<head".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HTML = "<html".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MARKER = MicrositeShim.MARKER.getBytes(StandardCharsets.US_ASCII);

    private final MicrositeShim shim;

    void write(StoredObject object, HttpStatus status, HttpServletRequest request,
               HttpServletResponse response) throws IOException {
        boolean page = isAPage(object.contentType());
        String entityTag = page ? derivedTagOf(object) : EntityTags.weak(object.entityTag());
        if (entityTag != null) {
            response.setHeader(HttpHeaders.ETAG, entityTag);
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
        if (!page) {
            // Measured: a browser ignores this for a subresource, so a microsite's own stylesheets and scripts
            // are unaffected - what it stops is a file opened directly being rendered as a document.
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment");
        }
        if (status == HttpStatus.OK && entityTag != null && EntityTags.unchanged(request, entityTag)) {
            response.setStatus(HttpStatus.NOT_MODIFIED.value());
            closeQuietly(object);
            return;
        }
        response.setStatus(status.value());
        if (object.contentType() != null) {
            response.setContentType(object.contentType());
        }
        if (page) {
            writeWithTheShim(object, request, response);
        } else {
            writeAsItIs(object, request, response);
        }
    }

    private void writeAsItIs(StoredObject object, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        response.setContentLengthLong(object.sizeInBytes());
        if (HttpMethod.HEAD.matches(request.getMethod())) {
            closeQuietly(object);
            return;
        }
        try (InputStream content = object.content()) {
            StreamUtils.copy(content, response.getOutputStream());
        }
    }

    /**
     * The page with the shim after its opening head tag, and the rest of it untouched.
     * <p>
     * Only the first window is examined; everything beyond it is copied through. A page that already carries
     * the tag is left alone - a second shim would replace the first one's values.
     */
    private void writeWithTheShim(StoredObject object, HttpServletRequest request,
                                  HttpServletResponse response) throws IOException {
        try (InputStream content = object.content()) {
            byte[] window = content.readNBytes(HEAD_WINDOW);
            int at = indexOfIgnoringCase(window, MARKER, 0) >= 0 ? -1 : insertionPointIn(window);
            response.setContentLengthLong(object.sizeInBytes() + (at < 0 ? 0 : shim.length()));
            if (HttpMethod.HEAD.matches(request.getMethod())) {
                return;
            }
            OutputStream out = response.getOutputStream();
            if (at < 0) {
                out.write(window);
            } else {
                out.write(window, 0, at);
                out.write(shim.tag());
                out.write(window, at, window.length - at);
            }
            content.transferTo(out);
        }
    }

    /**
     * Where the tag goes, as a byte offset into the window: after the opening {@code <head>}, or after
     * {@code <html>} for a document that has no head of its own. A document with neither in the window is
     * served unchanged.
     * <p>
     * <b>Searched in the bytes, not in a decoded string.</b> A character index is not a byte offset - a byte
     * order mark is three bytes and one character, and lower-casing can change a string's length - so the
     * shim went early and split the tag. The tags looked for are ASCII, which is what makes a byte search
     * exact whatever the page's characters are.
     */
    static int insertionPointIn(byte[] window) {
        int after = endOfTag(window, HEAD);
        return after >= 0 ? after : endOfTag(window, HTML);
    }

    /**
     * The offset just past the first opening tag of this name, or -1.
     * <p>
     * <b>The name has to end where the tag name ends</b> - at {@code >}, {@code /} or whitespace - or
     * {@code <head} is also {@code <header>}, and the shim lands inside the body of a page that has no head.
     */
    private static int endOfTag(byte[] window, byte[] open) {
        for (int start = indexOfIgnoringCase(window, open, 0); start >= 0;
             start = indexOfIgnoringCase(window, open, start + 1)) {
            int next = start + open.length;
            if (next < window.length && endsTheName(window[next])) {
                for (int close = next; close < window.length; close++) {
                    if (window[close] == '>') {
                        return close + 1;
                    }
                }
                return -1;
            }
        }
        return -1;
    }

    private static boolean endsTheName(byte b) {
        return b == '>' || b == '/' || b == ' ' || b == '\t' || b == '\n' || b == '\r' || b == '\f';
    }

    /** Where the ASCII needle starts in the bytes, ignoring ASCII case, from the given offset on. */
    private static int indexOfIgnoringCase(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = from; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (lowerAscii(haystack[i + j]) != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static byte lowerAscii(byte b) {
        return b >= 'A' && b <= 'Z' ? (byte) (b + ('a' - 'A')) : b;
    }

    /**
     * The tag of a page that carries the shim: the stored one and the shim's version.
     * <p>
     * <b>Not the stored tag.</b> The bytes served are the stored bytes plus the tag, so answering a
     * conditional request with the stored tag would tell a reader holding the other version that nothing had
     * changed - and a new shim would never reach anybody.
     */
    private String derivedTagOf(StoredObject object) {
        if (object.entityTag() == null) {
            return null;
        }
        return "W/\"" + object.entityTag().replace("\"", "") + "-" + shim.version() + "\"";
    }

    /** Only a page is given the shim, and only a page is not offered as an attachment. */
    private static boolean isAPage(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("text/html");
    }

    /** The object holds an open connection to the storage; a 304 sends none of it, but it still has to close. */
    private static void closeQuietly(StoredObject object) {
        try {
            object.content().close();
        } catch (IOException e) {
            log.debug("The unread body of a not-modified microsite response could not be closed.", e);
        }
    }
}
