package ch.admin.bit.jeap.doc.html;

import ch.admin.bit.jeap.doc.domain.port.HtmlText;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Reads a document with jsoup.
 * <p>
 * <b>Nothing here decides what text is worth having.</b> jsoup parses the way a browser does, which is what an
 * uploaded document needs: it was produced by somebody else's build, it may be from before HTML5, and it may
 * have been cut off at a byte bound. A parser that refused such a document would refuse exactly the pages
 * worth indexing.
 */
@Slf4j
@RequiredArgsConstructor
class JsoupHtmlText implements HtmlText {

    private final DocHtmlProperties properties;

    @Override
    public Extracted of(byte[] html) {
        if (html == null || html.length == 0) {
            return Extracted.NOTHING;
        }
        Document document = parse(html);
        // Before the navigation goes: the title is in the head, but an h1 that stands in for it is not.
        String title = title(document);
        document.select(properties.ignoredSelectorQuery()).remove();
        Element body = document.body();
        return new Extracted(title, body == null ? "" : body.text());
    }

    private static Document parse(byte[] html) {
        try {
            // A null charset means the document's own declaration decides, falling back to UTF-8.
            return Jsoup.parse(new ByteArrayInputStream(html), null, "");
        } catch (IOException e) {
            // Reading a byte array cannot fail; this is here because the signature says it can.
            throw new UncheckedIOException(e);
        }
    }

    private static String title(Document document) {
        String title = document.title().trim();
        if (!title.isEmpty()) {
            return title;
        }
        Element heading = document.selectFirst("h1");
        return heading == null ? "" : heading.text().trim();
    }
}
