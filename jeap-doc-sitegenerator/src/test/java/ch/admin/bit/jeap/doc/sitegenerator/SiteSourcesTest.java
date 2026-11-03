package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.DisplayTime;
import ch.admin.bit.jeap.doc.domain.ArchitectureImportProperties;
import ch.admin.bit.jeap.doc.domain.BuildProperties;
import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.PublicationProperties;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.PartKey;
import ch.admin.bit.jeap.doc.domain.SitePart;
import ch.admin.bit.jeap.doc.domain.SiteEnvironment;
import ch.admin.bit.jeap.doc.domain.SiteProperties;
import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportState;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureSnapshot;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ImportOutcome;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource;
import ch.admin.bit.jeap.doc.domain.port.BuildMetrics;
import ch.admin.bit.jeap.doc.domain.custom.CustomProperties;
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
import java.time.Duration;
import java.time.Instant;
import java.util.stream.StreamSupport;
import java.time.LocalDateTime;
import java.time.ZoneId;
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

    /** Any version does: what matters is that both hashes are taken with the same one. */
    private static final String VERSION = "1.0.0";

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
        SiteProperties.Site handbook = new SiteProperties.Site();
        handbook.setPerEnvironment(false);
        handbook.setContent(ch.admin.bit.jeap.doc.domain.content.StaticSiteContent.SOURCE_ID);
        siteProperties.getSites().put("handbook", handbook);
        sources = new SiteSources(new SiteUrls(publication, ""), new DefaultResourceLoader(),
                java.util.List.of(new SystemDocumentationWriter(NoArchitectureModel.systemPages(new SiteUrls(publication, ""))),
                        new StaticSiteWriter(new NoCustomDocumentation(), new NoCustomStorage(),
                                new ch.admin.bit.jeap.doc.domain.custom.CustomProperties())),
                new DocumentationSites(siteProperties),
                new ch.admin.bit.jeap.doc.domain.SystemSitePartition(NO_MODEL, new NoCustomDocumentation()), buildProperties,
                TestProvenance.of(siteProperties, NoArchitectureModel.INSTANCE,
                        new StructureTemplates(List.of())),
                new AboutThisDocumentation(), publication);
    }

    /**
     * The worker threads of the static generation are a memory decision of the service, and the template reads
     * it out of {@code site.json} - the child's environment is built from nothing, so there is no other way in.
     */
    @Test
    void write_thenSiteJsonSaysWhetherTheStaticGenerationMayUseWorkerThreads() throws IOException {
        sources.write(1L, siteOf("default"), wholeSiteOf(siteOf("default")), content, GENERATED_AT);
        assertThat(siteJson().path("ssgWorkerThreads").asBoolean())
                .describedAs("off unless an instance asks for it").isFalse();

        buildProperties.setSsgWorkerThreads(true);
        sources.write(1L, siteOf("default"), wholeSiteOf(siteOf("default")), content, GENERATED_AT);

        assertThat(siteJson().path("ssgWorkerThreads").asBoolean()).isTrue();
    }

    /**
     * <b>The point of taking the status off the page: a part that has not changed is skipped.</b>
     * <p>
     * The architecture repository having been read again is not a change to the documentation, and it used to
     * be one to the content: the page printed <i>last read 09:45</i> and the next occurrence of the import in
     * minutes from now, so the part carrying whole environments hashed differently every hour and was built
     * every hour. Both statements are answered live now, so two runs an hour apart over a landscape nobody
     * touched hash to the same value and the second publishes nothing.
     */
    @Test
    void write_whenOnlyTheLastReadMoved_thenTheContentHashesToWhatItDidAndThePartIsSkipped() throws IOException {
        TestProvenance.InMemoryImports imports = new TestProvenance.InMemoryImports();
        SiteSources documented = sourcesReadingAModel(imports);
        Site site = siteOf("default");

        imports.save(readAt(GENERATED_AT.minus(Duration.ofMinutes(50))));
        WrittenContent first = documented.write(1L, site, wholeSiteOf(site), content, GENERATED_AT);
        String published = ContentDigest.of(content, first.volatileTimestamps(), VERSION);

        // An hour later: another build, another moment, and the repository read once more in between.
        Instant anHourLater = GENERATED_AT.plus(Duration.ofHours(1));
        imports.save(readAt(anHourLater.minus(Duration.ofMinutes(15))));
        WrittenContent second = documented.write(2L, site, wholeSiteOf(site), content, anHourLater);

        assertThat(ContentDigest.of(content, second.volatileTimestamps(), VERSION)).isEqualTo(published);
    }

    /** A model import of the environment prod that succeeded at the given moment. */
    private static ArchitectureImportState readAt(Instant lastSuccessAt) {
        return new ArchitectureImportState("prod", ArchitectureImportKind.MODEL, "hash", null, true, 1,
                lastSuccessAt, lastSuccessAt, ImportOutcome.UNCHANGED, null);
    }

    /** The same sources over an environment whose architecture model is configured, which is what has a schedule. */
    private SiteSources sourcesReadingAModel(TestProvenance.InMemoryImports imports) {
        PublicationProperties publication = new PublicationProperties();
        publication.setUrl("https://doc.example.ch");
        SiteProperties siteProperties = new SiteProperties();
        siteProperties.getSites().put("default", new SiteProperties.Site());
        ArchitectureModelSource configured = new NoArchitectureModel() {
            @Override
            public boolean isConfiguredFor(String environment) {
                return true;
            }
        };
        return new SiteSources(new SiteUrls(publication, ""), new DefaultResourceLoader(),
                java.util.List.of(new SystemDocumentationWriter(NoArchitectureModel.systemPages(new SiteUrls(publication, ""))),
                        new StaticSiteWriter(new NoCustomDocumentation(), new NoCustomStorage(),
                                new ch.admin.bit.jeap.doc.domain.custom.CustomProperties())),
                new DocumentationSites(siteProperties),
                new ch.admin.bit.jeap.doc.domain.SystemSitePartition(NO_MODEL, new NoCustomDocumentation()), buildProperties,
                TestProvenance.of(siteProperties, configured, new StructureTemplates(List.of()), imports),
                new AboutThisDocumentation(), new PublicationProperties());
    }

    /**
     * <b>A part's content must not change when a system it does not document appears.</b>
     * {@code environments.json} is inside the hashed content, and the whole landscape's system list used to
     * be written into every part - so one system arriving rebuilt all of them, which is the one thing the
     * digest exists to prevent.
     */
    @Test
    void write_whenAnotherSystemAppears_thenAPartThatDoesNotDocumentItHashesToWhatItDid() throws IOException {
        Site site = siteOf("default");
        Path before = Files.createDirectories(content.resolve("before"));
        Path after = Files.createDirectories(content.resolve("after"));

        WrittenContent first = sourcesReading(new LandscapeIn("dev", List.of("orders")))
                .write(1L, site, systemPartOf(site, "orders"), before, GENERATED_AT);
        WrittenContent second = sourcesReading(new LandscapeIn("dev", List.of("orders", "shipping")))
                .write(1L, site, systemPartOf(site, "orders"), after, GENERATED_AT);

        assertThat(ContentDigest.of(after, second.volatileTimestamps(), VERSION))
                .describedAs("the content of the part carrying orders")
                .isEqualTo(ContentDigest.of(before, first.volatileTimestamps(), VERSION));
    }

    /**
     * <b>One build sees one landscape.</b> The systems index names the systems this run read, and the parts
     * a link is compared against used to be asked of the model again - so a system removed in between was
     * claimed by no part, its link stayed inside the broken-link check, and the build failed on a route that
     * never existed.
     */
    @Test
    void write_whenTheModelNoLongerKnowsASystemTheRunRead_thenItsLinkStillLeavesTheCheck() throws IOException {
        Site site = siteOf("default");
        // The landscape this run reads has orders in it; the partition, which asks the model, no longer does.
        SiteSources withOneModel = sourcesReadingOneModelIn("dev");

        withOneModel.write(1L, site, shellOf(site), content, GENERATED_AT);

        assertThat(Files.readString(content.resolve("dev/systems/index.md"), StandardCharsets.UTF_8))
                .describedAs("the index's link to the part that carries orders")
                .contains("](pathname:///dev/systems/orders/)");
    }

    /** A generator whose landscape is one system, read by the one environment named. */
    private static SiteSources sourcesReadingOneModelIn(String modelled) {
        PublicationProperties publication = new PublicationProperties();
        publication.setUrl("https://doc.example.ch");
        SiteUrls urls = new SiteUrls(publication, "");
        return new SiteSources(urls, new DefaultResourceLoader(),
                java.util.List.of(new SystemDocumentationWriter(new SystemPages(new OneSystemIn(modelled), NoMessageSchemas.INSTANCE,
                        NoArchitectureArtifacts.INSTANCE, NoArchitectureArtifacts.INSTANCE,
                        NoReactions.INSTANCE, NoReactions.INSTANCE,
                        new NoCustomDocumentation(), NoCustomStorage.INSTANCE, new CustomProperties(),
                        new StructureTemplates(List.of()), new GeneratorProperties(),
                        new ArchitectureImportProperties(), BuildMetrics.NONE, urls)),
                        new StaticSiteWriter(new NoCustomDocumentation(), new NoCustomStorage(),
                                new ch.admin.bit.jeap.doc.domain.custom.CustomProperties())),
                new DocumentationSites(new SiteProperties()),
                new ch.admin.bit.jeap.doc.domain.SystemSitePartition(NO_MODEL, new NoCustomDocumentation()), new BuildProperties(),
                TestProvenance.of(new OneSystemIn(modelled)), new AboutThisDocumentation(), new PublicationProperties());
    }

    /** A generator over the given landscape, with the partition reading the same one. */
    private static SiteSources sourcesReading(ArchitectureModelSource landscape) {
        PublicationProperties publication = new PublicationProperties();
        publication.setUrl("https://doc.example.ch");
        SiteUrls urls = new SiteUrls(publication, "");
        return new SiteSources(urls, new DefaultResourceLoader(),
                java.util.List.of(new SystemDocumentationWriter(new SystemPages(landscape, NoMessageSchemas.INSTANCE, NoArchitectureArtifacts.INSTANCE,
                        NoArchitectureArtifacts.INSTANCE, NoReactions.INSTANCE, NoReactions.INSTANCE,
                new NoCustomDocumentation(), NoCustomStorage.INSTANCE, new CustomProperties(),
                new StructureTemplates(List.of()),
                        new GeneratorProperties(), new ArchitectureImportProperties(), BuildMetrics.NONE, urls)),
                        new StaticSiteWriter(new NoCustomDocumentation(), new NoCustomStorage(),
                                new ch.admin.bit.jeap.doc.domain.custom.CustomProperties())),
                new DocumentationSites(new SiteProperties()),
                new ch.admin.bit.jeap.doc.domain.SystemSitePartition(landscape, new NoCustomDocumentation()), new BuildProperties(),
                TestProvenance.of(landscape), new AboutThisDocumentation(), new PublicationProperties());
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
        sources.write(1L, siteOf("default"), wholeSiteOf(siteOf("default")), content, GENERATED_AT);

        String page = Files.readString(content.resolve("prod").resolve("index.md"), StandardCharsets.UTF_8);
        assertThat(page).doesNotContain("| Systems |")
                .describedAs("the rest of the table is still there")
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
        SiteSources withOneModel = sourcesReadingOneModelIn(modelled);

        Map<String, EnvironmentModel> models =
                withOneModel.write(1L, site, wholeSiteOf(site), content, GENERATED_AT).models();

        assertThat(models).containsOnlyKeys(modelled);
        assertThat(models.get(modelled).systemCount()).isEqualTo(1);
        // And the systems themselves reach environments.json, which is the only thing that can name them for
        // the shell's sidebar: each of them is built as a part of its own.
        JsonNode environments = JSON.readTree(content.resolve("environments.json").toFile())
                .get("environments");
        JsonNode modelledEnvironment = StreamSupport.stream(environments.spliterator(), false)
                .filter(environment -> environment.get("id").asString().equals(modelled))
                .findFirst().orElseThrow();
        assertThat(modelledEnvironment.get("systems")).hasSize(1);
        assertThat(modelledEnvironment.get("systems").get(0).get("label").asString()).isEqualTo("orders");
        assertThat(modelledEnvironment.get("systems").get(0).get("path").asString())
                .isEqualTo("/systems/orders/");
        assertThat(sources.write(1L, site, wholeSiteOf(site), content, GENERATED_AT).models())
                .describedAs("with no architecture repository at all, no environment reports a count")
                .isEmpty();
    }

    @Test
    void write_thenEachEnvironmentGetsARootPageWithNothingLeftToSubstitute() throws IOException {
        Site site = siteOf("default");

        sources.write(1L, site, wholeSiteOf(site), content, GENERATED_AT);

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

        sources.write(1L, site, wholeSiteOf(site), content, GENERATED_AT);

        String latest = Files.readString(content.resolve("dev").resolve("index.md"), StandardCharsets.UTF_8);
        String other = Files.readString(content.resolve("prod").resolve("index.md"), StandardCharsets.UTF_8);
        assertThat(latest).contains("whether it is deployed anywhere or not");
        assertThat(other).doesNotContain("whether it is deployed anywhere or not")
                .contains("environment.");
    }

    @Test
    void write_thenSiteJsonSaysWhatTheTemplateReads() throws IOException {
        sources.write(1L, siteOf("governance"), wholeSiteOf(siteOf("governance")), content, GENERATED_AT);

        JsonNode site = JSON.readTree(content.resolve("site.json").toFile());
        assertThat(site.get("id").asString()).isEqualTo("governance");
        assertThat(site.get("colorScheme").asString()).isEqualTo("jeap");
        assertThat(site.get("url").asString()).isEqualTo("https://doc.example.ch");
        assertThat(site.get("baseUrl").asString()).describedAs("a named site is served below /site/")
                .isEqualTo("/site/governance/");
        assertThat(site.get("generatedAt").asString()).isEqualTo(GENERATED_AT.toString());
        assertThat(site.get("tagline").asString()).isEmpty();
        // The footer reads these two: the Systems link is written only when the main environment has one, and
        // the Sites group is one entry per site this instance serves, each with an absolute URL.
        assertThat(site.get("hasSystems").asBoolean()).isFalse();
        assertThat(site.get("sites")).isNotEmpty();
        assertThat(site.get("sites").get(0).get("url").asString()).startsWith("https://doc.example.ch");
        // What the template branches on, and it is not the part's identity: whether this build wrote the
        // site's own pages is what says whether the navbar and the footer may link to them as routes of its
        // own. The two coincide only for a partition whose parts are systems.
        JsonNode part = site.get("part");
        assertThat(part.get("id").asString()).isEqualTo("shell");
        assertThat(part.get("carriesWholeEnvironments").asBoolean()).isTrue();
        assertThat(part.get("shell").asBoolean()).isTrue();
        assertThat(part.get("environments")).isNotEmpty();
    }

    /**
     * A site without environments is one tree, written where the template mounts it at the site root. Its pages
     * name no environment, and the template is told there is none to switch between.
     */
    @Test
    void write_whenTheSiteHasNoEnvironments_thenItsOwnPagesGoIntoTheOneTreeAndNameNoEnvironment()
            throws IOException {
        Site site = new Site("handbook", "Handbook", null, null, null, "jeap", List.of(), true, false, 1, null,
                ch.admin.bit.jeap.doc.domain.content.StaticSiteContent.SOURCE_ID);

        sources.write(1L, site, wholeSiteOf(site), content, GENERATED_AT);

        Path tree = content.resolve(SiteSources.SINGLE_TREE);
        String root = Files.readString(tree.resolve("index.md"), StandardCharsets.UTF_8);
        assertThat(root).doesNotContain("{{").doesNotContain("doc_environment")
                .doesNotContain("| Environment |").doesNotContain("It shows the")
                .contains("slug: /")
                .contains("This documentation is generated by the jEAP Doc Service.\n")
                .contains("| Site | `handbook` |\n| Generated | " + GENERATED_AT_DISPLAY + " |");
        String about = Files.readString(tree.resolve(AboutThisDocumentation.FILE_NAME), StandardCharsets.UTF_8);
        assertThat(about).doesNotContain("doc_environment").doesNotContain("| Environment |")
                .doesNotContain("The environments of this site")
                .describedAs("no model is read and no import publishes the site")
                .doesNotContain("Waits for the architecture model")
                .doesNotContain("The architecture model is imported");
        assertThat(JSON.readTree(content.resolve("environments.json").toFile()).get("environments")).isEmpty();
        assertThat(siteJson().get("perEnvironment")).isNotNull();
        assertThat(siteJson().get("perEnvironment").asBoolean()).isFalse();
        assertThat(siteJson().get("baseUrl").asString()).isEqualTo("/site/handbook/");
        try (var entries = Files.list(content)) {
            assertThat(entries.filter(Files::isDirectory).map(entry -> entry.getFileName().toString()))
                    .describedAs("no environment tree beside the one tree")
                    .containsExactly(SiteSources.SINGLE_TREE);
        }
    }

    /**
     * {@code site.json} is part of what a part's content hashes to, so a site with environments must not gain a
     * key: every part of every existing instance would be rebuilt by the release.
     */
    @Test
    void write_whenTheSiteHasEnvironments_thenSiteJsonDoesNotSayItHas() throws IOException {
        sources.write(1L, siteOf("default"), wholeSiteOf(siteOf("default")), content, GENERATED_AT);

        assertThat(siteJson().has("perEnvironment")).isFalse();
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
        sources.write(1L, siteOf("default"), wholeSiteOf(siteOf("default")), content, GENERATED_AT);

        JsonNode site = JSON.readTree(content.resolve("site.json").toFile());
        String display = site.get("generatedAtDisplay").asString();
        assertThat(display)
                .matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")
                .isEqualTo(GENERATED_AT_DISPLAY);
        assertThat(LocalDateTime.parse(display.substring(0, 19).replace(' ', 'T')))
                .isEqualTo(LocalDateTime.ofInstant(GENERATED_AT, ZoneId.systemDefault()));

        String page = Files.readString(content.resolve("prod").resolve("index.md"), StandardCharsets.UTF_8);
        // The front matter and the body answer different readers: the instant is for whatever filters the
        // pages, and what a person sees is the same moment written out.
        String frontMatter = page.substring(0, page.indexOf("---", 4));
        String body = page.substring(page.indexOf("---", 4));
        assertThat(frontMatter)
                .contains("doc_generated_at: \"" + GENERATED_AT + "\"")
                // The provenance block under the page reads this one, and falls back to the instant without it.
                .contains("doc_generated_at_display: \"" + GENERATED_AT_DISPLAY + "\"");
        assertThat(body)
                .contains("| Generated | " + GENERATED_AT_DISPLAY + " |")
                .doesNotContain(GENERATED_AT.toString());
    }

    @Test
    void write_thenEnvironmentsJsonCarriesEveryEnvironmentInOrder() throws IOException {
        sources.write(1L, siteOf("default"), wholeSiteOf(siteOf("default")), content, GENERATED_AT);

        JsonNode environments = JSON.readTree(content.resolve("environments.json").toFile()).get("environments");
        assertThat(environments).hasSize(4);
        assertThat(environments.get(0).get("id").asString()).isEqualTo("dev");
        assertThat(environments.get(0).get("latest").asBoolean()).isTrue();
        assertThat(environments.get(0).get("main").asBoolean()).isFalse();
        assertThat(environments.get(3).get("id").asString()).isEqualTo("prod");
        assertThat(environments.get(3).get("main").asBoolean()).isTrue();
        assertThat(environments.get(3).get("short").asString()).isEqualTo("PROD");
    }

    /**
     * Per environment, because a part's way out of itself links to the systems index of the environment the
     * reader is in. Site-wide it was only ever the main environment's answer, and a reader in the dev tree
     * clicking "All systems" landed in the main one - and where no environment has an index, a link to one is
     * a 404 that no build catches, because {@code pathname://} is outside the broken-link check.
     */
    @Test
    void write_thenEnvironmentsJsonSaysWhichOfThemHasASystemsIndex() throws IOException {
        Site site = siteOf("default");
        String modelled = site.environments().getFirst().id();
        SiteSources withOneModel = sourcesReadingOneModelIn(modelled);

        withOneModel.write(1L, site, wholeSiteOf(site), content, GENERATED_AT);

        JsonNode environments = JSON.readTree(content.resolve("environments.json").toFile())
                .get("environments");
        assertThat(environments.get(0).get("id").asString()).isEqualTo(modelled);
        assertThat(environments.get(0).get("hasSystems").asBoolean())
                .describedAs("the one environment whose landscape has a system in it").isTrue();
        assertThat(environments.get(3).get("hasSystems").asBoolean())
                .describedAs("and an environment that reads no model has no index either").isFalse();
    }

    /** With no architecture repository at all, no environment has a systems index. */
    @Test
    void write_whenNoArchitectureModelIsRead_thenNoEnvironmentHasASystemsIndex() throws IOException {
        Site site = siteOf("default");

        sources.write(1L, site, wholeSiteOf(site), content, GENERATED_AT);

        JsonNode environments = JSON.readTree(content.resolve("environments.json").toFile())
                .get("environments");
        for (JsonNode environment : environments) {
            assertThat(environment.get("hasSystems").asBoolean())
                    .describedAs("hasSystems of %s", environment.get("id").asString()).isFalse();
        }
    }

    /**
     * A site that brings no branding leaves the fields null, and the template falls back to its own mark. A
     * name pointing at a file nothing wrote would be a broken image on every page.
     */
    @Test
    void write_whenTheSiteBringsNoBranding_thenNoBrandingIsNamedAndNoneIsWritten() throws IOException {
        sources.write(1L, siteOf("default"), wholeSiteOf(siteOf("default")), content, GENERATED_AT);

        JsonNode site = JSON.readTree(content.resolve("site.json").toFile());
        assertThat(site.get("logo").isNull()).isTrue();
        assertThat(site.get("favicon").isNull()).isTrue();
        assertThat(content.resolve("static").resolve("branding")).doesNotExist();
    }

    @Test
    void write_whenTheSiteBringsOnlyALogo_thenTheFaviconPointsAtTheFileThatWasWritten() throws IOException {
        Path logo = Files.writeString(content.resolveSibling("mark.svg"), "<svg/>", StandardCharsets.UTF_8);
        Site site = new Site("default", "Documentation", null, logo.toUri().toString(), logo.toUri().toString(),
                "jeap", environments(), true, true);

        sources.write(1L, site, wholeSiteOf(site), content, GENERATED_AT);

        JsonNode description = JSON.readTree(content.resolve("site.json").toFile());
        assertThat(description.get("logo").asString()).isEqualTo("branding/logo.svg");
        // The same file, because only one was written - a favicon of its own would name nothing.
        assertThat(description.get("favicon").asString()).isEqualTo("branding/logo.svg");
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
                true, true);

        sources.write(1L, site, wholeSiteOf(site), content, GENERATED_AT);

        String page = Files.readString(content.resolve("prod").resolve("index.md"), StandardCharsets.UTF_8);
        assertThat(page).contains("title: \"jEAP: Documentation\"")
                // The heading is Markdown, not YAML, and is left as it was written.
                .contains("# jEAP: Documentation");
    }

    @Test
    void write_whenTheSiteBringsABlankLogo_thenNoBrandingIsNamed() throws IOException {
        Site site = new Site("default", "Documentation", null, "  ", "  ", "jeap", environments(), true, true);

        sources.write(1L, site, wholeSiteOf(site), content, GENERATED_AT);

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
                "jeap", environments(), true, true);

        sources.write(1L, site, wholeSiteOf(site), content, GENERATED_AT);

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
        Site site = new Site("default", "{{tagline}}", "the tagline", null, null, "jeap", environments(),
                true, true);

        sources.write(1L, site, wholeSiteOf(site), content, GENERATED_AT);

        String page = Files.readString(content.resolve("prod").resolve("index.md"), StandardCharsets.UTF_8);
        assertThat(page)
                .startsWith("---\ntitle: \"{{tagline}}\"\n")
                .contains("# &#123;&#123;tagline&#125;&#125;");
    }

    /**
     * An instance of several sites: every site shows the same title beside its logo - the default site's, where
     * none is configured - and a tab per site in configuration order, labelled with its title and marked where
     * the reader is. Each tab leads to the root of its site.
     */
    @Test
    void write_whenTheInstanceServesSeveralSites_thenEverySiteCarriesTheTitleAndATabPerSite() throws IOException {
        Site governance = new Site("governance", "Governance", null, null, null, "jeap", environments(), true, true);

        sources.write(1L, governance, wholeSiteOf(governance), content, GENERATED_AT);

        JsonNode navigation = siteJson().get("navigation");
        assertThat(navigation.get("title").asString()).describedAs("the default site's title").isEqualTo("Documentation");
        assertThat(navigation.get("tabs")).hasSize(3);
        assertThat(StreamSupport.stream(navigation.get("tabs").spliterator(), false)
                .map(tab -> tab.get("label").asString() + " " + tab.get("url").asString() + " "
                            + tab.get("current").asBoolean()))
                .containsExactly("Documentation / false", "governance /site/governance/ true",
                        "handbook /site/handbook/ false");
        assertThat(siteJson().get("title").asString()).describedAs("the site keeps its own title for its pages")
                .isEqualTo("Governance");
    }

    /** A configured title and navigation labels are what the header bar shows. */
    @Test
    void write_whenATitleAndLabelsAreConfigured_thenTheHeaderBarShowsThem() throws IOException {
        SiteProperties configured = new SiteProperties();
        SiteProperties.Site architecture = new SiteProperties.Site();
        architecture.setTitle("System Documentation");
        architecture.setNavigationLabel("Architecture");
        configured.getSites().put(Site.DEFAULT_SITE, architecture);
        SiteProperties.Site deployments = new SiteProperties.Site();
        deployments.setTitle("Deployments");
        configured.getSites().put("deployments", deployments);
        PublicationProperties publication = publication();
        publication.setTitle("Example Documentation");
        DocumentationSites sites = new DocumentationSites(configured);

        sourcesOf(configured, publication).write(1L, sites.find("deployments").orElseThrow(),
                wholeSiteOf(sites.find("deployments").orElseThrow()), content, GENERATED_AT);

        JsonNode navigation = siteJson().get("navigation");
        assertThat(navigation.get("title").asString()).isEqualTo("Example Documentation");
        assertThat(StreamSupport.stream(navigation.get("tabs").spliterator(), false)
                .map(tab -> tab.get("label").asString()))
                .describedAs("a label where one is configured, the title where it is not")
                .containsExactly("Architecture", "Deployments");
    }

    /**
     * <b>A single site without a configured title gains nothing</b>: {@code site.json} is part of what its parts
     * hash to, so writing the navigation would rebuild every part of every such instance with the release.
     */
    @Test
    void write_whenTheInstanceServesOneSiteAndNoTitleIsConfigured_thenSiteJsonHasNoNavigation()
            throws IOException {
        SiteProperties configured = new SiteProperties();
        configured.getSites().put(Site.DEFAULT_SITE, new SiteProperties.Site());

        sourcesOf(configured, publication()).write(1L, siteOf(Site.DEFAULT_SITE),
                wholeSiteOf(siteOf(Site.DEFAULT_SITE)), content, GENERATED_AT);

        assertThat(siteJson().has("navigation")).isFalse();
    }

    /** One site with a title of its own shows it beside its logo, and still has no tabs. */
    @Test
    void write_whenTheInstanceServesOneSiteWithAConfiguredTitle_thenItHasTheTitleAndNoTabs() throws IOException {
        SiteProperties configured = new SiteProperties();
        configured.getSites().put(Site.DEFAULT_SITE, new SiteProperties.Site());
        PublicationProperties publication = publication();
        publication.setTitle("Example Documentation");

        sourcesOf(configured, publication).write(1L, siteOf(Site.DEFAULT_SITE),
                wholeSiteOf(siteOf(Site.DEFAULT_SITE)), content, GENERATED_AT);

        assertThat(siteJson().get("navigation").get("title").asString()).isEqualTo("Example Documentation");
        assertThat(siteJson().get("navigation").get("tabs")).isEmpty();
    }

    private static PublicationProperties publication() {
        PublicationProperties publication = new PublicationProperties();
        publication.setUrl("https://doc.example.ch");
        return publication;
    }

    private SiteSources sourcesOf(SiteProperties configured, PublicationProperties publication) {
        return new SiteSources(new SiteUrls(publication, ""), new DefaultResourceLoader(),
                java.util.List.of(new SystemDocumentationWriter(NoArchitectureModel.systemPages(new SiteUrls(publication, ""))),
                        new StaticSiteWriter(new NoCustomDocumentation(), new NoCustomStorage(),
                                new ch.admin.bit.jeap.doc.domain.custom.CustomProperties())), new DocumentationSites(configured),
                new ch.admin.bit.jeap.doc.domain.SystemSitePartition(NO_MODEL, new NoCustomDocumentation()),
                buildProperties, TestProvenance.of(configured, NoArchitectureModel.INSTANCE,
                        new StructureTemplates(List.of())),
                new AboutThisDocumentation(), publication);
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
        return new Site(id, "Documentation", null, null, null, "jeap", environments(), true, true);
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
        public java.util.List<String> systemSlugsOf(String environment) {
            return read(environment).model().systems().stream()
                    .map(ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem::slug).sorted().toList();
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

    /** A landscape of the named systems, read by exactly one environment. */
    private record LandscapeIn(String environment, List<String> systems) implements ArchitectureModelSource {

        @Override
        public boolean isConfiguredFor(String environment) {
            return this.environment.equals(environment);
        }

        @Override
        public Optional<String> sourceUrlOf(String environment) {
            return Optional.of("https://archrepo");
        }

        @Override
        public List<String> systemSlugsOf(String environment) {
            return systems.stream().sorted().toList();
        }

        @Override
        public ArchitectureSnapshot read(String environment) {
            return new ArchitectureSnapshot(ArchitectureModel.of(systems.stream()
                    .map(system -> new DocumentedSystem(system, system, null, List.of(), null, List.of(),
                            List.of(), List.of()))
                    .toList()), GENERATED_AT);
        }

        @Override
        public Optional<Instant> lastSuccessfulImportAt(String environment) {
            return Optional.of(GENERATED_AT);
        }
    }

    /** The shell: it carries every environment's tree and writes the site's own pages, but no system. */
    private static SitePart shellOf(Site site) {
        return new SitePart(PartKey.shellOf(site.id()), "the site itself", "", false,
                site.environments().stream().map(SiteEnvironment::id).toList(), List.of());
    }

    /** The part carrying one system, in every environment of the site - what the partition produces. */
    private static SitePart systemPartOf(Site site, String slug) {
        return new SitePart(PartKey.of(site.id(), "system-" + slug), "the system " + slug, "systems/" + slug,
                true, site.environments().stream().map(SiteEnvironment::id).toList(),
                site.environments().stream()
                        .map(environment -> environment.routePrefix() + "/systems/" + slug + "/").toList());
    }

    /**
     * The part that carries the whole site: every environment and every system of it. It is what a site cut
     * into one part looks like, and what these cases are about - which pages exist, not which part they are
     * in.
     */
    private static SitePart wholeSiteOf(Site site) {
        return new SitePart(PartKey.shellOf(site.id()), "the site itself", "", true,
                site.environments().stream().map(SiteEnvironment::id).toList(), List.of());
    }

    /**
     * A model source that knows no system, so a site has one part: its shell. What the parts of a site are is
     * SystemSitePartitionTest's business; here they only have to exist.
     */
    private static final ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource NO_MODEL =
            new ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource() {

                @Override
                public boolean isConfiguredFor(String environment) {
                    return false;
                }

                @Override
                public java.util.Optional<String> sourceUrlOf(String environment) {
                    return java.util.Optional.empty();
                }

                @Override
                public java.util.Optional<java.time.Instant> lastSuccessfulImportAt(String environment) {
                    return java.util.Optional.empty();
                }

                @Override
                public ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureSnapshot read(
                        String environment) {
                    return ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureSnapshot.empty();
                }

                @Override
                public java.util.List<String> systemSlugsOf(String environment) {
                    return java.util.List.of();
                }
            };
}
