package ch.admin.bit.jeap.doc.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The one definition of what a slug is in the doc service.
 * <p>
 * Systems, components, messages, libraries, sites, templates, topics and environments are all named by slugs,
 * and they all end up as path segments - of an object key, of a URL, or of both. Keeping the rule in one place is what stops
 * the answer from drifting between the API and the configuration.
 */
public final class Slugs {

    // Possessive quantifiers: the values come from outside, and a nested repetition that can backtrack would let
    // a long value overflow the stack of the regex engine.
    private static final Pattern SLUG = Pattern.compile("[a-z0-9]++(?:-[a-z0-9]++)*+");

    /**
     * What a slug may look like, for a message that has to tell someone what to change.
     */
    public static final String DESCRIPTION = "lower case letters, digits and single hyphens";

    private Slugs() {
    }

    /**
     * Whether the given value is a slug: lower case letters, digits and single hyphens between them.
     */
    public static boolean isSlug(String value) {
        return value != null && SLUG.matcher(value).matches();
    }

    /** Everything that may not appear in a slug, in runs, so that each run becomes one hyphen. */
    private static final Pattern NOT_SLUG_CHARACTERS = Pattern.compile("[^a-z0-9]++");

    /** The marks left behind by decomposing an accented letter. */
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}++");

    private static final Pattern LEADING_OR_TRAILING_HYPHENS = Pattern.compile("(?:^-+)|(?:-+$)");

    /**
     * A name with its accents taken off: {@code Zürich} becomes {@code Zurich}.
     * <p>
     * Decomposing the name and then dropping the marks keeps the letter that carries the accent. Without it
     * every accented letter is a character no path segment may hold, and a name written in one of the three
     * national languages loses a letter to a hyphen - or yields nothing at all.
     * <p>
     * Public because a slug is not always built by {@link #toSlug}: a message type name is split into words
     * first, and it has to fold the same way or the two disagree over the same letters.
     */
    public static String withoutAccents(String name) {
        return COMBINING_MARKS.matcher(Normalizer.normalize(name, Normalizer.Form.NFKD)).replaceAll("");
    }

    /**
     * The slug of a name that comes from outside: what it is addressed as, once it has been made into a path
     * segment.
     * <p>
     * Names in the architecture repository are written as people write them, and every path segment here is a
     * slug. Deriving one rather than refusing the name is deliberate: a system whose name is not already a
     * slug still has to be documented, and the derived slug is one an upload may name, because the upload API
     * checks that the value it is given is a slug and not how the architecture repository spells the name.
     * <p>
     * {@code ORDERS} becomes {@code orders}, {@code Order Fulfilment} becomes {@code order-fulfilment}, and
     * {@code orders-payment-scs} is itself.
     *
     * @throws IllegalArgumentException if the name yields no slug at all, which takes a name made of nothing
     *                                  but punctuation
     */
    public static String toSlug(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A name that is null or blank has no slug.");
        }
        String folded = withoutAccents(name);
        String hyphenated = NOT_SLUG_CHARACTERS.matcher(folded.toLowerCase(Locale.ROOT)).replaceAll("-");
        String slug = LEADING_OR_TRAILING_HYPHENS.matcher(hyphenated).replaceAll("");
        if (slug.isEmpty()) {
            throw new IllegalArgumentException(
                    "The name '%s' has no slug: it carries no letter and no digit.".formatted(name));
        }
        return slug;
    }

    /**
     * The slug of a message type name: the name kebab-cased, word by word.
     * <p>
     * {@code OrdersCheckErpAvailabilityV2Command} becomes {@code orders-check-erp-availability-v2-command}.
     * A message type name is camel-cased, so it is split into its words before it is folded - {@link #toSlug}
     * would run the words together into one segment nobody can read. Every other path segment is a slug, and
     * one mixed-case URL among forty lower-case ones is a URL people mistype. The exact name stays as the
     * page title.
     * <p>
     * Like {@link #toSlug}, this derives a segment rather than refusing the name, and refuses only a name that
     * yields nothing: one made of nothing but punctuation, or written in a script that has no lower-case ASCII
     * to become. The importer turns that into an abandoned run naming the message, so that nothing is ever
     * quietly left out.
     *
     * @throws IllegalArgumentException if the name yields no slug at all
     */
    public static String toMessageSlug(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A name that is null or blank has no slug.");
        }
        // Folded the way every other name from outside is folded, before the words are found: an accented
        // letter keeps its letter instead of becoming a separator, and 'ZuerichEvent' and 'ZürichEvent' do not
        // end up one documented and the other refused.
        char[] folded = withoutAccents(name).toCharArray();
        StringBuilder slug = new StringBuilder(folded.length + 8);
        for (int i = 0; i < folded.length; i++) {
            char c = folded[i];
            if (!Character.isLetterOrDigit(c)) {
                appendSeparator(slug);
                continue;
            }
            if (startsAWord(folded, i)) {
                appendSeparator(slug);
            }
            slug.append(Character.toLowerCase(c));
        }
        String kebab = LEADING_OR_TRAILING_HYPHENS.matcher(slug).replaceAll("");
        // Checked rather than assumed: everything above builds a slug out of letters, digits and single
        // hyphens, and this is what says so to the next person who changes it.
        if (!isSlug(kebab)) {
            throw new IllegalArgumentException(
                    "The name '%s' has no slug: it carries no letter and no digit.".formatted(name));
        }
        return kebab;
    }

    /**
     * Where a word starts in a camel-cased name.
     * <p>
     * An upper-case letter starts one, except inside an acronym: {@code ERP} is one word. A digit after a
     * lower-case letter starts one too, so {@code V2} reads as {@code v2}.
     */
    private static boolean startsAWord(char[] name, int at) {
        if (at == 0) {
            return false;
        }
        char current = name[at];
        char previous = name[at - 1];
        if (Character.isDigit(current)) {
            return Character.isLetter(previous) && !Character.isUpperCase(previous);
        }
        if (!Character.isUpperCase(current)) {
            return false;
        }
        if (!Character.isUpperCase(previous)) {
            return true;
        }
        // The last letter of an acronym belongs to the word that follows it: ...ErpR|eferability.
        return at + 1 < name.length && Character.isLowerCase(name[at + 1]);
    }

    private static void appendSeparator(StringBuilder slug) {
        if (!slug.isEmpty() && slug.charAt(slug.length() - 1) != '-') {
            slug.append('-');
        }
    }
}
