package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.DisplayTime;
import ch.admin.bit.jeap.doc.domain.ArchitectureImportProperties;
import ch.admin.bit.jeap.doc.domain.BuildProperties;
import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.PublicationProperties;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SiteEnvironment;
import ch.admin.bit.jeap.doc.domain.SiteProperties;
import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureSnapshot;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource;
import ch.admin.bit.jeap.doc.domain.port.BuildMetrics;
import ch.admin.bit.jeap.doc.domain.template.StructureTemplates;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the doc service hands the site generator.
 * <p>
 * These files are the contract between the two: the template reads `site.json` and `environments.json` by the
 * field names below, and a page whose front matter is wrong fails the Docusaurus build minutes into a run. So
 * they are asserted here rather than discovered there.
 */
class SiteSourcesTest {

    private static final Instant GENERATED_AT = Instant.parse("2026-08-27T09:00:00Z");

    /**
     * The readable form of the instant above, derived the way the generator derives it rather than written out.
     * A literal '2026-08-27 11:00:00' would pass here and fail on a build server running in UTC - the format is
     * fixed, the zone is the one the service happens to run in.
     */
    private static final String GENERATED_AT_DISPLAY = DisplayTime.of(GENERATED_AT);

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path content;

    private SiteSources sources;
    private BuildProperties buildProperties;

    @BeforeEach
    void setUp() {
        PublicationProperties publication = new PublicationProperties();
        publication.setUrl("https://doc.example.ch");
        buildProperties = new BuildProperties();
        // Both sites the tests here build, because only a configured site is ever built: the page describing
        // the documentation is linked from the root page, so a run that could not write it fails.
        SiteProperties siteProperties = new SiteProperties();
        siteProperties.getSites().put("default", new SiteProperties.Site());
        siteProperties.getSites().put("governance", new SiteProperties.Site());
        sources = new SiteSources(new SiteUrls(publication, ""), new DefaultResourceLoader(),
                NoArchitectureModel.systemPages(new SiteUrls(publication, "")),
                new DocumentationSites(siteProperties), buildProperties,
                TestProvenance.of(siteProperties, NoArchitectureModel.INSTANCE,
                        new StructureTemplates(List.of())),
                new AboutThisDocumentation());
    }

    /**
     * The worker threads of the static generation are a memory decision of the service, and the template reads
     * it out of {@code site.json} - the child's environment is built from nothing, so there is no other way in.
     */
    @Test
    void write_thenSiteJsonSaysWhetherTheStaticGenerationMayUseWorkerThreads() throws IOException {
        sources.write(1L, siteOf("default"), content, GENERATED_AT);
        assertThat(siteJson().path("ssgWorkerThreads").asBoolean())
                .describedAs("off unless an instance asks for it").isFalse();

        buildProperties.setSsgWorkerThreads(true);
        sources.write(1L, siteOf("default"), content, GENERATED_AT);

        assertThat(siteJson().path("ssgWorkerThreads").asBoolean()).isTrue();
    }

    private JsonNode siteJson() throws IOException {
        return JSON.readTree(Files.readString(content.resolve("site.json"), StandardCharsets.UTF_8));
    }

    /**
     * An environment that reads no architecture model knows nothing about how many systems there are. A zero
     * would say the landscape is empty rather than that it was never looked at.
     */
    @Test
    void write_whenNoArchitectureModelIsRead_thenTheRootPageCountsNoSystems() throws IOException {
        sources.write(1L, siteOf("default"), content, GENERATED_AT);

        String page = Files.readString(content.resolve("prod").resolve("index.md"), StandardCharsets.UTF_8);
        assertThat(page).doesNotContain("| Systems |");
        assertThat(page).describedAs("the rest of the table is still there")
                .contains("| Site |").contains("| Generated |");
    }

    /**
     * The counts travel with the build result to the systems gauge, so they have to say which environments
     * read a model - and only those. An environment that reads none is absent rather than zero.
     */
    @Test
    void write_thenItAnswersHowManySystemsEachEnvironmentThatReadsAModelDocuments() throws IOException {
        Site site = siteOf("default");
        String modelled = site.environments().getFirst().id();
        PublicationProperties publication = new PublicationProperties();
        publication.setUrl("https://doc.example.ch");
        SiteUrls urls = new SiteUrls(publication, "");
        SiteSources withOneModel = new SiteSources(urls, new DefaultResourceLoader(),
                new SystemPages(new OneSystemIn(modelled), NoMessageSchemas.INSTANCE, new StructureTemplates(List.of()),
                        new GeneratorProperties(), new ArchitectureImportProperties(), BuildMetrics.NONE, urls),
                new DocumentationSites(new SiteProperties()), new BuildProperties(),
                TestProvenance.of(new OneSystemIn(modelled)), new AboutThisDocumentation());

        Map<String, EnvironmentModel> models = withOneModel.write(1L, site, content, GENERATED_AT);

        assertThat(models).containsOnlyKeys(modelled);
        assertThat(models.get(modelled).systems()).isEqualTo(1);
        assertThat(sources.write(1L, site, content, GENERATED_AT))
                .describedAs("with no architecture repository at all, no environment reports a count")
                .isEmpty();
    }

    @Test
    void write_thenEachEnvironmentGetsARootPageWithNothingLeftToSubstitute() throws IOException {
        Site site = siteOf("default");

        sources.write(1L, site, content, GENERATED_AT);

        for (SiteEnvironment environment : site.environments()) {
            String page = Files.readString(content.resolve(environment.id()).resolve("index.md"),
                    StandardCharsets.UTF_8);
            assertThat(page)
                    .describedAs("the root page of %s", environment.id())
                    .doesNotContain("{{")
                    .contains("title: \"Documentation\"")
                    .contains("slug: /")
                    .contains("`default`")
                    .contains(environment.label())
                    .contains("| Generated | " + GENERATED_AT_DISPLAY + " |");
        }
    }

    /**
     * The one environment carrying documentation that is not deployed anywhere says so; the others must not.
     */
    @Test
    void write_thenOnlyTheLatestEnvironmentExplainsWhatItCarries() throws IOException {
        Site site = siteOf("default");

        sources.write(1L, site, content, GENERATED_AT);

        String latest = Files.readString(content.resolve("dev").resolve("index.md"), StandardCharsets.UTF_8);
        String other = Files.readString(content.resolve("prod").resolve("index.md"), StandardCharsets.UTF_8);
        assertThat(latest).contains("whether it is deployed anywhere or not");
        assertThat(other).doesNotContain("whether it is deployed anywhere or not");
        assertThat(other).contains("environment.");
    }

    @Test
    void write_thenSiteJsonSaysWhatTheTemplateReads() throws IOException {
        sources.write(1L, siteOf("governance"), content, GENERATED_AT);

        JsonNode site = JSON.readTree(content.resolve("site.json").toFile());
        assertThat(site.get("id").asText()).isEqualTo("governance");
        assertThat(site.get("colorScheme").asText()).isEqualTo("jeap");
        assertThat(site.get("url").asText()).isEqualTo("https://doc.example.ch");
        assertThat(site.get("baseUrl").asText()).describedAs("a named site is served below /site/")
                .isEqualTo("/site/governance/");
        assertThat(site.get("generatedAt").asText()).isEqualTo(GENERATED_AT.toString());
        assertThat(site.get("tagline").asText()).isEmpty();
        // The footer reads these two: the Systems link is written only when the main environment has one, and
        // the Sites group is one entry per site this instance serves, each with an absolute URL.
        assertThat(site.get("hasSystems").asBoolean()).isFalse();
        assertThat(site.get("sites")).isNotEmpty();
        assertThat(site.get("sites").get(0).get("url").asText()).startsWith("https://doc.example.ch");
    }

    /**
     * The instant is the contract field and stays ISO-8601; beside it goes the one form a reader sees, on the
     * root page and in the footer of every generated page. The format is pinned here so that it has a single
     * definition in Java rather than one in every place that prints it.
     * <p>
     * It is the time zone of the service, which is the zone its publication schedules are evaluated in too - so
     * the expectation is derived from the default zone rather than written out, and the assertion below reads
     * the value back to check it really is this instant rather than merely something of the right shape.
     */
    @Test
    void write_thenTheGeneratedTimestampIsAlsoWrittenInAFormAReaderCanRead() throws IOException {
        sources.write(1L, siteOf("default"), content, GENERATED_AT);

        JsonNode site = JSON.readTree(content.resolve("site.json").toFile());
        String display = site.get("generatedAtDisplay").asText();
        // And it names the zone: without a designator the number is unreadable for anybody who does not
        // already know what the container's TZ is.
        assertThat(display)
                .matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} \\S+")
                .isEqualTo(GENERATED_AT_DISPLAY);
        assertThat(LocalDateTime.parse(display.substring(0, 19).replace(' ', 'T')))
                .isEqualTo(LocalDateTime.ofInstant(GENERATED_AT, ZoneId.systemDefault()));

        String page = Files.readString(content.resolve("prod").resolve("index.md"), StandardCharsets.UTF_8);
        // The front matter and the body answer different readers: the instant is for whatever filters the
        // pages, and what a person sees is the same moment written out.
        String frontMatter = page.substring(0, page.indexOf("---", 4));
        String body = page.substring(page.indexOf("---", 4));
        assertThat(frontMatter).contains("doc_generated_at: \"" + GENERATED_AT + "\"");
        assertThat(body)
                .contains("| Generated | " + GENERATED_AT_DISPLAY + " |")
                .doesNotContain(GENERATED_AT.toString());
    }

    @Test
    void write_thenEnvironmentsJsonCarriesEveryEnvironmentInOrder() throws IOException {
        sources.write(1L, siteOf("default"), content, GENERATED_AT);

        JsonNode environments = JSON.readTree(content.resolve("environments.json").toFile()).get("environments");
        assertThat(environments).hasSize(4);
        assertThat(environments.get(0).get("id").asText()).isEqualTo("dev");
        assertThat(environments.get(0).get("latest").asBoolean()).isTrue();
        assertThat(environments.get(0).get("main").asBoolean()).isFalse();
        assertThat(environments.get(3).get("id").asText()).isEqualTo("prod");
        assertThat(environments.get(3).get("main").asBoolean()).isTrue();
        assertThat(environments.get(3).get("short").asText()).isEqualTo("PROD");
    }

    /**
     * A site that brings no branding leaves the fields null, and the template falls back to its own mark. A
     * name pointing at a file nothing wrote would be a broken image on every page.
     */
    @Test
    void write_whenTheSiteBringsNoBranding_thenNoBrandingIsNamedAndNoneIsWritten() throws IOException {
        sources.write(1L, siteOf("default"), content, GENERATED_AT);

        JsonNode site = JSON.readTree(content.resolve("site.json").toFile());
        assertThat(site.get("logo").isNull()).isTrue();
        assertThat(site.get("favicon").isNull()).isTrue();
        assertThat(content.resolve("static").resolve("branding")).doesNotExist();
    }

    @Test
    void write_whenTheSiteBringsOnlyALogo_thenTheFaviconPointsAtTheFileThatWasWritten() throws IOException {
        Path logo = Files.writeString(content.resolveSibling("mark.svg"), "<svg/>", StandardCharsets.UTF_8);
        Site site = new Site("default", "Documentation", null, logo.toUri().toString(), logo.toUri().toString(),
                "jeap", environments(), null, true, true);

        sources.write(1L, site, content, GENERATED_AT);

        JsonNode description = JSON.readTree(content.resolve("site.json").toFile());
        assertThat(description.get("logo").asText()).isEqualTo("branding/logo.svg");
        // The same file, because only one was written - a favicon of its own would name nothing.
        assertThat(description.get("favicon").asText()).isEqualTo("branding/logo.svg");
        // Written under static/, which the site generator adds to its static directories - so it is served at
        // branding/logo.svg without anything landing in the template's own static/img.
        assertThat(content.resolve("static").resolve("branding").resolve("logo.svg")).exists();
    }

    /**
     * The front matter is YAML and a title is free text. 'jEAP: Documentation' is an ordinary thing to call a
     * site and an invalid YAML scalar unquoted - the build would fail minutes later with a js-yaml message
     * naming neither the property nor the site.
     */
    @Test
    void write_whenTheTitleContainsYamlPunctuation_thenTheFrontMatterIsStillValid() throws IOException {
        Site site = new Site("default", "jEAP: Documentation", null, null, null, "jeap", environments(),
                null, true, true);

        sources.write(1L, site, content, GENERATED_AT);

        String page = Files.readString(content.resolve("prod").resolve("index.md"), StandardCharsets.UTF_8);
        assertThat(page).contains("title: \"jEAP: Documentation\"");
        // The heading is Markdown, not YAML, and is left as it was written.
        assertThat(page).contains("# jEAP: Documentation");
    }

    @Test
    void write_whenTheSiteBringsABlankLogo_thenNoBrandingIsNamed() throws IOException {
        Site site = new Site("default", "Documentation", null, "  ", "  ", "jeap", environments(), null, true, true);

        sources.write(1L, site, content, GENERATED_AT);

        JsonNode description = JSON.readTree(content.resolve("site.json").toFile());
        // A name with no file behind it would be truthy in the template and would skip its own default, so
        // every page would carry a broken mark.
        assertThat(description.get("logo").isNull()).isTrue();
        assertThat(description.get("favicon").isNull()).isTrue();
    }

    /**
     * A configured title or tagline is text, and a {@code .md} page is MDX - so markup in one of them would
     * otherwise be an element in the reader's browser, and a stray brace would fail the build. The values are
     * the instance's own configuration rather than anything uploaded, which is what makes this a build hazard
     * first and a security one second; escaping settles both.
     */
    @Test
    void write_whenTheTitleAndTaglineCarryMarkup_thenTheyLandOnThePageAsText() throws IOException {
        Site site = new Site("default", "<script>alert(1)</script>", "Everything about {jme} & more", null, null,
                "jeap", environments(), null, true, true);

        sources.write(1L, site, content, GENERATED_AT);

        String page = Files.readString(content.resolve("prod").resolve("index.md"), StandardCharsets.UTF_8);
        assertThat(bodyOf(page))
                .doesNotContain("<script>")
                .contains("# &lt;script&gt;alert(1)&lt;/script&gt;")
                .contains("Everything about &#123;jme&#125; &amp; more");
        // The front matter is YAML rather than MDX, and stays the JSON scalar it was: Docusaurus puts that
        // title into the browser tab as text, so escaping it the same way would show the references themselves.
        assertThat(page).startsWith("---\ntitle: \"<script>alert(1)</script>\"\n");
    }

    /**
     * The substitutions run one after another over the same page, so a value naming a later placeholder used to
     * be substituted in turn. It cannot be any more: escaping takes the braces out of the body, and the front
     * matter - where a JSON scalar keeps them - is substituted last, after which nothing reads the page again.
     */
    @Test
    void write_whenTheTitleNamesAnotherPlaceholder_thenItIsNotSubstituted() throws IOException {
        Site site = new Site("default", "{{tagline}}", "the tagline", null, null, "jeap", environments(), null,
                true, true);

        sources.write(1L, site, content, GENERATED_AT);

        String page = Files.readString(content.resolve("prod").resolve("index.md"), StandardCharsets.UTF_8);
        assertThat(page)
                .startsWith("---\ntitle: \"{{tagline}}\"\n")
                .contains("# &#123;&#123;tagline&#125;&#125;");
    }

    /**
     * The page without its YAML front matter - the part Docusaurus parses as MDX, and so the only part in which
     * markup from a configured value would become an element rather than stay text.
     */
    private static String bodyOf(String page) {
        int frontMatterEnd = page.indexOf("---", "---".length());
        assertThat(frontMatterEnd).describedAs("the end of the front matter of the page").isNotNegative();
        return page.substring(frontMatterEnd + "---".length());
    }

    private static Site siteOf(String id) {
        return new Site(id, "Documentation", null, null, null, "jeap", environments(), null, true, true);
    }

    private static List<SiteEnvironment> environments() {
        return List.of(
                new SiteEnvironment("dev", "DEV", "Development", 1, false, true),
                new SiteEnvironment("ref", "REF", "Reference", 2, false, false),
                new SiteEnvironment("abn", "ABN", "Acceptance", 3, false, false),
                new SiteEnvironment("prod", "PROD", "Production", 4, true, false));
    }
    /** A landscape of one system, read by exactly one environment. */
    private record OneSystemIn(String environment) implements ArchitectureModelSource {

        @Override
        public boolean isConfiguredFor(String environment) {
            return this.environment.equals(environment);
        }

        @Override
        public Optional<String> sourceUrlOf(String environment) {
            return Optional.of("https://archrepo");
        }

        @Override
        public ArchitectureSnapshot read(String environment) {
            return new ArchitectureSnapshot(
                    ArchitectureModel.of(List.of(new DocumentedSystem("orders", "orders", null, List.of(), null,
                            List.of(), List.of(), List.of()))),
                    GENERATED_AT);
        }

        @Override
        public Optional<Instant> lastSuccessfulImportAt(String environment) {
            return Optional.of(GENERATED_AT);
        }
    }
}
