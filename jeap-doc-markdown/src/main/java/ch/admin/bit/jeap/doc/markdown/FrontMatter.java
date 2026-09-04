package ch.admin.bit.jeap.doc.markdown;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Docusaurus front matter of a generated page.
 * <p>
 * Every string is written as a double-quoted scalar. A title such as {@code jEAP: Documentation}, or one
 * starting with {@code #}, is a YAML error otherwise - reported minutes into a site build, against a line of a
 * generated file rather than against the value it came from.
 * <p>
 * Keys keep the order they were added in, so two generated pages diff cleanly.
 */
public final class FrontMatter {

    private final Map<String, String> values = new LinkedHashMap<>();

    public static FrontMatter frontMatter() {
        return new FrontMatter();
    }

    /**
     * A string value, quoted. A null or blank value is skipped: Docusaurus treats an absent key as not set,
     * and an empty one as a value.
     */
    public FrontMatter put(String key, String value) {
        if (value != null && !value.isBlank()) {
            values.put(requireKey(key), Scalars.quoted(value));
        }
        return this;
    }

    /** A number, unquoted. {@code sidebar_position} has to be one, or Docusaurus ignores it. */
    public FrontMatter put(String key, int value) {
        values.put(requireKey(key), Integer.toString(value));
        return this;
    }

    public FrontMatter put(String key, boolean value) {
        values.put(requireKey(key), Boolean.toString(value));
        return this;
    }

    boolean isEmpty() {
        return values.isEmpty();
    }

    /** The block, with its delimiters and its trailing newline. */
    String render() {
        if (values.isEmpty()) {
            return "";
        }
        StringBuilder block = new StringBuilder("---\n");
        values.forEach((key, value) -> block.append(key).append(": ").append(value).append('\n'));
        return block.append("---\n").toString();
    }

    private static String requireKey(String key) {
        if (key == null || !key.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException(
                    "A front matter key is lower case with underscores, and this one is not: " + key);
        }
        return key;
    }
}
