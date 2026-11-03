package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.DisplayTime;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SiteEnvironment;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * The root page of a tree: what this documentation is, which site and which environment it is, and when it was
 * generated. What it says about the tree's contents is the content writer's, which knows them.
 */
final class RootPage {

    /** Where the page lives, beside this class - Markdown belongs in a file, not in a string literal. */
    private static final String RESOURCE = "root-page.md";

    private static final String TEMPLATE = readTemplate();

    /** Its own, for the same reason as {@code SiteSources}': the page's front matter is a file format. */
    private static final ObjectMapper JSON = new ObjectMapper();

    private RootPage() {
    }

    /**
     * Writes the page into a tree.
     *
     * @param environment the environment whose tree this is, or null for the one tree of a site without
     *                    environments, whose root page names none
     * @param directory   the tree the page is written into
     * @param contents    what the page says about the tree, as Markdown: where to start, or that it is empty
     * @param systemsRow  the table row counting the systems, with its line break, or nothing
     */
    static void write(Site site, SiteEnvironment environment, Path directory, Instant generatedAt,
                      String contents, String systemsRow) throws IOException {
        Files.createDirectories(directory);
        String page = TEMPLATE
                // The body of the page is Markdown that Docusaurus parses as MDX, so a configured value goes
                // in escaped - see MarkdownText. The front matter below is a different matter: it is YAML.
                .replace("{{title}}", MarkdownText.escaped(site.title()))
                .replace("{{tagline}}", MarkdownText.escaped(site.tagline()))
                .replace("{{environmentSentence}}", environmentSentenceOf(environment))
                .replace("{{environmentRow}}", environment == null ? ""
                        : "| Environment | %s (`%s`) |\n".formatted(
                                MarkdownText.escaped(environment.label()), environment.id()))
                .replace("{{siteId}}", site.id())
                .replace("{{contents}}", contents)
                .replace("{{systemsRow}}", systemsRow)
                .replace("{{generatedAt}}", DisplayTime.of(generatedAt))
                // Front matter, so YAML scalars: an environment id is a slug and an instant is safe either
                // way, and quoting them is what keeps that true of a value somebody changes later.
                .replace("{{environmentFrontMatter}}", environment == null ? ""
                        : "doc_environment: " + JSON.writeValueAsString(environment.id()) + "\n")
                .replace("{{generatedAtScalar}}", JSON.writeValueAsString(generatedAt.toString()))
                // Beside it the same moment as a reader reads it, because the provenance block under the page
                // falls back to the instant where this is missing.
                .replace("{{generatedAtDisplayScalar}}", JSON.writeValueAsString(DisplayTime.of(generatedAt)))
                // The front matter is YAML, and a title is free text: 'jEAP: Documentation' or one starting
                // with # or [ would be invalid front matter, and the build would fail minutes later with a
                // js-yaml message naming neither the property nor the site. A JSON string is a valid YAML
                // double-quoted scalar, so encoding it is the whole fix - and Docusaurus puts that title into
                // the browser tab as text, so it is escaped as YAML here and nothing else.
                // Last, and that is not cosmetic: a JSON scalar keeps the braces the escaping above takes away,
                // so a title of '{{tagline}}' substituted any earlier would be substituted again by whichever
                // replacement follows it, and the browser tab would show the tagline instead of the title.
                .replace("{{titleScalar}}", JSON.writeValueAsString(site.title()));
        Files.writeString(directory.resolve("index.md"), page, StandardCharsets.UTF_8);
    }

    /** Which environment the root page shows, with a leading space, or nothing on a site without environments. */
    private static String environmentSentenceOf(SiteEnvironment environment) {
        if (environment == null) {
            return "";
        }
        return " It shows the **%s** environment%s.".formatted(MarkdownText.escaped(environment.label()),
                environment.latest()
                        ? ", which carries the documentation of every component as it stands right now, whether "
                          + "it is deployed anywhere or not"
                        : "");
    }

    /**
     * The page, read once while the class is loaded. A page that cannot be read is a broken artifact, not a
     * runtime condition - so it fails here rather than minutes into the first build.
     */
    private static String readTemplate() {
        try (InputStream template = RootPage.class.getResourceAsStream(RESOURCE)) {
            if (template == null) {
                throw new IllegalStateException(
                        "The root page template %s is missing from the jeap-doc-sitegenerator artifact."
                                .formatted(RESOURCE));
            }
            return new String(template.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("The root page template %s could not be read."
                    .formatted(RESOURCE), e);
        }
    }
}
