package ch.admin.bit.jeap.doc.domain.custom;

import ch.admin.bit.jeap.doc.domain.template.NumberPrefixes;
import ch.admin.bit.jeap.doc.domain.template.ReservedNames;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One group: a folder directly below a chapter that holds at least one page, as the ordering and the site
 * generator need to see it.
 * <p>
 * A folder holding only assets is not a group - it stays an asset folder, as it always was. A group has at
 * most one level: a page inside a group's own folder is refused before this is ever built.
 *
 * @param folder      the folder's own name, exactly as it was uploaded - a number prefix and all
 * @param label       the group's title: its landing page's title where one names one, the folder's stripped
 *                    name otherwise
 * @param hint        where the group asked to go among the other items of its chapter, read from its landing
 *                    page's front matter; {@link OrderHint#NONE} where no landing page was uploaded
 * @param pages       the group's own pages, in no particular order; its landing page is never among them
 * @param landingPage the page uploaded as the group's own landing page, if there is one
 */
public record PageGroup(String folder, String label, OrderHint hint, List<CustomPage> pages,
                        Optional<CustomPage> landingPage) {

    public PageGroup {
        pages = pages == null ? List.of() : List.copyOf(pages);
        hint = hint == null ? OrderHint.NONE : hint;
    }

    /**
     * Builds the group from every non-asset page uploaded into this one folder, separating its landing page
     * from the rest.
     *
     * @param folder       the folder's own name, exactly as it was uploaded
     * @param pagesInGroup every non-asset page whose file name begins with {@code <folder>/}
     */
    public static PageGroup of(String folder, List<CustomPage> pagesInGroup) {
        List<CustomPage> rest = new ArrayList<>();
        CustomPage landing = null;
        for (CustomPage page : pagesInGroup) {
            String name = page.fileName().substring(folder.length() + 1);
            if (landing == null && ReservedNames.isLandingPageName(ReservedNames.withoutItsExtension(name), folder)) {
                landing = page;
            } else {
                rest.add(page);
            }
        }
        String label = landing != null && landing.title() != null ? landing.title() : NumberPrefixes.stripped(folder);
        return new PageGroup(folder, label, landing == null ? OrderHint.NONE : landing.hint(), rest,
                Optional.ofNullable(landing));
    }

    /** The name another item's {@code sidebar_after} / {@code sidebar_before} would give to reach this group. */
    public String name() {
        return NumberPrefixes.stripped(folder);
    }
}
