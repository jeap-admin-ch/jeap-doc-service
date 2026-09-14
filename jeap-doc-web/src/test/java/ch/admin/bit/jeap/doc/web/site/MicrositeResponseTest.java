package ch.admin.bit.jeap.doc.web.site;

import ch.admin.bit.jeap.doc.domain.port.StoredObject;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the service adds to an uploaded page, and what it leaves exactly as it was.
 * <p>
 * The rules are the ADR's J1 to J5: the shim goes into an HTML page of a microsite and nowhere else, once,
 * after the opening head tag, and everything after it is byte for byte what a team uploaded. The entity tag is
 * derived, because the bytes served are no longer the bytes stored.
 */
class MicrositeResponseTest {

    private static final String STORED_TAG = "\"abc123\"";

    private final MicrositeShim shim = new MicrositeShim();
    private final MicrositeResponse response = new MicrositeResponse(shim);

    private static StoredObject html(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return new StoredObject(new ByteArrayInputStream(bytes), bytes.length, STORED_TAG,
                "text/html;charset=UTF-8");
    }

    private static StoredObject file(String body, String contentType) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return new StoredObject(new ByteArrayInputStream(bytes), bytes.length, STORED_TAG, contentType);
    }

    private MockHttpServletResponse write(StoredObject object) throws Exception {
        return write(object, new MockHttpServletRequest("GET", "/microsites/x"));
    }

    private MockHttpServletResponse write(StoredObject object, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse written = new MockHttpServletResponse();
        response.write(object, HttpStatus.OK, request, written);
        return written;
    }

    @Test
    void aPage_carriesTheShimAfterItsHeadAndNothingElseChanges() throws Exception {
        String page = "<!doctype html><html><head><title>Report</title></head><body>Hello</body></html>";

        MockHttpServletResponse written = write(html(page));

        String served = written.getContentAsString();
        assertThat(served).contains(MicrositeShim.MARKER);
        assertThat(served.indexOf(MicrositeShim.MARKER))
                .describedAs("after the opening head tag, before the page's own scripts")
                .isGreaterThan(served.indexOf("<head>"))
                .isLessThan(served.indexOf("<title>"));
        assertThat(served).describedAs("everything the team wrote is still there, in order")
                .contains("<title>Report</title></head><body>Hello</body></html>");
        assertThat(written.getContentLength())
                .describedAs("the length is recomputed, or the response is cut short")
                .isEqualTo(page.getBytes(StandardCharsets.UTF_8).length + shim.length());
    }

    /** A document with no head of its own still gets the shim, after its opening html tag. */
    @Test
    void aPageWithoutAHead_carriesTheShimAfterItsHtmlTag() throws Exception {
        MockHttpServletResponse written = write(html("<html><body>Only a body</body></html>"));

        assertThat(written.getContentAsString()).contains(MicrositeShim.MARKER);
        assertThat(written.getContentAsString().indexOf(MicrositeShim.MARKER))
                .isLessThan(written.getContentAsString().indexOf("<body>"));
    }

    /**
     * <b>The insertion point is a byte offset, and a character is not a byte.</b> A byte order mark is three
     * bytes and one character, and it is common in HTML a Windows tool wrote: counted in characters, the shim
     * went two bytes early and split the head tag in two.
     */
    @Test
    void aPageThatStartsWithAByteOrderMark_keepsItsHeadTagWhole() throws Exception {
        String page = "\uFEFF<!doctype html><html><head><title>Report</title></head><body></body></html>";

        String served = write(html(page)).getContentAsString(StandardCharsets.UTF_8);

        assertThat(served).contains("<head>" + new String(shim.tag(), StandardCharsets.UTF_8) + "<title>");
    }

    /** And the same for any character before the head that UTF-8 writes in more than one byte. */
    @Test
    void aPageWithNonAsciiBeforeItsHead_keepsItsHeadTagWhole() throws Exception {
        String page = "<!-- Bericht für die Übersicht, © 2026 --><html><head><title>R</title></head></html>";

        String served = write(html(page)).getContentAsString(StandardCharsets.UTF_8);

        assertThat(served).contains("<head>" + new String(shim.tag(), StandardCharsets.UTF_8) + "<title>");
    }

    /**
     * <b>{@code <header>} is not {@code <head>}.</b> A report fragment carries a header and no head, and a
     * plain search for {@code <head} put the shim inside its body instead of after its html tag.
     */
    @Test
    void aPageWithAHeaderButNoHead_carriesTheShimAfterItsHtmlTag() throws Exception {
        String page = "<html><body><header>Report</header><p>Hello</p></body></html>";

        String served = write(html(page)).getContentAsString(StandardCharsets.UTF_8);

        assertThat(served).startsWith("<html>" + new String(shim.tag(), StandardCharsets.UTF_8) + "<body>");
    }

    /** A head tag with attributes, in whatever case a generator wrote it, is still a head tag. */
    @Test
    void aHeadWithAttributesInUpperCase_isStillWhereTheShimGoes() throws Exception {
        String page = "<HTML><HEAD lang=\"en\"><TITLE>R</TITLE></HEAD></HTML>";

        String served = write(html(page)).getContentAsString(StandardCharsets.UTF_8);

        assertThat(served).contains("<HEAD lang=\"en\">" + new String(shim.tag(), StandardCharsets.UTF_8)
                                    + "<TITLE>");
    }

    /** Twice would be a second shim overwriting the first one's values. */
    @Test
    void aPageThatAlreadyCarriesTheShim_isNotGivenASecond() throws Exception {
        String page = "<html><head><script " + MicrositeShim.MARKER + "></script></head><body></body></html>";

        String served = write(html(page)).getContentAsString();

        assertThat(served.split(MicrositeShim.MARKER, -1).length - 1).isEqualTo(1);
        assertThat(served).isEqualTo(page);
    }

    /**
     * The response is not buffered whole - a generated report's page can be megabytes - so only the first
     * window is examined, and a document whose head is past it is served exactly as it is.
     */
    @Test
    void aPageWhoseHeadIsBeyondTheWindow_isServedUnchanged() throws Exception {
        String page = "<!--" + "x".repeat(MicrositeResponse.HEAD_WINDOW) + "--><html><head></head></html>";

        MockHttpServletResponse written = write(html(page));

        assertThat(written.getContentAsString()).isEqualTo(page);
        assertThat(written.getContentLength()).isEqualTo(page.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void aStylesheet_isNeverRewrittenAndIsOfferedAsAnAttachment() throws Exception {
        MockHttpServletResponse written = write(file("body { color: red }", "text/css"));

        assertThat(written.getContentAsString()).isEqualTo("body { color: red }");
        assertThat(written.getHeader(HttpHeaders.CONTENT_DISPOSITION)).isEqualTo("attachment");
    }

    @Test
    void aPage_isNotOfferedAsAnAttachment() throws Exception {
        MockHttpServletResponse written = write(html("<html><head></head></html>"));

        assertThat(written.getHeader(HttpHeaders.CONTENT_DISPOSITION)).isNull();
    }

    /**
     * <b>The tag of a page is derived.</b> Answering with the stored one would tell a reader holding the
     * version without the shim that nothing had changed, and a new shim would never reach anybody.
     */
    @Test
    void theTagOfAPage_saysWhichShimItCarries() throws Exception {
        MockHttpServletResponse written = write(html("<html><head></head></html>"));

        String tag = written.getHeader(HttpHeaders.ETAG);
        assertThat(tag).isNotNull().contains(shim.version())
                .describedAs("not the stored one, or a reader would keep the version without the shim")
                .isNotEqualTo("W/" + STORED_TAG);
        assertThat(written.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-cache");
    }

    @Test
    void theTagOfAFileThatIsNotAPage_isTheStoredOne() throws Exception {
        MockHttpServletResponse written = write(file("console.log(1)", "text/javascript"));

        assertThat(written.getHeader(HttpHeaders.ETAG)).isEqualTo("W/" + STORED_TAG);
    }

    @Test
    void aReaderHoldingTheServedVersion_isAnsweredNotModified() throws Exception {
        String tag = write(html("<html><head></head></html>")).getHeader(HttpHeaders.ETAG);
        MockHttpServletRequest conditional = new MockHttpServletRequest("GET", "/microsites/x");
        conditional.addHeader(HttpHeaders.IF_NONE_MATCH, tag);

        MockHttpServletResponse written = write(html("<html><head></head></html>"), conditional);

        assertThat(written.getStatus()).isEqualTo(HttpStatus.NOT_MODIFIED.value());
        assertThat(written.getContentAsString()).isEmpty();
    }
}
