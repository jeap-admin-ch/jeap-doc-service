package ch.admin.bit.jeap.doc.markdown;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Where a {@link Markdown} fragment comes from: the inline vocabulary of a generated page.
 * <p>
 * Every factory here escapes what it is given. Pages are written from data this service did not author, such as
 * system descriptions and message topics, and any of it can contain a character that means something in
 * Markdown. Escaping in one place beats remembering to escape at a few hundred call sites.
 *
 * @see Markdown
 */
public final class Md {

    /**
     * The characters that would change the structure of a line, and nothing else.
     * <p>
     * Pages are read as CommonMark, so {@code <} opens raw HTML and {@code &} opens a character reference. The
     * rest open a heading, a link or a code span. Escaping every character Markdown gives a meaning to would
     * fill the pages with backslashes.
     * <p>
     * A pipe is missing on purpose. It only means something inside a table row, so {@link MarkdownWriter}
     * escapes it when a fragment becomes a cell. Escaping it twice would put a backslash on the page.
     */
    private static final String ESCAPED = "\\`*_[]#<>~";

    private Md() {
    }

    /**
     * Plain text, escaped. A null or blank value becomes {@link Markdown#EMPTY}.
     * <p>
     * Leading whitespace is dropped. Four columns of it open an indented code block, and a tab counts to the
     * next tab stop - so {@code " \t"} is four columns just as {@code "    "} is, and counting characters
     * rather than columns leaves the hole open. Nothing that arrives from outside means to indent.
     * <p>
     * Only <b>leading</b>: a fragment is often written as {@code ": "} between two others, where the trailing
     * space is the point, and that one is untouched.
     */
    public static Markdown text(String value) {
        if (value == null || value.isBlank()) {
            return Markdown.EMPTY;
        }
        return escaped(value.stripLeading());
    }

    /**
     * The same escaping, but a blank value stays blank.
     * <p>
     * {@link #text} turns a blank value into {@link Markdown#EMPTY}, because a blank cell means the value is
     * missing. A single space between two fragments means a space, so {@link #sentence} and {@link #joinWith}
     * use this instead.
     */
    private static Markdown escaped(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ':' && opensADirective(value, i)) {
                // ::: at the start of a line opens a container directive - an admonition - and an imported
                // description beginning with one would swallow the rest of the page into it. This module
                // writes those itself; it does not accept them from outside.
                escaped.append("\\:");
            } else if (c == '&') {
                escaped.append("&amp;");
            } else if (ESCAPED.indexOf(c) >= 0) {
                escaped.append('\\').append(c);
            } else if (c == '\r' || c == '\n') {
                // A line break becomes a space. Dropped outright it would join two words; kept, a blank line in
                // an imported description would start a new block - a list, a table, an admonition - inside a
                // paragraph this writer meant as one. The escaping above covers the characters, this covers
                // the structure.
                appendSpaceUnlessTrailing(escaped);
            } else {
                escaped.append(c);
            }
        }
        return new Markdown(escaped.toString());
    }

    /**
     * Whether a colon starts a run of three or more, which is what a container directive is spelled with.
     * Only the first of the run is escaped; the two after it are then no longer at the start of anything.
     */
    private static boolean opensADirective(String value, int at) {
        // startsWith carries the bound: it is false as soon as there are fewer than three characters left.
        return (at == 0 || value.charAt(at - 1) != ':') && value.startsWith(":::", at);
    }

    /** One space for a run of line breaks, rather than one per break. */
    private static void appendSpaceUnlessTrailing(StringBuilder escaped) {
        if (!escaped.isEmpty() && escaped.charAt(escaped.length() - 1) != ' ') {
            escaped.append(' ');
        }
    }

    /** Text, or the replacement when there is none. A blank cell reads as a defect; <i>unknown</i> does not. */
    public static Markdown textOr(String value, String fallback) {
        Markdown markdown = text(value);
        return markdown.isEmpty() ? text(fallback) : markdown;
    }

    /**
     * An identifier, a path or a property name: anything written elsewhere exactly like this.
     * <p>
     * A backslash means nothing inside a code span, so a value containing backticks is wrapped in a longer run
     * of them instead. That is what CommonMark prescribes.
     */
    public static Markdown code(String value) {
        if (value == null || value.isBlank()) {
            return Markdown.EMPTY;
        }
        String inline = value.replace('\n', ' ').replace('\r', ' ');
        int longestRun = 0;
        int run = 0;
        for (int i = 0; i < inline.length(); i++) {
            run = inline.charAt(i) == '`' ? run + 1 : 0;
            longestRun = Math.max(longestRun, run);
        }
        String fence = "`".repeat(longestRun + 1);
        // A code span that starts or ends with a backtick needs a space of padding, which CommonMark strips.
        String padding = inline.startsWith("`") || inline.endsWith("`") ? " " : "";
        return new Markdown(fence + padding + inline + padding + fence);
    }

    public static Markdown bold(String value) {
        return bold(text(value));
    }

    public static Markdown bold(Markdown value) {
        return value.isEmpty() ? Markdown.EMPTY : new Markdown("**" + value.value() + "**");
    }

    public static Markdown italic(String value) {
        Markdown escaped = text(value);
        return escaped.isEmpty() ? Markdown.EMPTY : new Markdown("*" + escaped.value() + "*");
    }

    /**
     * A link into the documentation or out of it.
     * <p>
     * A target is not escaped the way text is, because a backslash would become part of the address. It is
     * checked instead. Use {@link #linkOrCode} when the target comes from outside this service.
     */
    public static Markdown link(String target, String label) {
        return link(target, text(label));
    }

    public static Markdown link(String target, Markdown label) {
        if (target == null || target.isBlank()) {
            return label;
        }
        String url = target.strip();
        if (!isLinkable(url)) {
            throw new IllegalArgumentException(
                    "This is not a link target a page may carry: " + target + ". A target is relative, an "
                    + "anchor, http(s) or mailto, and carries no spaces, parentheses or angle brackets. Use "
                    + "linkOrCode when the value comes from outside this service.");
        }
        // The target as the label, escaped: it is the one place in this module where a string that came from
        // outside would otherwise become Markdown without passing through the escaping, and a "]" in it ends
        // the link text early.
        Markdown text = label.isEmpty() ? text(url) : label;
        return new Markdown("[" + text.value() + "](" + url + ")");
    }

    /**
     * A link when the target can be one, and the name beside the unusable target when it cannot.
     * <p>
     * A descriptor URL or a contact address is free text somebody typed into the architecture repository. One
     * bad value must not fail the run and take the documentation of every other system with it.
     * <p>
     * <b>The label survives either way.</b> What a reader wants from the cell is the name - the schema's file,
     * the team - and answering a bad URL with the URL alone takes that off the page, which is a second loss on
     * top of the link. The unusable target is still shown, as code, because it is what somebody has to correct.
     */
    public static Markdown linkOrCode(String target, String label) {
        if (target == null || target.isBlank()) {
            return Markdown.EMPTY;
        }
        if (isExternalTarget(target.strip())) {
            return link(target, label);
        }
        Markdown name = text(label);
        // joinWith and not join: text(" ") is blank and would be dropped, and the space is what separates the
        // name from the address.
        return name.isEmpty() ? code(target) : joinWith(" ", List.of(name, code(target)));
    }

    /**
     * Whether a target may be put in a link.
     * <p>
     * It may not contain a character that would end the link early, and its scheme has to be one a reader can
     * follow. A {@code javascript:} target would run code in the browser of everyone reading the page.
     * <p>
     * This is the rule for the targets <b>this service builds itself</b>, which is why a relative path and an
     * anchor pass it. A target that came from outside goes through {@link #isExternalTarget} instead.
     */
    private static boolean isLinkable(String url) {
        if (!isWellFormedTarget(url)) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        return url.startsWith("/") || url.startsWith("#") || url.startsWith("./") || url.startsWith("../")
               || lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:");
    }

    /**
     * Whether a target that came from outside this service may be put in a link.
     * <p>
     * It has to name where it points: {@code http}, {@code https} or {@code mailto}, and nothing else. A
     * relative target is not a link somebody typed into the architecture repository, it is a path <i>into this
     * site</i> - Docusaurus resolves it against the page it stands on and then fails the build over it, because
     * {@code onBrokenLinks: 'throw'}. One unusable value in one system's model would take the whole
     * environment's site down, which is the outcome {@link #linkOrCode} exists to prevent.
     * <p>
     * {@code //host/path} is refused for the same reason it looks harmless: it starts with a slash but leaves
     * the site, so treating it as internal would be a link to somewhere else entirely.
     */
    private static boolean isExternalTarget(String url) {
        if (!isWellFormedTarget(url)) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:");
    }

    /** Whether the target survives being written between the brackets of a link at all. */
    private static boolean isWellFormedTarget(String url) {
        // Every ASCII control character and the space, not only the two line breaks: a link destination may
        // carry none of them, so a tab in one produces something that is not a link at all - which ships as
        // literal brackets on the page, where onBrokenLinks never sees it.
        for (int i = 0; i < url.length(); i++) {
            if (url.charAt(i) <= ' ' || url.charAt(i) == 0x7f) {
                return false;
            }
        }
        return url.indexOf(')') < 0 && url.indexOf('(') < 0
               && url.indexOf('<') < 0 && url.indexOf('>') < 0;
    }

    /** Puts fragments one after another, with nothing between them. */
    public static Markdown join(Markdown... parts) {
        return new Markdown(Arrays.stream(parts).map(Markdown::value).collect(Collectors.joining()));
    }

    /** Puts fragments one after another with a separator between them, skipping the empty ones. */
    public static Markdown joinWith(String separator, List<Markdown> parts) {
        List<String> present = new ArrayList<>();
        for (Markdown part : parts) {
            if (!part.isEmpty()) {
                present.add(part.value());
            }
        }
        return new Markdown(String.join(escaped(separator).value(), present));
    }

    /**
     * A sentence with fragments in it. Every {@code {}} is replaced by the next argument; the text around them
     * is escaped. It keeps a sentence readable in the code that writes it.
     */
    public static Markdown sentence(String pattern, Markdown... arguments) {
        StringBuilder built = new StringBuilder();
        int argument = 0;
        int from = 0;
        int at;
        while ((at = pattern.indexOf("{}", from)) >= 0) {
            built.append(escaped(pattern.substring(from, at)).value());
            if (argument >= arguments.length) {
                throw new IllegalArgumentException(
                        "The sentence pattern has more placeholders than arguments: " + pattern);
            }
            built.append(arguments[argument++].value());
            from = at + 2;
        }
        built.append(escaped(pattern.substring(from)).value());
        if (argument != arguments.length) {
            throw new IllegalArgumentException(
                    "The sentence pattern has fewer placeholders than arguments: " + pattern);
        }
        return new Markdown(built.toString());
    }
}
