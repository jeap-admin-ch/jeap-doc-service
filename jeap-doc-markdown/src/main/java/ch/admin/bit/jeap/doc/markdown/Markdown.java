package ch.admin.bit.jeap.doc.markdown;

import java.util.Objects;

/**
 * A fragment of Markdown that has already been escaped.
 * <p>
 * The constructor is package-private, so the only way to get one is a factory on {@link Md}. Every one of those
 * escapes what it is given. Text from outside this service therefore cannot reach a page unescaped by accident.
 * <p>
 * It is a class and not a record because a record's constructor cannot be less visible than the record itself.
 * <p>
 * <b>Do not add a factory that takes text as it is.</b> That would give the escaping rule a second definition.
 */
public final class Markdown implements CharSequence {

    /** Nothing at all: an empty cell, or a paragraph that is not written. */
    public static final Markdown EMPTY = new Markdown("");

    private final String value;

    Markdown(String value) {
        this.value = Objects.requireNonNull(value, "A Markdown fragment is never null; use Markdown.EMPTY.");
    }

    public String value() {
        return value;
    }

    @Override
    public boolean isEmpty() {
        return value.isEmpty();
    }

    @Override
    public int length() {
        return value.length();
    }

    @Override
    public char charAt(int index) {
        return value.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return value.subSequence(start, end);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Markdown markdown && value.equals(markdown.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
