package ch.admin.bit.jeap.doc.domain.custom;

import ch.admin.bit.jeap.doc.domain.SearchProperties;
import ch.admin.bit.jeap.doc.domain.port.HtmlText;
import ch.admin.bit.jeap.doc.domain.port.UploadedBundles;
import ch.admin.bit.jeap.doc.domain.upload.validation.MicrositeRules;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The text of a microsite's pages, taken out of the bundle that was just uploaded.
 * <p>
 * <b>Once per upload rather than once per publication.</b> A set is immutable between uploads and is published
 * many times, in as many environment trees as the subject is documented in - so reading the HTML again on
 * every index run would be work repeated for an answer that cannot have changed.
 * <p>
 * <b>And bounded, because one microsite is more documents than a whole system's generated tree.</b> A Javadoc
 * of 519 pages would fill every hit of a result list on its own, so a set contributes at most
 * {@link SearchProperties#getMaxMicrositePages()} pages, the entry point first.
 */
@Slf4j
public final class MicrositeSearchText {

    private MicrositeSearchText() {
    }

    /**
     * Every page of the set that is worth indexing, in the order they are indexed in.
     *
     * @param received the bundle as it was received, still on its file
     * @param html     what reads one document
     * @param search   the bounds: how many pages, and how much of one
     */
    public static List<MicrositePageText> of(UploadedBundles.ReceivedBundle received, HtmlText html,
                                             SearchProperties search) {
        List<String> pages = pagesOf(received.paths(), search.getMaxMicrositePages());
        List<MicrositePageText> text = new ArrayList<>(pages.size());
        for (String path : pages) {
            HtmlText.Extracted extracted = html.of(received.head(path, search.maxMicrositePageBytes()));
            if (extracted.isEmpty()) {
                // A page that says nothing - a frameset, a redirect stub - is not a result worth returning.
                continue;
            }
            text.add(new MicrositePageText(path,
                    extracted.title().isBlank() ? path : extracted.title(), extracted.text()));
        }
        return List.copyOf(text);
    }

    /**
     * Which pages are read: the entry point first, then the rest in the order the archive lists them.
     * <p>
     * The entry point is first because it is the one page that must always be findable - it is what the frame
     * opens, and what the cap may not cut.
     */
    private static List<String> pagesOf(List<String> paths, int maxPages) {
        List<String> pages = new ArrayList<>();
        if (paths.contains(MicrositeRules.ENTRY_POINT)) {
            pages.add(MicrositeRules.ENTRY_POINT);
        }
        for (String path : paths) {
            if (pages.size() >= maxPages) {
                log.info("A microsite holds more than {} pages; the ones past that are not indexed.", maxPages);
                break;
            }
            if (isAPage(path) && !path.equals(MicrositeRules.ENTRY_POINT)) {
                pages.add(path);
            }
        }
        return pages;
    }

    /** Only what a reader reads. Everything else a microsite carries is an asset of one of these pages. */
    private static boolean isAPage(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".html") || lower.endsWith(".htm");
    }
}
