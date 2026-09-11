package ch.admin.bit.jeap.doc.domain.custom;

import ch.admin.bit.jeap.doc.domain.port.UploadedBundles;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A path becomes a chapter and a file name, and the pages of a chapter get their order. */
class UploadedSetTest {

    /** A bundle that is only its front matter, which is all the ordering reads. */
    private record Titles(Map<String, String> titles) implements UploadedBundles.ReceivedBundle {

        @Override
        public List<String> paths() {
            return List.copyOf(titles.keySet());
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
        public byte[] head(String path, int maxBytes) {
            String title = titles.get(path);
            return title == null ? new byte[0]
                    : ("---\ntitle: " + title + "\n---\n").getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void close() {
            // nothing to release: this bundle is a map of titles.
        }
    }

    /**
     * The other half of {@code IgnoredPaths}: the validation drops the files nobody wrote before any rule
     * runs, and a set must not record them either - a recorded one would be copied into the generated site
     * and charged to what a set may unpack to.
     */
    @Test
    void aFileNobodyWrote_isNotEvenRecorded() {
        List<CustomPage> pages = UploadedSet.pagesOf(
                List.of("1-intro/.DS_Store", "1-intro/goals.md", "1-intro/._goals.md", "__MACOSX/1-intro/x",
                        "1-intro/Thumbs.db", "1-intro/goals.md~"),
                new Titles(new java.util.LinkedHashMap<>(Map.of("1-intro/goals.md", "Goals"))));

        assertThat(pages).extracting(CustomPage::fileName).containsExactly("goals.md");
    }

    @Test
    void aPage_isItsChapterAndItsFileName() {
        List<CustomPage> pages = UploadedSet.pagesOf(List.of("1-intro/goals.md"),
                new Titles(Map.of("1-intro/goals.md", "Goals")));

        assertThat(pages).singleElement().satisfies(page -> {
            assertThat(page.chapter()).isEqualTo("1-intro");
            assertThat(page.fileName()).isEqualTo("goals.md");
            assertThat(page.title()).isEqualTo("Goals");
            assertThat(page.asset()).isFalse();
            assertThat(page.position()).isEqualTo(1);
        });
    }

    @Test
    void thePagesOfAChapter_areOrderedByTheirTitles() {
        List<String> paths = List.of("2-constraints/aaa.md", "2-constraints/mmm.md", "2-constraints/zzz.md");
        List<CustomPage> pages = UploadedSet.pagesOf(paths, new Titles(Map.of(
                "2-constraints/aaa.md", "Zulu",
                "2-constraints/mmm.md", "Mike",
                "2-constraints/zzz.md", "Alpha")));

        assertThat(pages)
                .describedAs("the file name would have given exactly the opposite order")
                .extracting(CustomPage::fileName).containsExactly("zzz.md", "mmm.md", "aaa.md");
        assertThat(pages).extracting(CustomPage::position).containsExactly(1, 2, 3);
    }

    @Test
    void aPageWithNoTitle_isOrderedByItsFileName() {
        List<CustomPage> pages = UploadedSet.pagesOf(
                List.of("12-glossary/terms.md", "12-glossary/abbreviations.md"),
                new Titles(Map.of("12-glossary/terms.md", "Terms")));

        assertThat(pages).extracting(CustomPage::fileName)
                .containsExactly("abbreviations.md", "terms.md");
    }

    @Test
    void twoPagesWithOneTitle_areOrderedByTheirFileNames() {
        List<CustomPage> pages = UploadedSet.pagesOf(
                List.of("1-intro/b.md", "1-intro/a.md"),
                new Titles(Map.of("1-intro/b.md", "Goals", "1-intro/a.md", "Goals")));

        assertThat(pages).describedAs("so the order never depends on how the archive listed them")
                .extracting(CustomPage::fileName).containsExactly("a.md", "b.md");
    }

    @Test
    void eachChapter_isNumberedFromOne() {
        List<CustomPage> pages = UploadedSet.pagesOf(
                List.of("1-intro/a.md", "1-intro/b.md", "12-glossary/z.md"),
                new Titles(Map.of("1-intro/a.md", "A", "1-intro/b.md", "B", "12-glossary/z.md", "Z")));

        assertThat(pages).filteredOn(page -> page.chapter().equals("1-intro"))
                .extracting(CustomPage::position).containsExactly(1, 2);
        assertThat(pages).filteredOn(page -> page.chapter().equals("12-glossary"))
                .extracting(CustomPage::position).containsExactly(1);
    }

    @Test
    void anImage_isAnAssetAndIsNotOrdered() {
        List<CustomPage> pages = UploadedSet.pagesOf(
                List.of("3-context-and-scope/context.png", "3-context-and-scope/who.md"),
                new Titles(Map.of("3-context-and-scope/who.md", "Who we talk to")));

        assertThat(pages).filteredOn(CustomPage::asset).singleElement().satisfies(asset -> {
            assertThat(asset.fileName()).isEqualTo("context.png");
            assertThat(asset.title()).isNull();
            assertThat(asset.position()).isZero();
        });
        assertThat(pages).filteredOn(page -> !page.asset()).hasSize(1);
    }

    @Test
    void aFileBelongingToNoChapter_isNotPlaced() {
        List<CustomPage> pages = UploadedSet.pagesOf(List.of("README.md", "1-intro/goals.md"),
                new Titles(Map.of("1-intro/goals.md", "Goals")));

        assertThat(pages).describedAs("the upload was already refused over it")
                .extracting(CustomPage::path).containsExactly("1-intro/goals.md");
    }

    @Test
    void theCaseOfATitle_doesNotDecideTheOrder() {
        List<CustomPage> pages = UploadedSet.pagesOf(List.of("1-intro/a.md", "1-intro/b.md"),
                new Titles(Map.of("1-intro/a.md", "beta", "1-intro/b.md", "Alpha")));

        assertThat(pages).extracting(CustomPage::fileName).containsExactly("b.md", "a.md");
    }
}
