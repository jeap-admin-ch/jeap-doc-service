package ch.admin.bit.jeap.doc.domain.custom;

import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the front matter of an uploaded page, and writes the one this service decides.
 * <p>
 * <b>The body is never rewritten.</b> Nothing here reads it, edits it or reasons about it - the blank lines
 * between it and the front matter are normalised and that is all - so nothing here can corrupt what a team
 * wrote, and it is why the provenance of a custom page is not appended to it: an admonition after an
 * unterminated code fence is a broken page.
 * <p>
 * <b>The front matter is parsed and written as YAML, not assembled as text.</b> It is YAML that Docusaurus
 * will parse, so anything less than a parser here is a second, poorer implementation of it - and one that has
 * to be right about quoting, about a scalar continued over several lines and about a value that looks like a
 * date. Each of those got its own hand-rolled rule before, and a value the reader mishandled did not fail
 * here: it failed the build of the whole part, twenty minutes later, on a YAML error naming a file.
 * <p>
 * <b>The keys the upload may carry are kept and everything else is dropped.</b> An allowlist rather than a
 * blocklist is what makes that safe: a page cannot claim to be generated, or take over a route with
 * {@code slug}, by carrying the key itself.
 */
@Slf4j
public final class UploadedFrontMatter {

    private static final String DELIMITER = "---";

    /** Escaped rather than written: a raw one is invisible in an editor and in a diff. */
    private static final String BYTE_ORDER_MARK = "\uFEFF";

    /**
     * The keys an uploaded page may say about itself.
     * <p>
     * The same list the doc workflow validates against. What is not here is refused there and dropped here,
     * which is two answers to one question - and the reason for both is the same: a page's identity, its URL
     * and its place in the navigation are not the page's to decide.
     */
    public static final Set<String> ALLOWED_KEYS =
            Set.of("title", "description", "sidebar_label", "tags", "keywords");

    /** The two the site reads as a list of strings; the rest it reads as one string. */
    private static final Set<String> LIST_KEYS = Set.of("tags", "keywords");

    /**
     * What the reader will not go past, over untrusted input: the block of a page, the nesting a title or a
     * list of keywords needs, and no alias to a collection - which is what an expansion bomb is built from. An
     * alias to a scalar is not bounded by this and does not need to be: it expands to one scalar.
     */
    private static final int MAX_BLOCK_CHARACTERS = 64 * 1024;
    private static final int MAX_NESTING_DEPTH = 8;
    private static final int MAX_COLLECTION_ALIASES = 0;

    private UploadedFrontMatter() {
    }

    /**
     * The page with its front matter replaced.
     *
     * @param page      the page as it was uploaded
     * @param generated the keys the doc service adds, in the order they are written, as the values they are -
     *                  a number as a number and an instant as its text; the quoting is this class's business
     */
    public static String rewritten(String page, Map<String, Object> generated) {
        Block block = blockOf(page);
        Map<String, Object> written = new LinkedHashMap<>();
        parsed(block.block()).forEach((key, value) ->
                keptValueOf(key, value).ifPresent(kept -> written.put(key, kept)));
        written.putAll(generated);
        StringBuilder rewritten = new StringBuilder(DELIMITER).append('\n')
                .append(dumped(written))
                .append(DELIMITER).append('\n');
        String body = block.body();
        // One blank line between the block and the body, however many the page had - and none added to a page
        // that is nothing but front matter.
        return body.isBlank() ? rewritten.toString() : rewritten.append('\n').append(body.stripLeading())
                .toString();
    }

    /**
     * An allowlisted key with a value the site can take, or nothing.
     * <p>
     * <b>The shape matters as much as the name.</b> Docusaurus validates the front matter of a document and
     * throws on a value of the wrong kind - a {@code title} given as a list, {@code keywords} given as a
     * scalar - so a page carrying one would fail the build of the whole part, which is exactly what parsing
     * the block was meant to stop. A value that cannot be published is dropped like an unreadable block: the
     * page keeps everything else and is published.
     * <p>
     * A scalar is taken as its text, which is also what turns the {@link java.util.Date} a plain
     * {@code 2024-01-01} parses into back into the title the page meant.
     */
    private static Optional<Object> keptValueOf(String key, Object value) {
        if (value == null || !ALLOWED_KEYS.contains(key)) {
            return Optional.empty();
        }
        if (LIST_KEYS.contains(key)) {
            if (!(value instanceof List<?> items) || items.stream().anyMatch(UploadedFrontMatter::isNotScalar)) {
                return Optional.empty();
            }
            List<String> texts = items.stream().map(UploadedFrontMatter::textOf)
                    .filter(text -> !text.isEmpty()).toList();
            return texts.isEmpty() ? Optional.empty() : Optional.of(texts);
        }
        if (isNotScalar(value)) {
            return Optional.empty();
        }
        String text = textOf(value);
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }

    /**
     * A scalar as the text it is, stripped.
     * <p>
     * <b>Blank is no value.</b> {@link UploadedTitles} reads a blank title as no title - it sorts the page by
     * its file name - so keeping one here would publish an empty title in the sidebar and in the heading of a
     * page the navigation says has none. Two readers of one format disagreeing about a page is the thing
     * parsing it in one place is for.
     */
    private static String textOf(Object value) {
        return String.valueOf(value).strip();
    }

    private static boolean isNotScalar(Object value) {
        return value == null || value instanceof Map || value instanceof Iterable;
    }

    /** An ordered map, so the keys are written in the order they are put in. */
    public static Map<String, Object> keys() {
        return new LinkedHashMap<>();
    }

    /**
     * The mapping in the front matter of a page, or an empty one where it has none or none that can be read.
     * <p>
     * <b>Unreadable front matter is no front matter.</b> A page whose block does not parse, or is not a
     * mapping, or is longer than this reads, still has to be published - what it loses is what it said about
     * itself, which is a title and a description.
     *
     * @param block the text between the two delimiters, without them
     */
    static Map<String, Object> parsed(String block) {
        if (block == null || block.isBlank()) {
            return Map.of();
        }
        if (block.length() > MAX_BLOCK_CHARACTERS) {
            log.warn("The front matter of an uploaded page is longer than the {} characters this service "
                     + "reads; the page is published without what it said about itself.",
                    MAX_BLOCK_CHARACTERS);
            return Map.of();
        }
        try {
            Object loaded = reader().load(block);
            if (!(loaded instanceof Map<?, ?> mapping)) {
                return Map.of();
            }
            Map<String, Object> keys = new LinkedHashMap<>();
            mapping.forEach((key, value) -> {
                if (key instanceof String name) {
                    keys.put(name, value);
                }
            });
            return keys;
        } catch (YAMLException e) {
            log.debug("The front matter of an uploaded page cannot be read as YAML; the page is published "
                      + "without what it said about itself.", e);
            return Map.of();
        }
    }

    /**
     * A reader that constructs nothing but the collections and scalars YAML defines, and stops well before
     * anything an uploaded page could make it do.
     * <p>
     * <b>{@link SafeConstructor}, because the content is untrusted.</b> The default constructor instantiates
     * the types a document names, which is a deserialization sink; this one refuses a tag it does not know.
     * The bounds beside it are what an expansion bomb runs into.
     * <p>
     * A reader per call, deliberately: a {@link Yaml} is not thread-safe, and several parts of a site are
     * generated at once.
     */
    private static Yaml reader() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(MAX_COLLECTION_ALIASES);
        options.setNestingDepthLimit(MAX_NESTING_DEPTH);
        options.setCodePointLimit(MAX_BLOCK_CHARACTERS);
        DumperOptions dumper = new DumperOptions();
        return new Yaml(new SafeConstructor(options), new Representer(dumper), dumper, options,
                new EverythingIsText());
    }

    /**
     * A resolver that reads every plain scalar as text.
     * <p>
     * <b>The five keys a page may carry are text and lists of text</b>, and YAML's implicit types are all
     * wrong for them: {@code title: 2024-01-01} is a title and not a timestamp, {@code title: 1.10} is a
     * title and not the number 1.1, {@code description: NO} is a description and not {@code false}. Resolving
     * them and converting back would publish what {@code Date.toString()} makes of a date in the build
     * machine's own time zone; not resolving them publishes what the page wrote.
     */
    private static final class EverythingIsText extends Resolver {

        @Override
        protected void addImplicitResolvers() {
            // None. A scalar is its text, and the structure - a mapping, a list - is still YAML's.
        }
    }

    /**
     * The block as YAML, with the quoting the emitter decides.
     * <p>
     * <b>That is the point of writing it through a dumper.</b> A repository URL holds a colon, a title may
     * hold anything, and an instant written plainly would be read back as a date rather than as the text it
     * is - the emitter quotes exactly what needs it, which is a rule nobody here has to keep.
     */
    private static String dumped(Map<String, Object> keys) {
        if (keys.isEmpty()) {
            return "";
        }
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        // The items of a list under their key, which is how everyone writes one by hand and therefore how a
        // diff of a published page reads.
        options.setIndent(2);
        options.setIndicatorIndent(2);
        options.setIndentWithIndicator(true);
        // A line of front matter is never wrapped: a folded title is legal YAML and an unnecessary way to
        // make a page's own metadata hard to read in a diff.
        options.setWidth(Integer.MAX_VALUE);
        options.setSplitLines(false);
        return new Yaml(new SafeConstructor(new LoaderOptions()), new Representer(options), options)
                .dump(keys);
    }

    /**
     * The front matter of the page, and the body below it.
     * <p>
     * <b>The delimiters are a Markdown convention rather than YAML</b>, so finding them is a matter of lines:
     * the block is what lies between the first {@code ---} and the next one. A block that is never closed
     * means there is no telling where the page begins, and the page carries no front matter at all.
     */
    private static Block blockOf(String page) {
        String text = page.startsWith(BYTE_ORDER_MARK) ? page.substring(1) : page;
        List<String> lines = List.of(text.split("\n", -1));
        int first = 0;
        while (first < lines.size() && lines.get(first).isBlank()) {
            first++;
        }
        if (first >= lines.size() || !lines.get(first).strip().equals(DELIMITER)) {
            return new Block("", text);
        }
        for (int i = first + 1; i < lines.size(); i++) {
            if (lines.get(i).strip().equals(DELIMITER)) {
                return new Block(String.join("\n", lines.subList(first + 1, i)),
                        String.join("\n", lines.subList(i + 1, lines.size())));
            }
        }
        return new Block("", String.join("\n", lines.subList(first + 1, lines.size())));
    }

    /** The front matter of a page as it was written, and the body under it. */
    private record Block(String block, String body) {
    }
}
