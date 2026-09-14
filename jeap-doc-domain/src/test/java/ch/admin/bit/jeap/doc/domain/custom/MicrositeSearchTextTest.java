package ch.admin.bit.jeap.doc.domain.custom;

import ch.admin.bit.jeap.doc.domain.SearchProperties;
import ch.admin.bit.jeap.doc.domain.port.HtmlText;
import ch.admin.bit.jeap.doc.domain.port.UploadedBundles;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which pages of a microsite are indexed, in which order, and what stands in for a title.
 * <p>
 * What the text of one page is belongs to the parser and is asserted where the parser is; this is about the
 * bounds around it - the entry point first, the cap, and only the files a reader reads.
 */
class MicrositeSearchTextTest {

    /** A parser that answers what the file says, so the assertions are about the selection, not the parse. */
    private final HtmlText html = bytes -> {
        String content = new String(bytes, StandardCharsets.UTF_8);
        return content.isBlank() ? HtmlText.Extracted.NOTHING
                : new HtmlText.Extracted(content.startsWith("#") ? content.substring(1) : "", content);
    };

    @Test
    void theEntryPoint_isIndexedFirstHoweverTheArchiveListsIt() {
        List<MicrositePageText> pages = extract(bundle(
                "a/deep.html", "the deep one",
                "index.html", "the entry point",
                "b/other.html", "another"));

        assertThat(pages).extracting(MicrositePageText::path)
                .containsExactly("index.html", "a/deep.html", "b/other.html");
    }

    @Test
    void onlyWhatAReaderReads_isIndexed() {
        List<MicrositePageText> pages = extract(bundle(
                "index.html", "the entry point",
                "style.css", "h1 { color: red }",
                "logo.png", "not text at all",
                "legacy.htm", "an older page",
                "data/rows.csv", "a,b,c"));

        assertThat(pages).extracting(MicrositePageText::path)
                .containsExactly("index.html", "legacy.htm");
    }

    /** <b>One upload may not be the result list.</b> Past the cap a set contributes nothing more. */
    @Test
    void pastTheCap_nothingMoreIsIndexed() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("index.html", "the entry point");
        for (int i = 0; i < 10; i++) {
            files.put("page-" + i + ".html", "page " + i);
        }
        SearchProperties bounded = new SearchProperties();
        bounded.setMaxMicrositePages(4);

        List<MicrositePageText> pages = MicrositeSearchText.of(new Files(files), html, bounded);

        assertThat(pages).extracting(MicrositePageText::path)
                .containsExactly("index.html", "page-0.html", "page-1.html", "page-2.html");
    }

    @Test
    void aPageThatNamesItself_isCalledThat_andOneThatDoesNotIsCalledAfterItsPath() {
        List<MicrositePageText> pages = extract(bundle(
                "index.html", "#Configuration Reference",
                "plain.html", "no title in here"));

        assertThat(pages).extracting(MicrositePageText::title)
                .containsExactly("Configuration Reference", "plain.html");
    }

    /** A redirect stub or a frameset says nothing, and a result that shows nothing is worse than none. */
    @Test
    void aPageWithNothingOnIt_isNotARecord() {
        assertThat(extract(bundle("index.html", "the entry point", "empty.html", "  ")))
                .extracting(MicrositePageText::path).containsExactly("index.html");
    }

    private List<MicrositePageText> extract(UploadedBundles.ReceivedBundle bundle) {
        return MicrositeSearchText.of(bundle, html, new SearchProperties());
    }

    private static UploadedBundles.ReceivedBundle bundle(String... pathsAndContent) {
        Map<String, String> files = new LinkedHashMap<>();
        for (int i = 0; i < pathsAndContent.length; i += 2) {
            files.put(pathsAndContent[i], pathsAndContent[i + 1]);
        }
        return new Files(files);
    }

    /** A bundle that is its files, read as far as a caller asks. */
    private record Files(Map<String, String> files) implements UploadedBundles.ReceivedBundle {

        @Override
        public List<String> paths() {
            return List.copyOf(files.keySet());
        }

        @Override
        public byte[] head(String path, int maxBytes) {
            byte[] content = files.getOrDefault(path, "").getBytes(StandardCharsets.UTF_8);
            return content.length <= maxBytes ? content : java.util.Arrays.copyOf(content, maxBytes);
        }

        @Override
        public long declaredUnpackedSize() {
            return 0;
        }

        @Override
        public String sha256() {
            return "";
        }

        @Override
        public long sizeInBytes() {
            return 0;
        }

        @Override
        public void close() {
            // Nothing to release: the content is held in memory.
        }
    }
}
