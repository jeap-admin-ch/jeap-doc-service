package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.SitePart;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Turns the links that leave a part into unchecked absolute ones.
 * <p>
 * A site is built one part at a time, and Docusaurus checks every link against the routes of <b>its own</b>
 * build - with {@code onBrokenLinks: 'throw'}, which is what catches a generator bug. A link into another part
 * is a route this build has never heard of, so it has to leave the check: Docusaurus' {@code pathname://}
 * protocol renders a plain anchor, which is what the environment switcher has always used and for the same
 * reason.
 * <p>
 * <b>Done once over the written content rather than at every link.</b> There are some thirty places that write
 * a documentation link, and a rule applied at each of them is a rule the next page can forget. Here it holds
 * for every page that exists, and what is left inside the build's own check is exactly what the build can
 * check. What replaces the check across parts is the generator's own route registry.
 * <p>
 * <b>Line by line, and not over the whole file.</b> A page is not all Markdown: a diagram is a fenced block
 * whose content is another language, whose links are written absolute already, and whose {@code [[...]]} is
 * not a Markdown link at all. Rewriting inside a fence would corrupt a diagram, so the pass tracks fences and
 * skips them - which is also what stops an example in an uploaded document from being rewritten. What is
 * <i>not</i> tracked is an indented code block: four spaces mean a code block only outside a list, and every
 * generated page indents inside lists, so treating them as code would leave a real cross-part link unrewritten
 * and fail the build. A fenced block is the form a page writes an example in.
 */
@Slf4j
final class CrossPartLinks {

    /**
     * A Markdown link to a path of the site, in the forms a destination is written in:
     * {@code ](/systems/orders/)}, {@code ](</systems/orders/>)} and {@code ](/systems/orders/ "Orders")}.
     * Only root-relative ones - a relative link cannot leave the page's own directory tree far enough to leave
     * the part, and an absolute URL is already outside the check.
     * <p>
     * Group 1 is what precedes the destination, group 2 or 3 is the destination, group 4 is the title and the
     * closing bracket. A title containing a {@code )} is beyond this, and beyond what any page writes.
     * <p>
     * An image carries the same {@code ](} and is matched here too - {@link #isImage} is what tells the two
     * apart, because the {@code !} of {@code ![alt](/img/logo.png)} sits in front of the label and no
     * lookbehind reaches over a label of no fixed length.
     * <p>
     * A destination starting with {@code //} is not one of these: it is a protocol-relative URL of another
     * host, so it is outside the check already and rewriting it would break it. The template's own link pass
     * leaves those alone too - see {@code plugins/remark-env-links}.
     */
    private static final Pattern INLINE_LINK =
            Pattern.compile("(]\\()\\s*(?:<(/(?!/)[^>\\s]*)>|(/(?!/)[^)\\s]*))([^)]*\\))");

    /**
     * A link reference definition: {@code [orders]: /systems/orders/ "Orders"}. It carries a destination like
     * an inline link and Docusaurus resolves it like one, so it leaves the part in exactly the same way - and
     * the diagram pass visits these too. Grouped like {@link #INLINE_LINK}.
     */
    private static final Pattern REFERENCE_DEFINITION =
            Pattern.compile("^( {0,3}\\[[^]]+]:[ \\t]*)(?:<(/(?!/)[^>\\s]*)>|(/(?!/)\\S*))(.*)$");

    /** Where a fenced block starts and ends. Three or more backticks or tildes, indented at most three. */
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})");

    /** What Docusaurus reads as "not a route of this site, do not check it". */
    private static final String UNCHECKED = "pathname://";

    private CrossPartLinks() {
    }

    /**
     * What one environment's tree needs to resolve a link written on one of its pages.
     *
     * @param routePrefix what a path of this environment carries within the site - empty for the main
     *                    environment, {@code /dev} for another. A page writes its links relative to its own
     *                    environment while a part owns paths of the <b>site</b>, so this is what the two have
     *                    to be compared through
     * @param linkPrefix  what a path of this environment has to carry in front of it to be absolute: the base
     *                    URL of the site and the environment's own prefix
     */
    record EnvironmentLinks(String routePrefix, String linkPrefix) {
    }

    /**
     * Rewrites every link of the written content that this part does not own, and reports how many.
     *
     * @param contentDirectory   the content of one part, one directory per environment below it
     * @param part               the part that was written, which says which paths are its own
     * @param otherParts         the other parts of the site. A part that carries whole environment trees owns
     *                           whatever <b>they</b> do not claim, which is what makes the shell's own links
     *                           stay checked
     * @param linksByEnvironment what a link of each environment's tree resolves through
     */
    static int rewrite(Path contentDirectory, SitePart part, List<SitePart> otherParts,
                       Map<String, EnvironmentLinks> linksByEnvironment) {
        int rewritten = 0;
        for (Path page : pagesOf(contentDirectory)) {
            String environment = environmentOf(contentDirectory, page);
            // A page that is in no environment's tree belongs to no environment, so nothing resolves its
            // links - and an immutable map will not even be asked about a null key.
            EnvironmentLinks links = environment == null ? null : linksByEnvironment.get(environment);
            if (links == null) {
                continue;
            }
            rewritten += rewrite(page, part, otherParts, links);
        }
        if (rewritten > 0) {
            log.debug("{} link(s) of {} leave the part and were written as unchecked absolute paths.",
                    rewritten, part.key());
        }
        return rewritten;
    }

    /**
     * Whether a path of the site is one this part's own build has a route for.
     * <p>
     * Two cases, and the second is the one worth writing down. A part that carries one subtree owns that
     * subtree and nothing else. A part that carries whole environment trees - the shell - owns everything
     * <b>except</b> what another part claims: its own root page, its systems index and the page about the
     * documentation are pages of its own build, and a link to them should stay inside the check that catches a
     * generator bug.
     *
     * @param pathWithinSite the path below the site's root, not below an environment's tree
     */
    private static boolean owns(SitePart part, List<SitePart> otherParts, String pathWithinSite) {
        if (part.owns(pathWithinSite)) {
            return true;
        }
        return part.carriesWholeEnvironments()
               && otherParts.stream().noneMatch(other -> other.owns(pathWithinSite));
    }

    // Guard clauses: one continue per line that carries no link to rewrite, fenced code among them.
    @SuppressWarnings("java:S135")
    private static int rewrite(Path page, SitePart part, List<SitePart> otherParts, EnvironmentLinks links) {
        // Split keeping the trailing empty field, so joining puts the file back exactly as it was.
        String[] lines = read(page).split("\n", -1);
        String openFence = null;
        int count = 0;
        for (int i = 0; i < lines.length; i++) {
            String fence = fenceOf(lines[i]);
            if (openFence != null) {
                if (fence != null && closes(openFence, fence)) {
                    openFence = null;
                }
                continue;
            }
            if (fence != null) {
                openFence = fence;
                continue;
            }
            int before = count;
            StringBuilder inline = new StringBuilder();
            count += rewriteDestinations(INLINE_LINK, true, lines[i], part, otherParts, links, inline);
            StringBuilder definitions = new StringBuilder();
            count += rewriteDestinations(REFERENCE_DEFINITION, false, inline.toString(), part, otherParts,
                    links, definitions);
            if (count > before) {
                lines[i] = definitions.toString();
            }
        }
        if (count == 0) {
            return 0;
        }
        write(page, String.join("\n", lines));
        return count;
    }

    /**
     * Applies one destination pattern to one line, and answers how many of its destinations left the part. The
     * line is appended to {@code rewritten} either way, so a caller can put two patterns over it in turn.
     */
    private static int rewriteDestinations(Pattern pattern, boolean inline, String line, SitePart part,
                                           List<SitePart> otherParts, EnvironmentLinks links,
                                           StringBuilder rewritten) {
        Matcher destinations = pattern.matcher(line);
        int count = 0;
        while (destinations.find()) {
            String path = destinations.group(2) != null ? destinations.group(2) : destinations.group(3);
            if ((inline && isImage(line, destinations.start(1)))
                || owns(part, otherParts, links.routePrefix() + path)) {
                destinations.appendReplacement(rewritten, Matcher.quoteReplacement(destinations.group()));
            } else {
                // Without the angle brackets a destination may have been written in: what replaces it is a URL
                // with no space in it, which needs none, and one form out is one form to read.
                destinations.appendReplacement(rewritten, Matcher.quoteReplacement(
                        destinations.group(1) + UNCHECKED + links.linkPrefix() + path.substring(1)
                        + destinations.group(4)));
                count++;
            }
        }
        destinations.appendTail(rewritten);
        return count;
    }

    /**
     * Whether what a destination belongs to is an image rather than a link.
     * <p>
     * An image is not a route: it is published for the whole site, and a {@code pathname://} in a {@code src}
     * is a broken image rather than a link that left the check. The {@code !} that says so sits in front of
     * the label's <b>opening</b> bracket, so finding it means walking back over the label - counting the
     * brackets, because a label may contain a pair of its own.
     *
     * @param closingBracket where the {@code ]} of the label is
     */
    private static boolean isImage(String line, int closingBracket) {
        int depth = 0;
        for (int i = closingBracket; i >= 0; i--) {
            char bracket = line.charAt(i);
            if (bracket == ']') {
                depth++;
            } else if (bracket == '[') {
                depth--;
                if (depth == 0) {
                    return i > 0 && line.charAt(i - 1) == '!';
                }
            }
        }
        return false;
    }

    /** The marker a line opens or closes a fenced block with, or null where it is not a fence line. */
    private static String fenceOf(String line) {
        Matcher fence = FENCE.matcher(line);
        return fence.lookingAt() ? fence.group(1) : null;
    }

    /**
     * Whether a fence line closes the block another one opened: the same character, and at least as long. A
     * shorter run, or the other character, is content of the block.
     */
    private static boolean closes(String open, String fence) {
        return fence.charAt(0) == open.charAt(0) && fence.length() >= open.length();
    }

    /** Which environment tree a written page belongs to: the first segment below the content directory. */
    private static String environmentOf(Path contentDirectory, Path page) {
        Path relative = contentDirectory.relativize(page);
        return relative.getNameCount() < 2 ? null : relative.getName(0).toString();
    }

    private static List<Path> pagesOf(Path contentDirectory) {
        if (!Files.isDirectory(contentDirectory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(contentDirectory)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".md"))
                    .toList();
        } catch (IOException | UncheckedIOException e) {
            throw new UncheckedIOException("The generated pages under " + contentDirectory + " could not be "
                                           + "listed.", e instanceof UncheckedIOException unchecked
                    ? unchecked.getCause() : (IOException) e);
        }
    }

    private static String read(Path page) {
        try {
            return Files.readString(page, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("The generated page " + page + " could not be read.", e);
        }
    }

    private static void write(Path page, String text) {
        try {
            Files.writeString(page, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("The generated page " + page + " could not be rewritten.", e);
        }
    }
}
