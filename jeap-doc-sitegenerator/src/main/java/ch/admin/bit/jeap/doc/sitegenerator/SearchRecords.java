package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SiteEnvironment;
import ch.admin.bit.jeap.doc.domain.template.DocumentationPaths;
import ch.admin.bit.jeap.doc.domain.template.NumberPrefixes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads a written content tree back as one record per page.
 * <p>
 * <b>It reads the Markdown rather than the generated HTML</b>, and that is the point. Reading Markdown means a
 * page that was uploaded is read by the same code as a page that was generated, so custom documentation is
 * indexed without anything here knowing it exists. It also means a fenced diagram is a block to skip rather
 * than a CSS selector to guess at: the offline search plugin this replaces indexed every PlantUML and Avro
 * block on the site, because nobody had told it not to.
 * <p>
 * Nothing here knows a chapter, a component or arc42. It knows the folder layout of a content tree, which is
 * the site generator's own.
 */
public final class SearchRecords {

    /** A front matter block: three dashes, keys, three dashes, at the very start of the file. */
    private static final Pattern FRONT_MATTER = Pattern.compile("\\A---\\R(.*?)\\R---\\R", Pattern.DOTALL);

    private static final Pattern KEY = Pattern.compile("^(\\w+):[ \\t]*(.*)$", Pattern.MULTILINE);

    /** What an uploaded page's {@code doc_status} says, against the {@code generated} of a written one. */
    private static final String CUSTOM = "custom";

    /** The front matter key that says a page frames a microsite, and where that microsite is served. */
    private static final String MICROSITE_URL = "doc_microsite_url";

    /**
     * A fenced block, whatever its language, including the fences.
     * <p>
     * <b>The block closes on its own marker</b>, which is what the back reference is for. A fence is three
     * backticks <i>or more</i> - {@code MarkdownWriter.fence} lengthens it whenever the body holds a backtick
     * run, and documentation showing a Markdown example does the same - so a pattern that only ever closed on
     * three of them ran past the end of such a block and swallowed the prose after it, indexing the diagram it
     * was meant to skip instead. Up to three spaces of indentation, and tildes as well as backticks, because
     * CommonMark says so.
     */
    private static final Pattern FENCE = Pattern.compile("^[ \\t]{0,3}(`{3,}|~{3,})[^\\n]*\\R.*?^[ \\t]{0,3}\\1[`~]*[ \\t]*$",
            Pattern.DOTALL | Pattern.MULTILINE);

    private static final Pattern HEADING = Pattern.compile("^#{1,4}[ \\t]+(.*?)[ \\t]*$", Pattern.MULTILINE);

    /** An inline link or image: what a reader sees of it is its label. */
    private static final Pattern LINK = Pattern.compile("!?\\[([^\\]]*)]\\([^)]*\\)");

    /** An admonition marker - {@code :::note} and its closing {@code :::} - which is layout, not text. */
    private static final Pattern ADMONITION = Pattern.compile("^[ \\t]*:::.*$", Pattern.MULTILINE);

    /** The rule under a table's header row. */
    private static final Pattern TABLE_RULE = Pattern.compile("^[ \\t]*\\|?[ \\t]*[-:][-:| \\t]*$", Pattern.MULTILINE);


    private SearchRecords() {
    }

    /**
     * Every page of the given content tree, one record each.
     * <p>
     * Only the environment trees are read. A content directory also holds the two JSON files the template
     * reads and the site's branding, and none of that is a page.
     */
    public static List<SearchRecord> of(Path contentDirectory, Site site) {
        List<SearchRecord> records = new ArrayList<>();
        for (SiteEnvironment environment : site.environments()) {
            Path tree = contentDirectory.resolve(environment.id());
            if (!Files.isDirectory(tree)) {
                // An environment that reads no architecture model, or a site not every part was written for.
                continue;
            }
            records.addAll(readTree(tree, environment));
        }
        return List.copyOf(records);
    }

    private static List<SearchRecord> readTree(Path tree, SiteEnvironment environment) {
        try (Stream<Path> files = Files.walk(tree)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .map(file -> read(file, tree, environment))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("The content tree of " + environment.id() + " could not be read", e);
        }
    }

    private static SearchRecord read(Path file, Path tree, SiteEnvironment environment) {
        String raw = readFile(file);
        Matcher frontMatter = FRONT_MATTER.matcher(raw);
        String keys = "";
        String body = raw;
        if (frontMatter.find()) {
            keys = frontMatter.group(1);
            body = raw.substring(frontMatter.end());
        }

        String relative = tree.relativize(file).toString().replace('\\', '/');
        String title = valueOf(keys, "title");
        // The fences go first, so that a `#` inside one is never read as a heading.
        String withoutFences = FENCE.matcher(body).replaceAll(" ");
        String micrositeUrl = valueOf(keys, MICROSITE_URL);
        return new SearchRecord(
                urlOf(relative, valueOf(keys, "slug"), environment),
                title.isEmpty() ? fileNameAsTitle(relative) : title,
                headingsOf(withoutFences),
                plain(HEADING.matcher(withoutFences).replaceAll(" ")),
                environment.id(),
                sourceOf(valueOf(keys, "doc_status"), micrositeUrl),
                subjectOf(relative),
                systemOf(relative),
                nameOf(relative),
                micrositeUrl.isEmpty() ? null : micrositeUrl,
                // A page of the site, not a page inside a microsite: what a hit inside one says it is in is
                // set where those records are made.
                null);
    }

    /**
     * What produced a page, from what the page says about itself.
     * <p>
     * Every page the service writes carries {@code doc_status}, and an uploaded one carries {@code custom} -
     * which is the same key the provenance block on the page is built from, so the badge on a search result
     * and the line under the page can never disagree. A page that frames a microsite is uploaded HTML: it
     * says nothing of its own, and every record of the files inside it is one of these too.
     */
    private static String sourceOf(String status, String micrositeUrl) {
        if (!micrositeUrl.isEmpty()) {
            return SearchRecord.HTML;
        }
        return CUSTOM.equals(status) ? SearchRecord.MARKDOWN : SearchRecord.GENERATED;
    }

    /**
     * What a page documents, from the folder it is in.
     * <p>
     * <b>A library is not a component.</b> It publishes no artifact and is deployed nowhere, so no
     * architecture model holds one and all twelve of its chapters are written by hand - and a reader
     * narrowing a search to components should not be shown one.
     * <p>
     * Null for the site's own pages - the root, the systems index, the page about the documentation. They
     * document nothing, so they carry no value, and a search narrowed by subject leaves them out.
     */
    private static String subjectOf(String relativePath) {
        String[] segments = relativePath.split("/");
        if (segments.length <= 2 || !DocumentationPaths.SYSTEMS_SEGMENT.equals(segments[0])) {
            return null;
        }
        for (int i = 0; i < segments.length - 1; i++) {
            if (DocumentationPaths.COMPONENTS_SEGMENT.equals(segments[i])) {
                return SearchRecord.COMPONENT;
            }
            if (DocumentationPaths.LIBRARIES_SEGMENT.equals(segments[i])) {
                return SearchRecord.LIBRARY;
            }
        }
        return SearchRecord.SYSTEM;
    }

    /**
     * Where a page is served.
     * <p>
     * The path within the environment tree is the path within the site below that environment's prefix -
     * {@code index.md} collapsing to its directory, and a trailing slash because the site is generated with
     * {@code trailingSlash: true}. An explicit {@code slug} in the front matter wins, because that is what
     * Docusaurus will do with it; the root page of every environment carries one.
     * <p>
     * <b>And the number prefixes come off, every segment of the path.</b> The chapter folders are numbered on
     * disk and Docusaurus does not serve them numbered - {@code 5-building-block-view} is published at
     * {@code building-block-view} - so a URL taken from the file path names a page that does not exist. It is
     * the same rule the upload validation applies to a document's name, which is why
     * {@link NumberPrefixes} is where both can read it. Not applied to a {@code slug}: Docusaurus takes that
     * one as it is written.
     */
    private static String urlOf(String relativePath, String slug, SiteEnvironment environment) {
        String prefix = environment.routePrefix();
        if (!slug.isEmpty()) {
            String withoutSlashes = slug.replaceAll("^/+", "").replaceAll("/+$", "");
            if (withoutSlashes.isEmpty()) {
                return prefix + "/";
            }
            // A slug that starts with a slash is the route from the root of the documentation; one that does
            // not is relative to the folder the page is in, which is what Docusaurus does with it. The page
            // that frames a microsite carries the second kind - `microsites/<topic>` inside its chapter - and
            // reading it as the first made the search point at a route that does not exist.
            return slug.startsWith("/") ? prefix + "/" + withoutSlashes + "/"
                    : prefix + "/" + routeOfTheFolder(relativePath) + withoutSlashes + "/";
        }
        String withoutExtension = relativePath.substring(
                0, relativePath.length() - DocumentationPaths.MARKDOWN_EXTENSION.length() - 1);
        String index = DocumentationPaths.INDEX_SEGMENT;
        String route = withoutExtension.equals(index) ? ""
                : withoutExtension.endsWith("/" + index)
                ? withoutExtension.substring(0, withoutExtension.length() - index.length() - 1)
                : withoutExtension;
        route = NumberPrefixes.strippedFromEverySegment(route);
        return route.isEmpty() ? prefix + "/" : prefix + "/" + route + "/";
    }

    /**
     * The route of the folder a page is in, with a trailing slash, or empty for a page at the root of an
     * environment tree. The number prefixes come off, as they do everywhere else.
     */
    private static String routeOfTheFolder(String relativePath) {
        int lastSlash = relativePath.lastIndexOf('/');
        if (lastSlash < 0) {
            return "";
        }
        return NumberPrefixes.strippedFromEverySegment(relativePath.substring(0, lastSlash)) + "/";
    }

    /**
     * The system a page documents, from the folder it is in. Null for the site's own pages - the root page,
     * the systems index, the page about the documentation - which belong to no system.
     */
    private static String systemOf(String relativePath) {
        String[] segments = relativePath.split("/");
        return segments.length > 2 && DocumentationPaths.SYSTEMS_SEGMENT.equals(segments[0])
                ? segments[1] : null;
    }

    /**
     * The component or the library a page documents, from the folder it is in, or null for a page that is
     * the system's own.
     * <p>
     * <b>It is what tells a reader which of a system's fifty components a hit is in.</b> A component's tree
     * hangs inside the chapter that describes the decomposition, so the component is the segment after
     * {@code components} - and a page that only mentions one, such as the whitebox view, is not in it. A
     * library's tree hangs in the same chapter, under a group of its own.
     */
    private static String nameOf(String relativePath) {
        String[] segments = relativePath.split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            if (DocumentationPaths.COMPONENTS_SEGMENT.equals(segments[i])
                || DocumentationPaths.LIBRARIES_SEGMENT.equals(segments[i])) {
                return segments[i + 1];
            }
        }
        return null;
    }

    private static List<String> headingsOf(String withoutFences) {
        List<String> headings = new ArrayList<>();
        Matcher matcher = HEADING.matcher(withoutFences);
        while (matcher.find()) {
            String heading = plain(matcher.group(1));
            if (!heading.isEmpty()) {
                headings.add(heading);
            }
        }
        return headings;
    }

    /**
     * Markdown as a reader sees it: link labels rather than links, no table pipes, no emphasis or code
     * markers, and the character references the generator writes for a title back as themselves.
     */
    static String plain(String markdown) {
        String text = LINK.matcher(markdown).replaceAll("$1");
        text = ADMONITION.matcher(text).replaceAll(" ");
        text = TABLE_RULE.matcher(text).replaceAll(" ");
        // Emphasis and code markers out - but not the underscore. This documentation is full of snake_case
        // table and column names, and splitting `tenant_reference` into two words is how a reader searching
        // for the identifier they are looking at finds nothing.
        text = text.replace('|', ' ').replaceAll("[*`>]", " ");
        text = text.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&#123;", "{").replace("&#125;", "}")
                .replace("&amp;", "&");
        return text.replaceAll("\\s+", " ").trim();
    }

    /** A page with no title in its front matter still has to be findable, so its file name stands in. */
    private static String fileNameAsTitle(String relativePath) {
        String name = relativePath.substring(relativePath.lastIndexOf('/') + 1);
        return name.substring(0, name.length() - ".md".length());
    }

    private static String valueOf(String frontMatter, String key) {
        Matcher matcher = KEY.matcher(frontMatter);
        while (matcher.find()) {
            if (matcher.group(1).equals(key)) {
                String value = matcher.group(2).trim();
                // Scalars are quoted where they have to be; what is wanted here is the text inside.
                if (value.length() > 1 && (value.startsWith("\"") && value.endsWith("\"")
                                           || value.startsWith("'") && value.endsWith("'"))) {
                    return value.substring(1, value.length() - 1);
                }
                return value;
            }
        }
        return "";
    }

    private static String readFile(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("A page of the content tree could not be read: " + file, e);
        }
    }
}
