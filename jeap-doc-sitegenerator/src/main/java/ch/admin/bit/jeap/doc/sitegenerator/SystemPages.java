package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.architecture.view.WhiteboxView;
import ch.admin.bit.jeap.doc.domain.ArchitectureImportProperties;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SiteEnvironment;
import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureSnapshot;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessage;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessageVersion;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.architecture.MessageVersionSchemas;
import ch.admin.bit.jeap.doc.domain.architecture.Team;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource;
import ch.admin.bit.jeap.doc.domain.port.BuildMetrics;
import ch.admin.bit.jeap.doc.domain.port.MessageSchemaRepository;
import ch.admin.bit.jeap.doc.domain.template.DocumentationPaths;
import ch.admin.bit.jeap.doc.domain.template.GenerationContext;
import ch.admin.bit.jeap.doc.domain.template.StructureTemplate;
import ch.admin.bit.jeap.doc.domain.template.StructureTemplates;
import ch.admin.bit.jeap.doc.markdown.CategoryFile;
import ch.admin.bit.jeap.doc.markdown.Markdown;
import ch.admin.bit.jeap.doc.markdown.MarkdownWriter;
import ch.admin.bit.jeap.doc.markdown.Md;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static ch.admin.bit.jeap.doc.markdown.FrontMatter.frontMatter;

/**
 * The pages that are the same whichever structure template a system carries: the list of systems, and the
 * landing page of each of them.
 * <p>
 * They belong here and not to a template. They are the doc service's own index pages, and a system's landing
 * page lists every structure the system carries, which no single template knows.
 * <p>
 * Below each landing page, every registered template writes its own subtree. This class names none of them.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemPages {

    private final ArchitectureModelSource architectureModel;

    /**
     * The replicated Avro schemas, read <b>per system</b> - see {@link #withSchemas}. Not part of the model
     * the landscape read returns, deliberately.
     */
    private final MessageSchemaRepository messageSchemas;
    private final StructureTemplates templates;
    private final GeneratorProperties properties;
    private final ArchitectureImportProperties importProperties;
    private final BuildMetrics metrics;
    private final SiteUrls urls;

    /** What the systems index is called, in its category file, its front matter and its heading. */
    private static final String SYSTEMS_LABEL = "Systems";

    /**
     * Writes the documentation of every system of one environment, and reports how many there were - or
     * nothing at all when the environment reads no architecture model.
     * <p>
     * An environment with no architecture repository writes nothing, not even an empty index. An empty index
     * would say the landscape is empty rather than that it was not read.
     */
    public Optional<EnvironmentModel> write(Site site, SiteEnvironment environment,
                                           Path environmentDirectory, Instant generatedAt) throws IOException {
        return write(site.id(), environment.id(), diagramLinkPrefixOf(site, environment), environmentDirectory,
                generatedAt);
    }

    /**
     * Says so when the architecture repository of this environment has not been read for longer than the import
     * schedule should leave it.
     * <p>
     * <b>The last successful import and not the age of the content.</b> A landscape nobody has changed for a
     * month is not stale - the import has been reading it all along and writing nothing, which is what it is
     * meant to do. What this warns about is an import that has stopped working.
     * <p>
     * The build goes on: a site published from a model of yesterday is worth more than no site. What the age
     * means for an operator is the staleness gauge; this is the line that names it in the build's own log.
     */
    private void warnWhenTheImportIsBehind(String environment, Instant generatedAt) {
        architectureModel.lastSuccessfulImportAt(environment).ifPresent(lastSuccess -> {
            Duration age = Duration.between(lastSuccess, generatedAt);
            if (age.compareTo(importProperties.getStaleAfter()) > 0) {
                log.warn("The architecture repository of the environment {} was last read successfully {} ago, "
                         + "which is more than {}. The documentation is generated from what was imported then, "
                         + "all the same; check whether the import is still running.",
                        environment, age, importProperties.getStaleAfter());
            }
        });
    }

    /**
     * What a link inside a diagram has to start with: the base URL of the site, then the environment prefix.
     * A Markdown link gets both added for it.
     */
    private String diagramLinkPrefixOf(Site site, SiteEnvironment environment) {
        return urls.baseUrl(site) + (environment.main() ? "" : environment.id() + "/");
    }

    Optional<EnvironmentModel> write(String site, String environment, String diagramLinkPrefix,
                                     Path environmentDirectory, Instant generatedAt) throws IOException {
        if (!architectureModel.isConfiguredFor(environment)) {
            log.debug("No architecture repository is configured for the environment {}; no system "
                      + "documentation is generated into it.", environment);
            // Not the same as none: an environment that reads no model has nothing to say about how many
            // systems there are, and the root page must not claim there are zero.
            return Optional.empty();
        }
        long startedAt = System.nanoTime();
        // One call, so that the landscape and the import it came from are one moment. Reading them separately
        // let a page name an import its content did not come from - see ArchitectureSnapshot. It reads what
        // the import stored and never throws, so there is no second outcome to time.
        ArchitectureSnapshot snapshot = architectureModel.read(environment);
        // Only the timing. How many systems there were is the build result's to carry, and it is reported when
        // the build is published - a failure between here and there must not move that gauge.
        metrics.modelRead(site, environment, elapsedSince(startedAt));
        ArchitectureModel model = snapshot.model();
        if (model.isEmpty()) {
            log.warn("The architecture repository of the environment {} reports no system at all. Nothing is "
                     + "generated into that tree.", environment);
            return Optional.of(EnvironmentModel.empty(snapshot.importedAt()));
        }
        warnWhenTheImportIsBehind(environment, generatedAt);
        GenerationContext context = new GenerationContext(model, environment,
                architectureModel.sourceUrlOf(environment).orElse(""),
                snapshot.importedAt(), generatedAt,
                properties.getMaxDiagramNodes(), properties.getMaxEdgeLabels(), diagramLinkPrefix);

        Path systems = environmentDirectory.resolve(DocumentationPaths.SYSTEMS_SEGMENT);
        Files.createDirectories(systems);
        Files.writeString(systems.resolve(CategoryFile.NAME), CategoryFile.of(SYSTEMS_LABEL, 1),
                StandardCharsets.UTF_8);
        writeIndex(model, context, systems);

        for (DocumentedSystem documented : model.systems()) {
            // The schemas of this system, and only this system: the renderings of a whole landscape have no
            // business being held while the site generator runs for minutes afterwards.
            DocumentedSystem system = withSchemas(documented, environment);
            Path directory = systems.resolve(system.slug());
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(CategoryFile.NAME), CategoryFile.of(system.name()),
                    StandardCharsets.UTF_8);
            // The templates first, so the landing page links only to the subtrees that exist. A template is
            // allowed to write nothing, and a link to a page nothing wrote fails the whole site build.
            List<StructureTemplate> written = new ArrayList<>();
            for (StructureTemplate template : templates.all()) {
                template.writeSystem(system, context, directory);
                if (Files.isDirectory(directory.resolve(template.systemPathSegment()))) {
                    written.add(template);
                }
            }
            writeLandingPage(system, context, directory, written);
        }
        log.info("Generated the documentation of {} systems into the {} tree.",
                model.systems().size(), environment);
        // Counted off the landscape this run has just generated from, so that the page describing the
        // documentation says what is in it without asking the database again.
        return Optional.of(new EnvironmentModel(model.systems().size(),
                countOf(model, system -> system.components().size()),
                countOf(model, system -> system.messages().size()),
                snapshot.importedAt()));
    }

    private static int countOf(ArchitectureModel model, java.util.function.ToIntFunction<DocumentedSystem> of) {
        return model.systems().stream().mapToInt(of).sum();
    }

    /**
     * The same system with the replicated schemas joined onto its message versions.
     * <p>
     * <b>Read here rather than with the model</b>, for two reasons. The renderings are the largest text this
     * service stores and the model a build holds stays in memory until the site generator has finished, so a
     * landscape's worth of them would be held for minutes for the sake of a few pages. And they do not need the
     * model's snapshot: a schema row is replaced whole or not at all, so there is nothing a concurrent import
     * could tear.
     * <p>
     * A version with nothing replicated - new, or missed by a run that hit its deadline - is left exactly as it
     * was. It keeps its place on the page and simply carries no schema, which is why a replication that is
     * behind never costs a page.
     * <p>
     * <b>Matched ignoring case, like every other name join in this service.</b> The two halves are keyed by the
     * spellings of two different exports of the same upstream, and a difference in case between them would take
     * every schema of the system off every one of its pages - with no failed build, no broken link and no log
     * line to find it by. {@code ArchitectureModel.findSystem}, {@code DocumentedSystem.hasComponent} and
     * {@code WhiteboxView.endsOf} all fold for the same reason.
     */
    private DocumentedSystem withSchemas(DocumentedSystem system, String environment) {
        if (system.messages().isEmpty()) {
            return system;
        }
        Map<String, MessageVersionSchemas> replicated = new HashMap<>();
        for (MessageVersionSchemas schemas : messageSchemas.findAll(environment, system.name())) {
            replicated.put(keyOf(schemas.message(), schemas.version()), schemas);
        }
        if (replicated.isEmpty()) {
            return system;
        }
        List<DocumentedMessage> messages = new ArrayList<>();
        int joined = 0;
        for (DocumentedMessage message : system.messages()) {
            List<DocumentedMessageVersion> versions = new ArrayList<>();
            for (DocumentedMessageVersion version : message.versions()) {
                MessageVersionSchemas schemas = replicated.get(keyOf(message.name(), version.version()));
                versions.add(joined(version, schemas));
                joined += schemas == null ? 0 : 1;
            }
            messages.add(message.withVersions(versions));
        }
        if (joined == 0) {
            // Rows for this system exist and not one of them belongs to a version the model lists. That is a
            // model and a replication that disagree about a name, and it is otherwise invisible: the pages are
            // written, complete and simply without schemas.
            log.debug("The {} replicated message type version(s) of the system {} in the environment {} match "
                      + "no version of its model.", replicated.size(), system.name(), environment);
        }
        return system.withMessages(messages);
    }

    /** How a version of the model and a replicated row find each other: by name, folded. */
    private static String keyOf(String message, String version) {
        return message.toLowerCase(Locale.ROOT) + " " + version.toLowerCase(Locale.ROOT);
    }

    private static DocumentedMessageVersion joined(DocumentedMessageVersion version,
                                                   MessageVersionSchemas schemas) {
        return schemas == null ? version : version.with(schemas);
    }

    private static Duration elapsedSince(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt);
    }

    /** Every system of the landscape, with who owns it and how much of it is documented. */
    private void writeIndex(ArchitectureModel model, GenerationContext context, Path systems)
            throws IOException {
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(frontMatter()
                        .put("title", SYSTEMS_LABEL)
                        .put("sidebar_label", SYSTEMS_LABEL)
                        .put("sidebar_position", 0)
                        .put("doc_status", "generated")
                        .put("doc_source", "archrepo")
                        .put("doc_environment", context.environment())
                        .put("doc_generated_at", context.generatedAt().toString()))
                .heading(1, SYSTEMS_LABEL)
                .paragraph(Md.sentence("The systems the architecture repository of the {} environment knows, "
                                       + "and the documentation published for each of them.",
                        Md.bold(context.environment())));

        List<List<Markdown>> rows = new ArrayList<>();
        for (DocumentedSystem system : model.systems()) {
            rows.add(List.of(
                    Md.link(DocumentationPaths.system(system.slug()), system.name()),
                    teamOf(system.team()),
                    Md.text(String.valueOf(system.components().size())),
                    Md.text(String.valueOf(system.events().size())),
                    Md.text(String.valueOf(system.commands().size())),
                    Md.text(system.description())));
        }
        page.table(List.of("System", "Team", "Components", "Events", "Commands", "Description"), rows);
        page.admonition("info", "Generated page", Md.sentence(
                "Generated by the jEAP Doc Service from the architecture model of the {} environment on {}.",
                Md.bold(context.environment()), Md.text(context.generatedAtDisplay())));
        Files.writeString(systems.resolve("index.md"), page.text(), StandardCharsets.UTF_8);
    }

    /**
     * What a system is, who owns it, and which documentation structures it carries. It is where a reader
     * arrives from the system list.
     */
    private void writeLandingPage(DocumentedSystem system, GenerationContext context, Path directory,
                                  List<StructureTemplate> written)
            throws IOException {
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(frontMatter()
                        .put("title", system.name())
                        .put("sidebar_label", system.name())
                        .put("sidebar_position", 0)
                        .put("description", system.description())
                        .put("doc_status", "generated")
                        .put("doc_source", "archrepo")
                        .put("doc_environment", context.environment())
                        .put("doc_generated_at", context.generatedAt().toString()))
                .heading(1, system.name())
                .paragraphOrNothing(Md.text(system.description()),
                        "The architecture repository holds no description of this system.");

        List<List<Markdown>> rows = new ArrayList<>();
        rows.add(List.of(Md.text("Responsible team"), teamOf(system.team())));
        if (!system.aliases().isEmpty()) {
            rows.add(List.of(Md.text("Also known as"),
                    Md.joinWith(", ", system.aliases().stream().map(Md::code).toList())));
        }
        rows.add(List.of(Md.text("Components"), Md.text(String.valueOf(system.components().size()))));
        rows.add(List.of(Md.text("Events"), Md.text(String.valueOf(system.events().size()))));
        rows.add(List.of(Md.text("Commands"), Md.text(String.valueOf(system.commands().size()))));
        page.table(List.of("", ""), rows);

        if (!written.isEmpty()) {
            page.heading(2, "Documentation");
            page.bulletList(written.stream()
                    .map(template -> Md.link(
                            DocumentationPaths.structure(system.slug(), template.systemPathSegment()),
                            template.systemLabel()))
                    .toList());
        }
        page.admonition("info", "Generated page", Md.sentence(
                "Generated by the jEAP Doc Service from the architecture model of the {} environment on {}.",
                Md.bold(context.environment()), Md.text(context.generatedAtDisplay())));
        Files.writeString(directory.resolve("index.md"), page.text(), StandardCharsets.UTF_8);
    }

    private static Markdown teamOf(Team team) {
        if (team == null || team.name() == null || team.name().isBlank()) {
            return Md.italic("unknown");
        }
        return team.contactAddress() == null || team.contactAddress().isBlank()
                ? Md.text(team.name())
                : Md.linkOrCode("mailto:" + team.contactAddress(), team.name());
    }
}
