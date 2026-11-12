package ch.admin.bit.jeap.doc.domain.custom;

import ch.admin.bit.jeap.doc.domain.template.DocumentationPaths;
import ch.admin.bit.jeap.doc.domain.template.NumberPrefixes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The order of one chapter, over everything a team put in it and the pages this template generates into it.
 * <p>
 * <b>Uploaded pages and microsites are one list.</b> A chapter can hold both, and ordering them separately
 * would put every microsite after every page however they are called - so a reader looking for
 * <i>Configuration Reference</i> would find it under C in one half of the chapter and nowhere in the other.
 * A page is ordered by its title and a microsite by its label, which is what each of them shows.
 * <p>
 * <b>The roots, in order,</b> are the generated siblings present in this environment (in the template's own
 * order), then the items with no reference ("free"), then the items whose reference is missing in this
 * environment or sits in a cycle ("orphans") - the latter is only possible for a set stored before this rule
 * existed. <b>Each root expands</b> to its before-group, itself, then its after-group, and a group member
 * expands the same way - so a chain of references nests correctly. Every group, and the free and orphan lists
 * themselves, are ordered by their own {@link OrderHint#position()} first (absent last), then by title or
 * label, then by name.
 * <p>
 * A chapter with no generated sibling, no reference and no position comes out exactly as the upload numbered
 * it: the sort is the same one {@link UploadedSet} applies, so nothing about markdown changes.
 * <p>
 * <b>A group is one more item of the chapter</b>, ordered by its landing page's hint and its label exactly as
 * a page is - but its own pages are never passed in here with it. Their order is a separate call over
 * {@link PageGroup#pages}, with no siblings and no microsites: one order per level, because a reference inside
 * a group names only that group's own pages, never a page or a sibling of the chapter around it.
 */
public final class ChapterOrder {

    private final Map<String, Integer> positions;

    private ChapterOrder(Map<String, Integer> positions) {
        this.positions = positions;
    }

    /**
     * One generated sibling present in this environment, at the position already written on its own page.
     * <p>
     * <b>The position is handed in rather than derived here.</b> {@link ch.admin.bit.jeap.doc.domain.template
     * .StructureTemplate#siblingPosition} is what a template's writer calls to put a number on the generated
     * page itself, and an uploaded item is only placed correctly next to it if this is the very same number -
     * not a second formula that happens to agree today. A chapter where a sibling is absent (a system with no
     * events, say) never offers its name here, which is what makes it unknown to a reference naming it.
     */
    public record PresentSibling(String name, int position) {
    }

    /**
     * The order of a chapter into which this template generates nothing, or for which the generated siblings
     * are not known - a static set of pages and microsites, numbered as the upload assigned it.
     * <p>
     * No groups either: a group's own order is computed by a call of its own, over its own pages alone - see
     * the class comment.
     *
     * @param pages      the uploaded pages of that chapter, assets not among them
     * @param microsites the microsites embedded in it
     */
    public static ChapterOrder of(List<CustomPage> pages, List<Microsite> microsites) {
        return of(List.of(), pages, microsites, List.of());
    }

    /**
     * The order of one chapter: the siblings this template generates into it, present in this environment and
     * everything a team uploaded beside them - its pages, its microsites and its groups.
     *
     * @param presentSiblings the siblings this template generates into the chapter that exist in this
     *                        environment, in the template's own order
     * @param pages           the pages directly in that chapter, assets and a group's own pages not among them
     * @param microsites      the microsites embedded in it
     */
    public static ChapterOrder of(List<PresentSibling> presentSiblings, List<CustomPage> pages,
                                  List<Microsite> microsites) {
        return of(presentSiblings, pages, microsites, List.of());
    }

    /**
     * The order of one chapter, groups included: a group is one item among the chapter's own, placed by its
     * landing page's hint and sorted by its label - exactly as a page is placed by its own. A group's own
     * pages are not here; they are ordered by a separate call over {@link PageGroup#pages}, because a
     * reference inside a group names only that group's own pages and nothing at the chapter's level - one
     * order per level, never a tree of them.
     *
     * @param presentSiblings the siblings this template generates into the chapter that exist in this
     *                        environment, in the template's own order
     * @param pages           the pages directly in that chapter, assets and a group's own pages not among them
     * @param microsites      the microsites embedded in it
     * @param groups          the groups of that chapter - a folder below it holding at least one page
     */
    public static ChapterOrder of(List<PresentSibling> presentSiblings, List<CustomPage> pages,
                                  List<Microsite> microsites, List<PageGroup> groups) {
        List<Item> items = new ArrayList<>();
        pages.forEach(page -> items.add(Item.of(page)));
        microsites.forEach(microsite -> items.add(Item.of(microsite)));
        groups.forEach(group -> items.add(Item.of(group)));

        Set<String> validTargets = new HashSet<>();
        presentSiblings.forEach(sibling -> validTargets.add(sibling.name()));
        items.forEach(item -> validTargets.add(item.name()));
        Set<String> cyclic = cyclicReferences(items);

        Map<String, List<Item>> beforeOf = new HashMap<>();
        Map<String, List<Item>> afterOf = new HashMap<>();
        List<Item> free = new ArrayList<>();
        List<Item> orphans = new ArrayList<>();
        for (Item item : items) {
            String reference = item.reference();
            if (reference == null) {
                free.add(item);
            } else if (cyclic.contains(item.name()) || !validTargets.contains(reference)) {
                orphans.add(item);
            } else if (item.hint().direction() == OrderHint.Direction.BEFORE) {
                beforeOf.computeIfAbsent(reference, key -> new ArrayList<>()).add(item);
            } else {
                afterOf.computeIfAbsent(reference, key -> new ArrayList<>()).add(item);
            }
        }
        free.sort(ITEM_ORDER);
        orphans.sort(ITEM_ORDER);
        beforeOf.values().forEach(group -> group.sort(ITEM_ORDER));
        afterOf.values().forEach(group -> group.sort(ITEM_ORDER));

        Walk walk = new Walk(beforeOf, afterOf);
        presentSiblings.forEach(sibling -> walk.sibling(sibling.name(), sibling.position()));
        free.forEach(walk::item);
        orphans.forEach(walk::item);

        return new ChapterOrder(walk.positions);
    }

    /** Where this page goes, numbered from one within its chapter when this template generates nothing. */
    public int positionOf(CustomPage page) {
        return positions.getOrDefault(page.fileName(), page.position());
    }

    /** Where this microsite's page goes, in the same numbering. */
    public int positionOf(Microsite microsite) {
        return positions.getOrDefault(microsite.fileName(), 1);
    }

    /** Where this group's {@code _category_.json} goes, in the same numbering. */
    public int positionOf(PageGroup group) {
        return positions.getOrDefault(group.folder(), 1);
    }

    /** Where a generated sibling goes, or nothing if this environment does not present it. */
    public int positionOf(String siblingName) {
        return positions.getOrDefault(siblingName, 0);
    }

    private static final Comparator<Item> ITEM_ORDER = Comparator
            .comparing(Item::position, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Item::sortKey, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Item::name);

    /**
     * The items whose reference, followed as far as it goes, loops back on itself - a self-reference is a
     * cycle of one. Only possible for a set stored before the validation that refuses it existed.
     */
    private static Set<String> cyclicReferences(List<Item> items) {
        Map<String, String> referenceOf = new HashMap<>();
        items.forEach(item -> referenceOf.putIfAbsent(item.name(), item.reference()));
        return namesInACycle(referenceOf);
    }

    /**
     * The names whose reference, followed as far as it goes, loops back on itself - a self-reference is a
     * cycle of one, and a reference to a name this map does not have as a key is followed no further, which
     * is what a reference to a generated sibling or to nothing at all does: neither ever has a reference of
     * its own.
     * <p>
     * <b>The one definition of a cycle.</b> This class uses it while placing the items of an already-taken-
     * over set; {@code OrderValidation} uses the exact same one to refuse a set that would introduce one,
     * before anything is stored - combining this set's own references with what is already stored.
     *
     * @param referenceOf one name to the name it names, or absent for a name with no reference of its own
     */
    public static Set<String> namesInACycle(Map<String, String> referenceOf) {
        Set<String> cyclic = new HashSet<>();
        Set<String> settled = new HashSet<>();
        for (String start : referenceOf.keySet()) {
            if (settled.contains(start)) {
                continue;
            }
            List<String> path = new ArrayList<>();
            String current = start;
            while (current != null && !settled.contains(current)) {
                int loopStart = path.indexOf(current);
                if (loopStart >= 0) {
                    cyclic.addAll(path.subList(loopStart, path.size()));
                    break;
                }
                path.add(current);
                String next = referenceOf.get(current);
                current = next != null && referenceOf.containsKey(next) ? next : null;
            }
            settled.addAll(path);
        }
        return cyclic;
    }

    /**
     * The name another item's {@code sidebar_after} / {@code sidebar_before} would give to reach a page of
     * this file name - without its extension and its leading number, the way every reference is read.
     * <p>
     * <b>Of the last segment alone.</b> A page's {@link CustomPage#fileName} carries the folder it is grouped
     * into, if any - {@code sap-interfaces/bestellungen.md} - and a reference inside that group names only
     * {@code bestellungen}, never the group's own name: a group is opaque from inside it, the same way the
     * chapter around it is.
     * <p>
     * Shared with {@code OrderValidation}, which needs the exact same name before a page is ever stored, to
     * tell whether a reference names a page this set or the stored one actually has.
     */
    public static String nameOf(String fileName) {
        String extension = "." + DocumentationPaths.MARKDOWN_EXTENSION;
        String withoutExtension = fileName.endsWith(extension)
                ? fileName.substring(0, fileName.length() - extension.length()) : fileName;
        int slash = withoutExtension.lastIndexOf('/');
        String lastSegment = slash < 0 ? withoutExtension : withoutExtension.substring(slash + 1);
        return NumberPrefixes.stripped(lastSegment);
    }

    /** Walks the tree once: a node's before-group, the node, then its after-group - recursively. */
    private static final class Walk {
        private final Map<String, List<Item>> beforeOf;
        private final Map<String, List<Item>> afterOf;
        private final Map<String, Integer> positions = new LinkedHashMap<>();
        private int base;
        private int offset;

        Walk(Map<String, List<Item>> beforeOf, Map<String, List<Item>> afterOf) {
            this.beforeOf = beforeOf;
            this.afterOf = afterOf;
        }

        void sibling(String name, int declaredPosition) {
            expandGroup(beforeOf, name);
            positions.put(name, declaredPosition);
            base = declaredPosition;
            expandGroup(afterOf, name);
        }

        void item(Item item) {
            expandGroup(beforeOf, item.name());
            offset++;
            positions.put(item.key(), base + offset);
            expandGroup(afterOf, item.name());
        }

        private void expandGroup(Map<String, List<Item>> group, String name) {
            group.getOrDefault(name, List.of()).forEach(this::item);
        }
    }

    /** One uploaded page or microsite, as the ordering needs to see it. */
    private record Item(String key, String name, String sortKey, OrderHint hint) {

        static Item of(CustomPage page) {
            return new Item(page.fileName(), nameOf(page.fileName()), sortKeyOf(page), page.hint());
        }

        static Item of(Microsite microsite) {
            return new Item(microsite.fileName(), Microsite.SEGMENT + "/" + microsite.topic(),
                    microsite.sortKey(), microsite.hint());
        }

        static Item of(PageGroup group) {
            return new Item(group.folder(), group.name(), group.label(), group.hint());
        }

        /** A page with no title is ordered by its file name, exactly as the upload ordered it. */
        private static String sortKeyOf(CustomPage page) {
            return page.title() == null ? page.fileName() : page.title();
        }

        String reference() {
            return hint == null ? null : hint.reference();
        }

        Double position() {
            return hint == null ? null : hint.position();
        }
    }
}
