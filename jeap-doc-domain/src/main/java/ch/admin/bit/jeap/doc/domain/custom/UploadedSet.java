package ch.admin.bit.jeap.doc.domain.custom;

import ch.admin.bit.jeap.doc.domain.port.UploadedBundles;
import ch.admin.bit.jeap.doc.domain.template.DocumentationPaths;
import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import ch.admin.bit.jeap.doc.domain.upload.validation.IgnoredPaths;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the paths of an uploaded archive into the files of a documentation set.
 * <p>
 * Two things happen here and nowhere else: a path becomes a chapter and a file name below it, and the pages of a
 * chapter are put in the order the navigation shows them.
 */
public final class UploadedSet {

    private UploadedSet() {
    }

    /**
     * The files of a set, as the format it was uploaded in records them.
     * <p>
     * <b>An HTML set records none.</b> A microsite is published as it is and served file by file: nothing
     * writes a page per file of it, so rows for its hundreds of assets would be rows nothing reads. What
     * names it in the navigation is the set's label.
     */
    public static List<CustomPage> pagesOf(SourceFormat sourceFormat, List<String> paths,
                                           UploadedBundles.ReceivedBundle bundle) {
        return sourceFormat == SourceFormat.HTML ? List.of() : pagesOf(paths, bundle);
    }

    /**
     * The files of a set, with the pages of each chapter ordered by their titles.
     * <p>
     * <b>The order is assigned here rather than left to the site generator.</b> Docusaurus sorts the items of
     * a folder by their position and, where there is none, by their file name - so a page called
     * {@code zebra.md} titled <i>Alpha</i> would come last. An upload may not set a position itself, which
     * keeps the order in one place: this one.
     *
     * @param paths  the files of the archive, as it lists them; the ones nobody wrote are dropped
     * @param bundle where a page's front matter is read from
     */
    public static List<CustomPage> pagesOf(List<String> paths, UploadedBundles.ReceivedBundle bundle) {
        Map<String, List<CustomPage>> byChapter = new LinkedHashMap<>();
        List<CustomPage> assets = new ArrayList<>();
        for (String path : paths) {
            // The first slash, not the last: an asset may lie in a folder inside its chapter.
            int slash = path.indexOf('/');
            // Two kinds of path that place nothing. A file nobody wrote - the validation drops it before any
            // rule runs, and this is the other half of that, so it is never copied into a site nor charged to
            // what a set may unpack to. And a file belonging to no chapter, which the validation has already
            // refused the upload over; this is the ordering, and it has nothing to place.
            if (IgnoredPaths.isIgnored(path) || slash <= 0 || path.endsWith("/")) {
                continue;
            }
            String chapter = path.substring(0, slash);
            String fileName = path.substring(slash + 1);
            if (isAPage(fileName)) {
                String title = UploadedTitles.titleOf(bundle.head(path, UploadedTitles.HEAD_BYTES));
                byChapter.computeIfAbsent(chapter, key -> new ArrayList<>())
                        .add(new CustomPage(chapter, fileName, title, 0, false));
            } else {
                assets.add(new CustomPage(chapter, fileName, null, 0, true));
            }
        }
        List<CustomPage> pages = new ArrayList<>();
        byChapter.values().forEach(inChapter -> pages.addAll(ordered(inChapter)));
        pages.addAll(assets);
        return List.copyOf(pages);
    }

    /**
     * The pages of one chapter, numbered from one in the order of their titles.
     * <p>
     * The file name decides where a page has no title, and it breaks the tie between two pages that have the
     * same one - so the order of a chapter never depends on the order the archive happened to list it in.
     */
    private static List<CustomPage> ordered(List<CustomPage> inChapter) {
        List<CustomPage> sorted = inChapter.stream()
                .sorted(Comparator.comparing(UploadedSet::sortKeyOf, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(CustomPage::fileName))
                .toList();
        List<CustomPage> numbered = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            CustomPage page = sorted.get(i);
            numbered.add(new CustomPage(page.chapter(), page.fileName(), page.title(), i + 1, false));
        }
        return numbered;
    }

    private static String sortKeyOf(CustomPage page) {
        return page.title() == null ? page.fileName() : page.title();
    }

    /** A page is what the site generator renders; everything else a set may carry is an asset beside one. */
    private static boolean isAPage(String fileName) {
        return fileName.toLowerCase(Locale.ROOT)
                .endsWith("." + DocumentationPaths.MARKDOWN_EXTENSION);
    }
}
