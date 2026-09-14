package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.custom.CustomProvenance;
import ch.admin.bit.jeap.doc.domain.custom.CustomSet;
import ch.admin.bit.jeap.doc.domain.custom.CustomSetKey;
import ch.admin.bit.jeap.doc.domain.custom.MicrositePageText;
import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * What a microsite contributes to the index, and where each of its pages sends a reader.
 * <p>
 * The expansion is driven by the page that frames a microsite, because that page is written once into every
 * environment tree the subject is documented in - which is what gets a hit into the tree the reader is in.
 */
class MicrositeSearchRecordsTest {

    private static final String MICROSITE_URL = "/microsites/orders/arc42/2-constraints/reference/";

    private static final String PAGE_URL = "/prod/systems/orders/system-architecture/constraints/"
                                           + "microsites/reference/";

    @Test
    void expand_thenEveryPageBelowTheEntryPointIsARecordThatOpensTheFrameAtIt() {
        List<SearchRecord> records = expand(List.of(framingPage(PAGE_URL, "prod")),
                text(Map.of("index.html", "The entry point",
                        "pages/properties.html", "Every property, one by one")));

        assertThat(records).extracting(SearchRecord::url, SearchRecord::title, SearchRecord::source)
                .contains(tuple(PAGE_URL + "?path=pages/properties.html", "pages/properties.html",
                        SearchRecord.HTML));
    }

    /**
     * <b>The entry point is folded into the page that frames it</b> rather than becoming a record of its own:
     * two records cannot share a URL, and that page is where a reader opening the microsite starts anyway.
     */
    @Test
    void expand_thenTheEntryPointIsTheFramingPagesOwnText() {
        List<SearchRecord> records = expand(List.of(framingPage(PAGE_URL, "prod")),
                text(Map.of("index.html", "Every property of this service")));

        assertThat(records).hasSize(1);
        assertThat(records.getFirst().url()).isEqualTo(PAGE_URL);
        assertThat(records.getFirst().body()).contains("Every property of this service");
    }

    /** A record keeps the environment, the system and the subject of the page that frames it. */
    @Test
    void expand_thenARecordIsWhereTheFramingPageIs() {
        List<SearchRecord> records = expand(List.of(framingPage(PAGE_URL, "prod")),
                text(Map.of("index.html", "The entry point", "a.html", "Something")));

        SearchRecord inside = records.stream().filter(r -> r.url().contains("?path=")).findFirst()
                .orElseThrow();
        assertThat(inside.environment()).isEqualTo("prod");
        assertThat(inside.system()).isEqualTo("orders");
        assertThat(inside.subject()).isEqualTo(SearchRecord.SYSTEM);
        assertThat(inside.microsite()).describedAs("which uploaded documentation a hit is inside")
                .isEqualTo("Configuration Reference");
    }

    /**
     * <b>One set, two environment trees, two sets of records.</b> The microsite's files are served from one
     * place while the page that frames them is written into every tree the subject is documented in - and a
     * hit has to open the tree the reader is in, because every query the site makes carries an environment.
     */
    @Test
    void expand_whenTheSubjectIsDocumentedInTwoEnvironments_thenEachTreeGetsItsOwnRecords() {
        List<SearchRecord> records = expand(
                List.of(framingPage("/prod/systems/orders/x/microsites/reference/", "prod"),
                        framingPage("/dev/systems/orders/x/microsites/reference/", "dev")),
                text(Map.of("index.html", "The entry point", "a.html", "Something")));

        assertThat(records).extracting(SearchRecord::url, SearchRecord::environment)
                .contains(tuple("/prod/systems/orders/x/microsites/reference/?path=a.html", "prod"),
                        tuple("/dev/systems/orders/x/microsites/reference/?path=a.html", "dev"));
    }

    /** A path is a query parameter value, so what a file name may hold is encoded - the slashes are not. */
    @Test
    void expand_thenAPathIsEncodedSegmentBySegment() {
        List<SearchRecord> records = expand(List.of(framingPage(PAGE_URL, "prod")),
                text(Map.of("index.html", "The entry point", "a b/c&d.html", "Something")));

        assertThat(records).extracting(SearchRecord::url)
                .contains(PAGE_URL + "?path=a%20b/c%26d.html");
    }

    /**
     * A set uploaded before the text was ever extracted, or to an instance that was not indexing: the page
     * that frames it stays findable, its content is not, and no run fails over it.
     */
    @Test
    void expand_whenTheSetCarriesNoText_thenOnlyTheFramingPageIsIndexed() {
        List<SearchRecord> records = expand(List.of(framingPage(PAGE_URL, "prod")), text(Map.of()));

        assertThat(records).hasSize(1);
        assertThat(records.getFirst().url()).isEqualTo(PAGE_URL);
    }

    /** A page that frames nothing is a page like any other, and a site with no microsite is untouched. */
    @Test
    void expand_whenThereIsNoMicrosite_thenThePagesAreWhatTheyWere() {
        List<SearchRecord> pages = List.of(new SearchRecord("/prod/systems/orders/", "Orders", List.of(),
                "The system.", "prod", SearchRecord.GENERATED, SearchRecord.SYSTEM, "orders", null, null,
                null));

        assertThat(MicrositeSearchRecords.expand(pages, List.of(), new NoCustomStorage())).isEqualTo(pages);
    }

    private static List<SearchRecord> expand(List<SearchRecord> pages, NoCustomStorage storage) {
        return MicrositeSearchRecords.expand(pages, List.of(micrositeSet()), storage);
    }

    private static SearchRecord framingPage(String url, String environment) {
        return new SearchRecord(url, "Configuration Reference", List.of(),
                "Published by the team that owns it.", environment, SearchRecord.HTML, SearchRecord.SYSTEM,
                "orders", null, MICROSITE_URL, null);
    }

    private static CustomSet micrositeSet() {
        CustomSetKey key = new CustomSetKey(Site.DEFAULT_SITE, SubjectKind.SYSTEM, "orders", null,
                SourceFormat.HTML, "arc42", "2-constraints", "reference");
        return new CustomSet(1L, key, "Configuration Reference", 1L, "current/docs/1/1/files/", "abc", 10,
                new CustomProvenance("repo", "main", "abc", Instant.EPOCH, null, Instant.EPOCH), List.of());
    }

    /** The text as it was stored when the set was uploaded, keyed by the prefix the row names. */
    private static NoCustomStorage text(Map<String, String> pages) {
        return new NoCustomStorage() {
            @Override
            public List<MicrositePageText> readSearchText(String prefix) {
                return pages.entrySet().stream()
                        .map(page -> new MicrositePageText(page.getKey(), page.getKey(), page.getValue()))
                        .toList();
            }
        };
    }
}
