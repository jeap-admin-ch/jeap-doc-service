package ch.admin.bit.jeap.doc.domain;

import java.util.regex.Pattern;

/**
 * The one definition of what a slug is in the doc service.
 * <p>
 * Systems, components, libraries, sites, templates, topics and environments are all named by slugs, and they all
 * end up as path segments - of an object key, of a URL, or of both. Keeping the rule in one place is what stops
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
}
