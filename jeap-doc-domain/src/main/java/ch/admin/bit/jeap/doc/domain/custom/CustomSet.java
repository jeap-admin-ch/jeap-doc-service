package ch.admin.bit.jeap.doc.domain.custom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One documentation set, as it is currently published.
 * <p>
 * The bytes are one object in the storage and the files are rows here. The object key carries
 * {@code revision}, so a set that is replaced is a different object rather than the same one overwritten -
 * which is what lets a build read the rows and then fetch the object without being able to see a set that is
 * half replaced.
 *
 * @param id          the identifier of the set, assigned when it is first taken over
 * @param key         what this set documents, and what a further upload of it replaces
 * @param label       the menu label of an HTML microsite; null for markdown, whose pages carry titles
 * @param revision    the upload this set came from, as the doc service numbered it
 * @param objectKey   where the bundle lies
 * @param sha256      the SHA-256 of that object, which is the one the uploading pipeline was told
 * @param sizeInBytes the size of that object
 * @param provenance  where the documents came from
 * @param pages       the files of the set, pages and assets alike
 * @param hint        where an HTML microsite asked to go among the other items of its chapter, read from the
 *                    upload parameters of the same three names; {@link OrderHint#NONE} for markdown, whose
 *                    pages carry their own hint instead, and for a microsite that gave none
 */
public record CustomSet(
        Long id,
        CustomSetKey key,
        String label,
        long revision,
        String objectKey,
        String sha256,
        long sizeInBytes,
        CustomProvenance provenance,
        List<CustomPage> pages,
        OrderHint hint) {

    public CustomSet {
        pages = pages == null ? List.of() : List.copyOf(pages);
        hint = hint == null ? OrderHint.NONE : hint;
    }

    /** A set with no declared placement among its siblings - every one before this hint existed. */
    public CustomSet(Long id, CustomSetKey key, String label, long revision, String objectKey, String sha256,
                      long sizeInBytes, CustomProvenance provenance, List<CustomPage> pages) {
        this(id, key, label, revision, objectKey, sha256, sizeInBytes, provenance, pages, OrderHint.NONE);
    }

    public CustomSubject subject() {
        return key.subject();
    }

    /**
     * The pages directly in one chapter, in the order the navigation shows them - a page inside a group is
     * not among them, because it is not a sibling of the chapter's own items.
     */
    public List<CustomPage> pagesOf(String chapterFolder) {
        return pages.stream()
                .filter(page -> page.chapter().equals(chapterFolder) && !page.asset() && !isGrouped(page))
                .sorted(Comparator.comparingInt(CustomPage::position))
                .toList();
    }

    /**
     * The groups of one chapter: every folder directly below it that holds at least one page, each with its
     * own landing page separated out already - see {@link PageGroup}.
     * <p>
     * A folder holding only assets is not among them; it stays an asset folder, as it always was.
     */
    public List<PageGroup> groupsOf(String chapterFolder) {
        Map<String, List<CustomPage>> byFolder = new LinkedHashMap<>();
        for (CustomPage page : pages) {
            if (!page.chapter().equals(chapterFolder) || page.asset() || !isGrouped(page)) {
                continue;
            }
            byFolder.computeIfAbsent(groupFolderOf(page), key -> new ArrayList<>()).add(page);
        }
        List<PageGroup> groups = new ArrayList<>();
        byFolder.forEach((folder, pagesInGroup) -> groups.add(PageGroup.of(folder, pagesInGroup)));
        return groups;
    }

    /** Whether this page lies in a group folder rather than directly in its chapter. */
    private static boolean isGrouped(CustomPage page) {
        return page.fileName().indexOf('/') > 0;
    }

    private static String groupFolderOf(CustomPage page) {
        return page.fileName().substring(0, page.fileName().indexOf('/'));
    }

    /**
     * The assets of one chapter: the files its pages show or link to, beside them or in folders inside the
     * chapter, and never in the navigation.
     */
    public List<CustomPage> assetsOf(String chapterFolder) {
        return pages.stream()
                .filter(page -> page.chapter().equals(chapterFolder) && page.asset())
                .toList();
    }
}
