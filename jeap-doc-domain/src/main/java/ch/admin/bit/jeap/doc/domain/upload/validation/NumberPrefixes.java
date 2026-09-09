package ch.admin.bit.jeap.doc.domain.upload.validation;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The number prefix the site generator takes off a document's name before it becomes a URL.
 * <p>
 * <b>A file name is not the name of the page it becomes.</b> Docusaurus parses a leading number off a
 * document's file name and uses what is left as the document's id, its slug and therefore its route, so
 * {@code 01-rest-api.md} is published at {@code rest-api} - the very route the generated page of that chapter
 * has. Comparing the file name against what is reserved would accept the upload and hand the build two
 * documents at one URL, which is what fails it.
 * <p>
 * <b>Ported from the generator rather than invented</b>, so that the two answers cannot drift: this is
 * {@code DefaultNumberPrefixParser} of {@code @docusaurus/plugin-content-docs}, which the site template leaves
 * in place - it configures no {@code numberPrefixParser}, and {@code parse_number_prefixes} defaults to true.
 * The front matter that could switch it off per document is not something an upload may carry: the allowlist
 * is {@code title} and {@code description}.
 * <p>
 * The generator ignores what looks like a date or a version ({@code 2021-11-foo}, {@code 7.0-foo}) rather than
 * reading it as a prefix, and so does this.
 */
final class NumberPrefixes {

    /** What is left alone: a second number after the separator reads as a date or a version, not a prefix. */
    private static final Pattern IGNORED = Pattern.compile("^\\d+[-_.]\\d+");

    /** A leading number, its separators, and the name that follows it. */
    private static final Pattern PREFIX = Pattern.compile("^(\\d+)\\s*[-_.]+\\s*([^-_.\\s].*)$");

    private NumberPrefixes() {
    }

    /**
     * The document name the generator would derive from this one.
     *
     * @param name a file name with its extension already taken off
     */
    static String stripped(String name) {
        if (IGNORED.matcher(name).find()) {
            return name;
        }
        Matcher match = PREFIX.matcher(name);
        return match.matches() ? match.group(2) : name;
    }
}
