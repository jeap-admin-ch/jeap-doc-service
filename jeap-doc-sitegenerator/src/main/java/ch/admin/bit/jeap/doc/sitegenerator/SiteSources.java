package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SiteEnvironment;

import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes what a documentation site contains, into the content directory of a build workspace.
 * <p>
 * This is the generator: it decides which pages exist and what they say. How a static site is produced from them
 * is the site generator adapter's business, and nothing about it is visible here.
 * <p>
 * <b>It writes nowhere but into the directory it is given</b>, and it cannot be talked into writing elsewhere:
 * every name it resolves is either a constant here or a slug the upload API and the configuration have already
 * validated, and a site's own logo is copied to a name derived from what it is rather than to the one it came
 * with. Nothing configured reaches the filesystem as a path.
 * <p>
 * That matters because the site template is installed over this content afterwards: the rule that generated
 * content can never become part of the Docusaurus application starts here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SiteSources {

    /** Where the root page lives, beside this class - Markdown belongs in a file, not in a string literal. */
    private static final String ROOT_PAGE_RESOURCE = "root-page.md";

    private static final String ROOT_PAGE_TEMPLATE = readRootPageTemplate();

    /**
     * Where a site's own logo and favicon are written, below the content directory.
     * <p>
     * A directory of its own, and <b>not</b> one that shadows the template's {@code static/img}: the site
     * generator copies its static directories without overwriting, so a generated {@code img/logo.svg} would be
     * silently skipped in favour of the template's default of the same name - the configured mark would simply
     * never appear, with nothing to see in the build output.
     */
    private static final String BRANDING_DIRECTORY = "static/branding";

    /** The path the generated branding is served under, which follows from the directory above. */
    private static final String BRANDING_URL_PREFIX = "branding/";
    private static final String LOGO = "logo";
    private static final String FAVICON = "favicon";

    /**
     * Its own, deliberately not the application's: what is written here is the contract between the generator
     * and the site template, and an instance that customised the service's mapper - a naming strategy, a date
     * format - would silently change a file format the template reads.
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * How a generated timestamp is written where a person reads it - the footer of every page and the row on
     * the root page. An {@code Instant} prints as {@code 2026-08-28T08:05:02.085482247Z}, which says the same
     * thing and says it to a machine.
     * <p>
     * The display form is the service's own local time, which is what the instances are configured with - the
     * same zone the publication schedules are evaluated in, and the one the configuration documents them as
     * using. Whoever needs the instant reads {@code generatedAt} from {@code site.json}, which stays ISO-8601.
     */
    private static final DateTimeFormatter GENERATED_AT_DISPLAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final SiteUrls urls;
    private final ResourceLoader resourceLoader;

    /**
     * Writes the site into the given content directory.
     */
    public void write(Site site, Path contentDirectory, Instant generatedAt) throws IOException {
        Files.createDirectories(contentDirectory);
        writeJson(contentDirectory, "environments.json", environmentsOf(site));
        writeJson(contentDirectory, "site.json", descriptionOf(site, generatedAt));
        writeBranding(site, contentDirectory);
        for (SiteEnvironment environment : site.environments()) {
            writeRootPage(site, environment, contentDirectory, generatedAt);
        }
        log.debug("Wrote the sources of the site {} with {} environments into {}.",
                site.id(), site.environments().size(), contentDirectory);
    }

    /**
     * The environments, as the site generator reads them: what the switcher shows and which tree is served at
     * the root.
     */
    private static Map<String, Object> environmentsOf(Site site) {
        List<Map<String, Object>> environments = site.environments().stream()
                .map(environment -> {
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("id", environment.id());
                    values.put("short", environment.shortName());
                    values.put("label", environment.label());
                    values.put("order", environment.order());
                    values.put("main", environment.main());
                    values.put("latest", environment.latest());
                    return values;
                })
                .toList();
        return Map.of("environments", environments);
    }

    /**
     * What the site is, as the site generator reads it - including where it is published, which the generated
     * site needs for its sitemap and its metadata.
     */
    private Map<String, Object> descriptionOf(Site site, Instant generatedAt) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", site.id());
        values.put("title", site.title());
        values.put("tagline", site.tagline() == null ? "" : site.tagline());
        values.put("colorScheme", site.colorScheme());
        values.put(LOGO, brandingUrlOf(site.logo(), LOGO));
        // A site that names a logo but no favicon uses the logo as both, and only one file is written - so the
        // favicon has to point at that file rather than at a name nothing wrote.
        values.put(FAVICON, java.util.Objects.equals(site.favicon(), site.logo())
                ? brandingUrlOf(site.logo(), LOGO)
                : brandingUrlOf(site.favicon(), FAVICON));
        values.put("url", urls.url());
        values.put("baseUrl", urls.baseUrl(site));
        values.put("generatedAt", generatedAt.toString());
        values.put("generatedAtDisplay", GENERATED_AT_DISPLAY.format(generatedAt));
        return values;
    }

    /**
     * Where the site generator finds one of the site's own branding files, or null when it brings none and the
     * template's default is used.
     */
    private static String brandingUrlOf(String location, String kind) {
        // The same predicate copyBranding uses, and for the same reason: a blank location copies no file, so
        // naming one here would put a broken mark on every page - and a name is truthy, so the template's own
        // default would be skipped in favour of it.
        return StringUtils.hasText(location) ? BRANDING_URL_PREFIX + fileNameOf(location, kind) : null;
    }

    /**
     * Copies the site's own logo and favicon into the content directory, from wherever the instance pointed at
     * them. They land under {@code content/branding}, which the site generator adds to its static directories -
     * so a site can bring its own mark without anything being written into the template's own {@code static}.
     */
    private void writeBranding(Site site, Path contentDirectory) throws IOException {
        Path branding = contentDirectory.resolve(BRANDING_DIRECTORY);
        copyBranding(site.logo(), LOGO, branding);
        if (site.favicon() != null && !site.favicon().equals(site.logo())) {
            copyBranding(site.favicon(), FAVICON, branding);
        }
    }

    private void copyBranding(String location, String kind, Path branding) throws IOException {
        if (!StringUtils.hasText(location)) {
            return;
        }
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException(
                    "The %s configured for a documentation site is not there: %s.".formatted(kind, location));
        }
        Files.createDirectories(branding);
        Path target = branding.resolve(fileNameOf(location, kind));
        try (InputStream content = resource.getInputStream()) {
            Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * The name the branding file gets in the site, keeping the extension it came with so that the browser is
     * told what it is.
     */
    private static String fileNameOf(String location, String kind) {
        String name = location.substring(location.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? kind + name.substring(dot) : kind;
    }

    /**
     * The one page this story generates: what this documentation is, which site and which environment it is,
     * and when it was generated. Every further page is the business of the stories that generate content.
     */
    private void writeRootPage(Site site, SiteEnvironment environment, Path contentDirectory, Instant generatedAt)
            throws IOException {
        Path directory = contentDirectory.resolve(environment.id());
        Files.createDirectories(directory);
        String page = ROOT_PAGE_TEMPLATE
                // The body of the page is Markdown that Docusaurus parses as MDX, so a configured value goes
                // in escaped - see MarkdownText. The front matter below is a different matter: it is YAML.
                .replace("{{title}}", MarkdownText.escaped(site.title()))
                .replace("{{tagline}}", MarkdownText.escaped(site.tagline()))
                .replace("{{environmentNote}}", environment.latest()
                        ? ", which carries the documentation of every component as it stands right now, whether "
                          + "it is deployed anywhere or not"
                        : "")
                .replace("{{environmentLabel}}", MarkdownText.escaped(environment.label()))
                .replace("{{environmentId}}", environment.id())
                .replace("{{siteId}}", site.id())
                .replace("{{generatedAt}}", GENERATED_AT_DISPLAY.format(generatedAt))
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

    /**
     * The root page, read once while the class is loaded. A page that cannot be read is a broken artifact, not
     * a runtime condition - so it fails here rather than minutes into the first build.
     */
    private static String readRootPageTemplate() {
        try (InputStream template = SiteSources.class.getResourceAsStream(ROOT_PAGE_RESOURCE)) {
            if (template == null) {
                throw new IllegalStateException(
                        "The root page template %s is missing from the jeap-doc-sitegenerator artifact."
                                .formatted(ROOT_PAGE_RESOURCE));
            }
            return new String(template.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("The root page template %s could not be read."
                    .formatted(ROOT_PAGE_RESOURCE), e);
        }
    }

    private void writeJson(Path contentDirectory, String name, Object value) throws IOException {
        Files.writeString(contentDirectory.resolve(name),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n",
                StandardCharsets.UTF_8);
    }
}
