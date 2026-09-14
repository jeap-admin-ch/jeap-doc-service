package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.custom.CustomSet;
import ch.admin.bit.jeap.doc.domain.custom.Microsite;
import ch.admin.bit.jeap.doc.domain.custom.MicrositePageText;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationStorage;
import lombok.extern.slf4j.Slf4j;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the one page that frames a microsite into one record per page inside it.
 * <p>
 * <b>This is the only place that knows what an uploaded microsite is</b>, and that is deliberate:
 * {@link SearchRecords} reads a content tree and knows nothing about uploads, which is what lets it index a
 * page a team wrote with the same code as a page the service generated.
 * <p>
 * <b>It is driven by the pages that were written, not by the sets.</b> The page that frames a microsite is
 * written once into every environment tree the subject is documented in, and it carries that tree's URL - so
 * expanding it gets the per-environment records right without deriving a route from anything. A record with no
 * environment would be invisible to every query the site makes.
 */
@Slf4j
final class MicrositeSearchRecords {

    private MicrositeSearchRecords() {
    }

    /**
     * Every record of the site: the pages as they were read, and the content of the microsites they frame.
     *
     * @param pages      what {@link SearchRecords} read out of the content tree
     * @param microsites the uploaded microsites of this site
     * @param storage    where the text extracted at upload lies
     */
    static List<SearchRecord> expand(List<SearchRecord> pages, List<CustomSet> microsites,
                                     CustomDocumentationStorage storage) {
        if (microsites.isEmpty()) {
            return pages;
        }
        Map<String, CustomSet> byUrl = new HashMap<>();
        for (CustomSet set : microsites) {
            byUrl.put(Microsite.of(set).url(), set);
        }
        // Read once per set, however many environment trees frame it: the same upload is published into each
        // of them, and what it says cannot differ between them.
        Map<String, List<MicrositePageText>> textByUrl = new HashMap<>();

        List<SearchRecord> records = new ArrayList<>(pages.size());
        for (SearchRecord page : pages) {
            records.addAll(recordsOf(page, byUrl, textByUrl, storage));
        }
        return List.copyOf(records);
    }

    /** The records one page contributes: itself, or - where it frames a microsite with text - that text too. */
    private static List<SearchRecord> recordsOf(SearchRecord page, Map<String, CustomSet> byUrl,
                                                Map<String, List<MicrositePageText>> textByUrl,
                                                CustomDocumentationStorage storage) {
        if (page.micrositeUrl() == null) {
            return List.of(page);
        }
        CustomSet set = byUrl.get(page.micrositeUrl());
        if (set == null) {
            // A page written from a row that is no longer there: an upload removed the set between the
            // content pass and now. The page stays findable, its content is not.
            log.debug("The page {} frames {}, which is no longer a set of this site.",
                    page.url(), page.micrositeUrl());
            return List.of(page);
        }
        List<MicrositePageText> text = textByUrl.computeIfAbsent(page.micrositeUrl(),
                url -> storage.readSearchText(set.objectKey()));
        if (text.isEmpty()) {
            // Uploaded before the text was extracted, or to an instance that was not indexing. Only a new
            // upload can produce it, and docs/search.md says so.
            log.info("The microsite {} carries no extracted text, so only the page that frames it is "
                     + "indexed.", page.micrositeUrl());
            return List.of(page);
        }
        List<SearchRecord> records = new ArrayList<>();
        records.add(theFramingPage(page, text));
        records.addAll(insideTheMicrosite(page, text));
        return records;
    }

    /**
     * The framing page, carrying the text of the microsite's entry point.
     * <p>
     * <b>Folded in rather than a record of its own.</b> Two records cannot share a URL, and the entry point is
     * what this page opens - so a reader who searches for something on the front page of a microsite lands on
     * the page that frames it, which is where they would have to start anyway.
     */
    private static SearchRecord theFramingPage(SearchRecord page, List<MicrositePageText> text) {
        return text.stream()
                .filter(inside -> ENTRY_POINT.equals(inside.path()))
                .findFirst()
                .map(entry -> new SearchRecord(page.url(), page.title(), page.headings(),
                        page.body() + " " + entry.text(), page.environment(), page.source(), page.subject(),
                        page.system(), page.name(), page.micrositeUrl(), null))
                .orElse(page);
    }

    /** One record per page below the entry point, each opening the frame at that page. */
    private static List<SearchRecord> insideTheMicrosite(SearchRecord page, List<MicrositePageText> text) {
        List<SearchRecord> inside = new ArrayList<>();
        for (MicrositePageText read : text) {
            if (ENTRY_POINT.equals(read.path())) {
                continue;
            }
            inside.add(new SearchRecord(page.url() + "?path=" + encoded(read.path()), read.title(),
                    List.of(), read.text(), page.environment(), SearchRecord.HTML, page.subject(),
                    page.system(), page.name(), null, page.title()));
        }
        return inside;
    }

    /**
     * The path as a query parameter value, one segment at a time: the slashes stay, because the page that
     * reads it checks that it is a relative path.
     */
    private static String encoded(String path) {
        StringBuilder encoded = new StringBuilder(path.length() + 8);
        for (String segment : path.split("/", -1)) {
            if (!encoded.isEmpty()) {
                encoded.append('/');
            }
            encoded.append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return encoded.toString();
    }

    /** The page a frame opens, spelled where the search index can read it without the upload rules. */
    private static final String ENTRY_POINT = "index.html";
}
