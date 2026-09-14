package ch.admin.bit.jeap.doc.domain.custom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The order of one chapter, over everything a team put in it.
 * <p>
 * <b>Uploaded pages and microsites are one list.</b> A chapter can hold both, and ordering them separately
 * would put every microsite after every page however they are called - so a reader looking for
 * <i>Configuration Reference</i> would find it under C in one half of the chapter and nowhere in the other.
 * A page is ordered by its title and a microsite by its label, which is what each of them shows.
 * <p>
 * A chapter with no microsite comes out exactly as the upload numbered it: the sort is the same one
 * {@link UploadedSet} applies, so nothing about markdown changes.
 */
public final class ChapterOrder {

    private final Map<String, Integer> positions;

    private ChapterOrder(Map<String, Integer> positions) {
        this.positions = positions;
    }

    /**
     * The order of one chapter.
     *
     * @param pages      the uploaded pages of that chapter, assets not among them
     * @param microsites the microsites embedded in it
     */
    public static ChapterOrder of(List<CustomPage> pages, List<Microsite> microsites) {
        List<Entry> entries = new ArrayList<>();
        pages.forEach(page -> entries.add(new Entry(page.fileName(), sortKeyOf(page))));
        microsites.forEach(microsite ->
                entries.add(new Entry(microsite.fileName(), microsite.sortKey())));
        entries.sort(Comparator.comparing(Entry::sortKey, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Entry::fileName));
        Map<String, Integer> positions = new HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            positions.put(entries.get(i).fileName(), i + 1);
        }
        return new ChapterOrder(positions);
    }

    /** Where this page goes, numbered from one within its chapter. */
    public int positionOf(CustomPage page) {
        return positions.getOrDefault(page.fileName(), page.position());
    }

    /** Where this microsite's page goes, in the same numbering. */
    public int positionOf(Microsite microsite) {
        return positions.getOrDefault(microsite.fileName(), 1);
    }

    /** A page with no title is ordered by its file name, exactly as the upload ordered it. */
    private static String sortKeyOf(CustomPage page) {
        return page.title() == null ? page.fileName() : page.title();
    }

    private record Entry(String fileName, String sortKey) {
    }
}
