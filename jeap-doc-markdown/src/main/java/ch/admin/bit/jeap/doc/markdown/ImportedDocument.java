package ch.admin.bit.jeap.doc.markdown;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes a Markdown document written outside this service safe to place in the middle of a page.
 * <p>
 * Four things are done, and nothing else:
 * <ul>
 *     <li>A {@code <} outside code is written as {@code &lt;}, so no raw HTML reaches the page. Inside a code
 *     span or a fenced block it is left alone, because CommonMark does not decode an entity there and the
 *     reader would see {@code &lt;}.</li>
 *     <li>A link or an image that does not point to {@code http}, {@code https}, {@code mailto} or an anchor
 *     is replaced by its text. A relative target means nothing on this site, and the site build fails on a
 *     broken link.</li>
 *     <li>A fence or a container directive the document opens and does not close is closed at its end, and a
 *     closing marker it never opened is escaped. Otherwise it would swallow, or end, what the page writes
 *     after it.</li>
 *     <li>A heading is moved one level down, so that the page keeps the one top-level heading it writes itself.
 *     Only ATX headings are moved: a Setext heading keeps its level.</li>
 * </ul>
 * Links and code spans are found in a paragraph as a whole, because both may continue on the next line. A
 * character counts as code only if it is code both in its paragraph and in its line, so a backtick in a heading
 * cannot hide what follows it. Indented code blocks are not recognised. In one, a {@code <} shows as
 * {@code &lt;}; that is the safe side.
 */
final class ImportedDocument {

    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    private static final Pattern CONTAINER_OPENING = Pattern.compile("^ {0,3}(:{3,})\\s*[A-Za-z].*$");
    private static final Pattern ATX_HEADING = Pattern.compile("^( {0,3})(#{1,5})(?=[ \\t]|$)");
    private static final Pattern CONTAINER_CLOSING = Pattern.compile("^( {0,3})(:{3,})\\s*$");
    /** A reference definition. Its target may be on the next line. */
    private static final Pattern LINK_DEFINITION = Pattern.compile(
            "^ {0,3}\\[((?:[^\\]\\\\]|\\\\.)+)\\]:[ \\t]*(?:\\n[ \\t]*)?<?([^\\s>]*)", Pattern.MULTILINE);
    private static final Pattern AUTOLINK = Pattern.compile("<(?i:https?|mailto):[^\\s<>]*>");

    private ImportedDocument() {
    }

    static String contained(String markdown) {
        String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder out = new StringBuilder(markdown.length() + 16);
        List<String> paragraph = new ArrayList<>();
        Deque<Integer> containers = new ArrayDeque<>();
        String openFence = null;
        for (String line : lines) {
            if (openFence != null) {
                out.append(line).append('\n');
                if (closesFence(line, openFence)) {
                    openFence = null;
                }
                continue;
            }
            Matcher fence = FENCE.matcher(line);
            if (fence.matches() && !(fence.group(1).charAt(0) == '`' && fence.group(2).contains("`"))) {
                flush(paragraph, out);
                openFence = fence.group(1);
                out.append(line).append('\n');
                continue;
            }
            Matcher closing = CONTAINER_CLOSING.matcher(line);
            if (closing.matches()) {
                flush(paragraph, out);
                int length = closing.group(2).length();
                if (!containers.isEmpty() && length >= containers.peek()) {
                    containers.pop();
                    out.append(line);
                } else {
                    out.append(closing.group(1)).append('\\').append(line.stripLeading());
                }
                out.append('\n');
                continue;
            }
            Matcher opening = CONTAINER_OPENING.matcher(line);
            if (opening.matches()) {
                flush(paragraph, out);
                containers.push(opening.group(1).length());
                out.append(paragraph(line)).append('\n');
                continue;
            }
            if (line.isBlank()) {
                flush(paragraph, out);
                out.append(line).append('\n');
                continue;
            }
            paragraph.add(ATX_HEADING.matcher(line).replaceFirst("$1#$2"));
        }
        flush(paragraph, out);
        if (openFence != null) {
            out.append(openFence).append('\n');
        }
        while (!containers.isEmpty()) {
            out.append(":".repeat(containers.pop())).append('\n');
        }
        return out.toString().strip();
    }

    private static void flush(List<String> paragraph, StringBuilder out) {
        if (!paragraph.isEmpty()) {
            out.append(paragraph(String.join("\n", paragraph))).append('\n');
            paragraph.clear();
        }
    }

    private static boolean closesFence(String line, String openFence) {
        String trimmed = line.strip();
        char marker = openFence.charAt(0);
        if (trimmed.length() < openFence.length() || line.length() - line.stripLeading().length() > 3) {
            return false;
        }
        return trimmed.chars().allMatch(c -> c == marker);
    }

    private static String paragraph(String text) {
        String linked = withoutUnsafeLinks(withoutUnsafeDefinitions(text));
        boolean[] code = code(linked);
        StringBuilder out = new StringBuilder(linked.length() + 8);
        int i = 0;
        while (i < linked.length()) {
            char c = linked.charAt(i);
            if (c == '<' && !code[i]) {
                Matcher autolink = AUTOLINK.matcher(linked).region(i, linked.length());
                if (autolink.lookingAt()) {
                    out.append(autolink.group());
                    i = autolink.end();
                    continue;
                }
                out.append("&lt;");
            } else {
                out.append(c);
            }
            i++;
        }
        return out.toString();
    }

    /** A reference definition with a target that cannot be a link is shown as the text it is. */
    private static String withoutUnsafeDefinitions(String text) {
        Matcher definition = LINK_DEFINITION.matcher(text);
        StringBuilder out = new StringBuilder(text.length() + 4);
        int last = 0;
        while (definition.find()) {
            // A label starting with a caret is a footnote, not a link.
            if (definition.group(1).startsWith("^") || isSafeTarget(definition.group(2))) {
                continue;
            }
            int bracket = text.indexOf('[', definition.start());
            out.append(text, last, bracket).append('\\');
            last = bracket;
        }
        return out.append(text.substring(last)).toString();
    }

    /** An inline link or image whose target cannot be a link is replaced by its text. */
    private static String withoutUnsafeLinks(String text) {
        boolean[] code = code(text);
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (code[i]) {
                out.append(c);
                i++;
                continue;
            }
            if (c == '\\' && i + 1 < text.length()) {
                out.append(c).append(text.charAt(i + 1));
                i += 2;
                continue;
            }
            int open = c == '!' && i + 1 < text.length() && text.charAt(i + 1) == '[' ? i + 1 : i;
            InlineLink link = text.charAt(open) == '[' ? InlineLink.at(text, code, open) : null;
            if (link == null) {
                out.append(c);
                i++;
                continue;
            }
            String label = withoutUnsafeLinks(text.substring(open + 1, link.closingBracket()));
            if (isSafeTarget(link.target())) {
                out.append(text, i, open + 1).append(label).append(text, link.closingBracket(), link.end());
            } else {
                out.append(label);
            }
            i = link.end();
        }
        return out.toString();
    }

    /**
     * Where an inline link starting at a bracket ends, read as CommonMark reads it: brackets in its text and
     * parentheses in its target nest, and either may continue on the next line.
     */
    private record InlineLink(int closingBracket, String target, int end) {

        static InlineLink at(String text, boolean[] code, int open) {
            int close = closingBracket(text, code, open);
            if (close < 0 || close + 1 >= text.length() || text.charAt(close + 1) != '(') {
                return null;
            }
            int i = skipSpace(text, close + 2);
            int targetStart;
            int targetEnd;
            if (i < text.length() && text.charAt(i) == '<') {
                targetStart = i + 1;
                targetEnd = angleTargetEnd(text, targetStart);
                if (targetEnd < 0) {
                    return null;
                }
                i = targetEnd + 1;
            } else {
                targetStart = i;
                targetEnd = bareTargetEnd(text, i);
                if (targetEnd < 0) {
                    return null;
                }
                i = targetEnd;
            }
            int afterTarget = skipSpace(text, i);
            if (afterTarget > i && afterTarget < text.length() && "\"'(".indexOf(text.charAt(afterTarget)) >= 0) {
                int titleEnd = titleEnd(text, afterTarget);
                if (titleEnd < 0) {
                    return null;
                }
                afterTarget = skipSpace(text, titleEnd);
            }
            if (afterTarget >= text.length() || text.charAt(afterTarget) != ')') {
                return null;
            }
            return new InlineLink(close, text.substring(targetStart, targetEnd), afterTarget + 1);
        }

        private static int closingBracket(String text, boolean[] code, int open) {
            int depth = 0;
            int i = open;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (code[i]) {
                    i++;
                    continue;
                }
                if (c == '\\') {
                    i += 2;
                    continue;
                }
                if (c == '[') {
                    depth++;
                } else if (c == ']' && --depth == 0) {
                    return i;
                }
                i++;
            }
            return -1;
        }

        private static int angleTargetEnd(String text, int from) {
            int i = from;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == '\\') {
                    i += 2;
                    continue;
                }
                if (c == '>') {
                    return i;
                }
                if (c == '<' || c == '\n') {
                    return -1;
                }
                i++;
            }
            return -1;
        }

        private static int bareTargetEnd(String text, int from) {
            int depth = 0;
            int i = from;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == '\\') {
                    i += 2;
                    continue;
                }
                if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                    break;
                }
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    if (depth == 0) {
                        break;
                    }
                    depth--;
                }
                i++;
            }
            return depth == 0 ? Math.min(i, text.length()) : -1;
        }

        private static int titleEnd(String text, int open) {
            char closer = text.charAt(open) == '(' ? ')' : text.charAt(open);
            int i = open + 1;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == '\\') {
                    i += 2;
                    continue;
                }
                if (c == closer) {
                    return i + 1;
                }
                i++;
            }
            return -1;
        }

        /** Spaces and tabs, and at most one line break. */
        private static int skipSpace(String text, int from) {
            int i = from;
            boolean lineBreak = false;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == '\n' && !lineBreak) {
                    lineBreak = true;
                } else if (c != ' ' && c != '\t') {
                    break;
                }
                i++;
            }
            return i;
        }
    }

    /** Which characters are code: inside a code span of the paragraph and of their own line alike. */
    private static boolean[] code(String text) {
        boolean[] code = codeSpans(text);
        int start = 0;
        while (start < text.length()) {
            int end = text.indexOf('\n', start);
            if (end < 0) {
                end = text.length();
            }
            boolean[] line = codeSpans(text.substring(start, end));
            for (int j = start; j < end; j++) {
                code[j] &= line[j - start];
            }
            start = end + 1;
        }
        return code;
    }

    /** Which characters are inside a code span, the backticks included. */
    private static boolean[] codeSpans(String text) {
        boolean[] code = new boolean[text.length()];
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) == '\\' && i + 1 < text.length()) {
                i += 2;
                continue;
            }
            if (text.charAt(i) != '`') {
                i++;
                continue;
            }
            int run = runOfBackticks(text, i);
            int closing = closingRun(text, i + run, run);
            if (closing < 0) {
                i += run;
                continue;
            }
            for (int j = i; j < closing + run; j++) {
                code[j] = true;
            }
            i = closing + run;
        }
        return code;
    }

    private static int closingRun(String text, int from, int length) {
        int i = from;
        while (i < text.length()) {
            if (text.charAt(i) != '`') {
                i++;
                continue;
            }
            int run = runOfBackticks(text, i);
            if (run == length) {
                return i;
            }
            i += run;
        }
        return -1;
    }

    private static int runOfBackticks(String text, int at) {
        int run = 0;
        while (at + run < text.length() && text.charAt(at + run) == '`') {
            run++;
        }
        return run;
    }

    private static boolean isSafeTarget(String target) {
        String lower = target.strip().toLowerCase(Locale.ROOT);
        return (lower.startsWith("#") && lower.length() > 1)
               || lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:");
    }
}
