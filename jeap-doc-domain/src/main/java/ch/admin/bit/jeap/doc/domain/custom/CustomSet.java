package ch.admin.bit.jeap.doc.domain.custom;

import java.util.Comparator;
import java.util.List;

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
        List<CustomPage> pages) {

    public CustomSet {
        pages = pages == null ? List.of() : List.copyOf(pages);
    }

    public CustomSubject subject() {
        return key.subject();
    }

    /** The pages of one chapter, in the order the navigation shows them. */
    public List<CustomPage> pagesOf(String chapterFolder) {
        return pages.stream()
                .filter(page -> page.chapter().equals(chapterFolder) && !page.asset())
                .sorted(Comparator.comparingInt(CustomPage::position))
                .toList();
    }

    /** The images of one chapter, which are beside a page rather than in the navigation. */
    public List<CustomPage> assetsOf(String chapterFolder) {
        return pages.stream()
                .filter(page -> page.chapter().equals(chapterFolder) && page.asset())
                .toList();
    }
}
