package ch.admin.bit.jeap.doc.domain.custom;

/**
 * Where an uploaded page or microsite asked to go among the other items of its chapter.
 * <p>
 * Optional in every part: a page that said nothing about its place is {@link #NONE}. A page may give a
 * position, a reference, or both - a position orders it among the items that share its parent, a reference
 * places it directly before or after one named item. At most one of {@link Direction#BEFORE} and
 * {@link Direction#AFTER} applies, because a page names at most one reference.
 *
 * @param position  where this item ranks among the other items with no reference of its own, or that share
 *                  its reference - lower first, absent last; null for an item that gave none
 * @param direction whether {@code reference} is the item this one goes before or after; null with no reference
 * @param reference the name of the other item of this chapter this one places itself next to; null for none
 */
public record OrderHint(Double position, Direction direction, String reference) {

    /** A page or microsite that named no placement at all - today's order, unchanged. */
    public static final OrderHint NONE = new OrderHint(null, null, null);

    public enum Direction {
        BEFORE, AFTER
    }
}
