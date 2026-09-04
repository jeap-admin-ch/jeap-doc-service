package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.DisplayTime;
import ch.admin.bit.jeap.doc.domain.port.SiteBuildException;
import ch.admin.bit.jeap.doc.domain.BuildProperties;
import ch.admin.bit.jeap.doc.domain.DocumentationFacts;
import ch.admin.bit.jeap.doc.domain.DocumentationProvenance;
import ch.admin.bit.jeap.doc.domain.DocumentationSites;
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
import java.util.Optional;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    private final SiteUrls urls;
    private final ResourceLoader resourceLoader;

    /** Writes the systems. This class writes the site-level files, and templates write the rest. */
    private final SystemPages systemPages;

    /** All the sites this instance serves, so the footer of one can link to the others. */
    private final DocumentationSites sites;

    /**
     * What the build is configured to do, for the settings the site template has to know about - the worker
     * threads of the static generation. It travels in {@code site.json} rather than in the child's environment,
     * which is built from nothing on purpose.
     */
    private final BuildProperties buildProperties;

    /** What the service may say about itself, and the page that says it. */
    private final DocumentationProvenance provenance;

    private final AboutThisDocumentation aboutThisDocumentation;

    /**
     * Writes the site into the given content directory, and answers what each environment's architecture model
     * contributed - only the environments that read one, because one that reads none has nothing to say about
     * that, and a zero would say the landscape is empty.
     */
    public Map<String, EnvironmentModel> write(long buildId, Site site, Path contentDirectory,
                                               Instant generatedAt) throws IOException {
        Files.createDirectories(contentDirectory);
        writeJson(contentDirectory, "environments.json", environmentsOf(site));
        writeBranding(site, contentDirectory);
        boolean mainHasSystems = false;
        Map<String, EnvironmentModel> models = new LinkedHashMap<>();
        for (SiteEnvironment environment : site.environments()) {
            Path directory = contentDirectory.resolve(environment.id());
            // The systems first, so the root page can say whether there are any.
            Optional<EnvironmentModel> model = systemPages.write(site, environment, directory, generatedAt);
            writeRootPage(site, environment, contentDirectory, generatedAt, model);
            model.ifPresent(counts -> models.put(environment.id(), counts));
            if (environment.main() && model.map(EnvironmentModel::systems).orElse(0) > 0) {
                mainHasSystems = true;
            }
        }
        // After the loop, because the page prints what every environment's model contributed and the loop is
        // what counted it. One page per tree - see AboutThisDocumentation.
        writeAboutThisDocumentation(buildId, site, contentDirectory, generatedAt, models);
        // Last, because it records whether the main environment has a systems page: the footer links to it,
        // and a link to a page that was not written fails the whole build.
        writeJson(contentDirectory, "site.json", descriptionOf(site, generatedAt, mainHasSystems));
        log.debug("Wrote the sources of the site {} with {} environments into {}.",
                site.id(), site.environments().size(), contentDirectory);
        return models;
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
    private Map<String, Object> descriptionOf(Site site, Instant generatedAt, boolean mainHasSystems) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", site.id());
        values.put("title", site.title());
        values.put("tagline", site.tagline() == null ? "" : site.tagline());
        values.put("colorScheme", site.colorScheme());
        values.put(LOGO, brandingUrlOf(site.logo(), LOGO));
        // A site that names a logo but no favicon uses the logo as both, and only one file is written - so the
        // favicon has to point at that file rather than at a name nothing wrote.
        values.put(FAVICON, Objects.equals(site.favicon(), site.logo())
                ? brandingUrlOf(site.logo(), LOGO)
                : brandingUrlOf(site.favicon(), FAVICON));
        values.put("url", urls.url());
        values.put("baseUrl", urls.baseUrl(site));
        // Whether /systems/ exists in the main environment, so the footer links to it only when it does.
        values.put("hasSystems", mainHasSystems);
        // Whether the static generation may use a pool of worker threads - a memory decision, made by the
        // service and read by the template, because Docusaurus has no environment variable for it.
        values.put("ssgWorkerThreads", buildProperties.isSsgWorkerThreads());
        values.put("sites", siblingSites());
        values.put("generatedAt", generatedAt.toString());
        values.put("generatedAtDisplay", DisplayTime.of(generatedAt));
        return values;
    }

    /**
     * Every site this instance serves, with its title and its absolute URL, for the footer's Sites group -
     * including the current one, so the group reads as a complete list rather than "the others". The URL is
     * absolute because a link from one site to another crosses base URLs - each site is its own Docusaurus
     * application. The footer shows the group only when there is more than one site.
     */
    private List<Map<String, Object>> siblingSites() {
        return sites.all().stream()
                .map(other -> {
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("id", other.id());
                    values.put("title", other.title());
                    values.put("url", urls.url() + urls.baseUrl(other));
                    return values;
                })
                .toList();
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
     * The page describing the documentation, into every environment tree of the site.
     * <p>
     * <b>There is no graceful path here.</b> The root page and the footer of the template both link the page,
     * and the site is built with {@code onBrokenLinks: 'throw'} - so a run that left it out would fail anyway,
     * later and with a message about a link rather than about the facts. The facts are absent only for a site
     * that is not configured, and only a configured site is ever built, so this says what went wrong instead of
     * pretending to carry on.
     */
    private void writeAboutThisDocumentation(long buildId, Site site, Path contentDirectory,
                                             Instant generatedAt, Map<String, EnvironmentModel> models)
            throws IOException {
        DocumentationFacts facts = provenance.of(site.id(), DocServiceVersion.get(), generatedAt)
                .orElseThrow(() -> new SiteBuildException(
                        "The site %s is being built and is not configured, so the page describing the "
                        .formatted(site.id())
                        + "documentation cannot be written - and the root page links it."));
        // Where the numbers of this build are published: the origin, the base URL of this site, and the file
        // the run writes beside the site once it knows them.
        String statusUrl = urls.url() + urls.baseUrl(site) + AboutThisDocumentation.STATUS_FILE;
        for (SiteEnvironment environment : site.environments()) {
            aboutThisDocumentation.write(facts, environment, models, buildId, statusUrl,
                    contentDirectory.resolve(environment.id()));
        }
    }

    /**
     * The one page this story generates: what this documentation is, which site and which environment it is,
     * and when it was generated. Every further page is the business of the stories that generate content.
     */
    private void writeRootPage(Site site, SiteEnvironment environment, Path contentDirectory,
                               Instant generatedAt, Optional<EnvironmentModel> model) throws IOException {
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
                .replace("{{contents}}", contentsOf(model))
                .replace("{{systemsRow}}", systemsRowOf(model))
                .replace("{{generatedAt}}", DisplayTime.of(generatedAt))
                // Front matter, so YAML scalars: an environment id is a slug and an instant is safe either
                // way, and quoting them is what keeps that true of a value somebody changes later.
                .replace("{{environmentIdScalar}}", JSON.writeValueAsString(environment.id()))
                .replace("{{generatedAtScalar}}", JSON.writeValueAsString(generatedAt.toString()))
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

    /** What the root page says about this tree: where to start, or that nothing has been published yet. */
    private static String contentsOf(Optional<EnvironmentModel> model) {
        if (model.map(EnvironmentModel::systems).orElse(0) == 0) {
            return "Nothing has been published into it yet: the documentation of the systems, their components "
                   + "and their libraries arrives as the pipelines of those repositories upload it, and as the "
                   + "architecture model is read.";
        }
        return "Start at [Systems](/systems/), which lists every system of the landscape with its components, "
               + "its events and its commands.";
    }

    /**
     * The row counting the systems, or no row at all.
     * <p>
     * An environment that reads no architecture model knows nothing about how many systems there are. A zero
     * would say the landscape is empty rather than that it was never looked at.
     */
    private static String systemsRowOf(Optional<EnvironmentModel> model) {
        return model.map(counts -> "| Systems | " + counts.systems() + " |\n").orElse("");
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
