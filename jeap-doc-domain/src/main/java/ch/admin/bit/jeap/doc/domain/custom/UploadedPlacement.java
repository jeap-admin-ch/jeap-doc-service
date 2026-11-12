package ch.admin.bit.jeap.doc.domain.custom;

/**
 * Where a page literally named a placement, before anything decided what to make of it.
 * <p>
 * {@link UploadedTitles#hintOf} turns this into the {@link OrderHint} every reader of a stored page sees,
 * reading a value it cannot make sense of as none at all - right once a set is taken over, wrong at the
 * upload that is still deciding whether to take it over, where a page that named something unreadable has
 * to fail rather than be read as if it said nothing. The upload validation reads this instead of
 * {@code OrderHint}, so the two can each ask the question that is theirs.
 *
 * @param position the raw value of {@code sidebar_position}, or null if the key is absent
 * @param after    the raw value of {@code sidebar_after}, or null if the key is absent
 * @param before   the raw value of {@code sidebar_before}, or null if the key is absent
 */
public record UploadedPlacement(Object position, Object after, Object before) {

    /** Whether {@code sidebar_position} is there and is not a finite number. */
    public boolean positionIsInvalid() {
        return position != null && UploadedFrontMatter.finiteNumberOf(position) == null;
    }

    /** Whether {@code sidebar_after} is there and is not a single, non-blank scalar. */
    public boolean afterIsInvalid() {
        return after != null && UploadedTitles.scalarOf(after) == null;
    }

    /** Whether {@code sidebar_before} is there and is not a single, non-blank scalar. */
    public boolean beforeIsInvalid() {
        return before != null && UploadedTitles.scalarOf(before) == null;
    }

    /** Whether the page named both directions, which cannot both hold. */
    public boolean namesBothDirections() {
        return after != null && before != null;
    }

    /** Whether the page named a reference at all, whichever direction and whatever it is worth. */
    public boolean namesAReference() {
        return after != null || before != null;
    }
}
