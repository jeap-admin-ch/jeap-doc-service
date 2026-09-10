package ch.admin.bit.jeap.doc.domain.template;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The node ids of a reaction graph, and the prefix that keeps two graphs on one page apart.
 * <p>
 * <b>A node id is an address, not a detail.</b> The site's diagram plugin resolves
 * {@code #graph?highlight-node=…} by SVG id, so a page that draws a reaction graph is also offering every node
 * of it as a target - and the page that writes the link is a different page, written by a different pass of the
 * generator, which holds one system's graphs at a time.
 * <p>
 * That is why the prefix is <b>derived from the variant</b> rather than counted. A message type with variants
 * draws a diagram per variant on one page, and the same reaction is regularly in several of them: without a
 * prefix the browser gets one id twice and a deep link lands on whichever it finds first. An ordinal would
 * keep them apart just as well, but only the pass that writes that page could know it - so nothing else could
 * ever address a node on it. A slug of the variant is a function of what a linking node already carries.
 * <p>
 * <b>Two variants can slug alike</b> ({@code a_b} and {@code a-b}), and a reader could not tell those apart
 * either. The first fence to claim a prefix keeps it and the next takes an ordinal infix, so a link goes to
 * the first - the corner case costs a wrong fence, never a wrong page.
 */
public final class ReactionIds {

    /** What a message node's id starts with, after the prefix. */
    private static final String MESSAGE = "MESSAGE-";

    /** And a reaction node's. */
    private static final String REACTION = "REACTION-";

    private ReactionIds() {
    }

    /**
     * The prefix of the graph of one variant, {@code ""} for a message type that has none.
     * <p>
     * Folded and reduced to what a DOM id carries safely: a variant is an upstream string that may contain
     * anything, and this ends up in the page.
     */
    public static String prefixOf(String variant) {
        String slug = slug(variant);
        return slug.isEmpty() ? "" : slug + "-";
    }

    /**
     * The prefixes of one page's graphs, in the order they are drawn, each unique on the page.
     * <p>
     * The first claim on a prefix keeps it, and a later one that would repeat it counts up until it is free.
     * A link written elsewhere addresses {@link #prefixOf} and so always reaches the first.
     */
    public static List<String> prefixesOf(List<String> variants) {
        Set<String> claimed = new LinkedHashSet<>();
        List<String> prefixes = new ArrayList<>(variants.size());
        for (String variant : variants) {
            String wanted = prefixOf(variant);
            String prefix = wanted;
            for (int ordinal = 2; !claimed.add(prefix); ordinal++) {
                prefix = wanted.isEmpty() ? ordinal + "-" : slug(variant) + "-" + ordinal + "-";
            }
            prefixes.add(prefix);
        }
        return List.copyOf(prefixes);
    }

    /** The id of a message node, which is what a link from another graph addresses. */
    public static String messageId(String prefix, long message) {
        return (prefix == null ? "" : prefix) + MESSAGE + message;
    }

    /** The id of a reaction node, addressed by a link from the graph of a system or a message. */
    public static String reactionId(String prefix, long reaction) {
        return (prefix == null ? "" : prefix) + REACTION + reaction;
    }

    /** Lower case, everything that is not a letter or a digit a single dash, and no dash at either end. */
    private static String slug(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder slug = new StringBuilder(value.length());
        for (char character : value.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(character) && character < 128) {
                slug.append(character);
            } else if (!slug.isEmpty() && slug.charAt(slug.length() - 1) != '-') {
                slug.append('-');
            }
        }
        while (!slug.isEmpty() && slug.charAt(slug.length() - 1) == '-') {
            slug.setLength(slug.length() - 1);
        }
        return slug.toString();
    }
}
