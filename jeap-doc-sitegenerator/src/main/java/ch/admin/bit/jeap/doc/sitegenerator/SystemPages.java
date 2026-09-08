package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.architecture.view.WhiteboxView;
import ch.admin.bit.jeap.doc.domain.ArchitectureImportProperties;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SiteEnvironment;
import ch.admin.bit.jeap.doc.domain.SitePart;
import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.DatabaseSchema;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.RestApiOverview;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureArtifact;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureSnapshot;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessage;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessageVersion;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.architecture.MessageVersionSchemas;
import ch.admin.bit.jeap.doc.domain.architecture.Team;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureArtifactContent;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureArtifactRepository;
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
import java.util.function.Function;

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

    /** The replicated OpenAPI specifications and database schemas, read <b>per system</b> - see below. */
    private final ArchitectureArtifactRepository artifacts;

    /** What turns their bytes into what a page shows. */
    private final ArchitectureArtifactContent artifactContent;

    private final StructureTemplates templates;
    private final GeneratorProperties properties;
    private final ArchitectureImportProperties importProperties;
    private final BuildMetrics metrics;
    private final SiteUrls urls;

    /** What the systems index is called, in its category file, its front matter and its heading. */
    private static final String SYSTEMS_LABEL = "Systems";

    /**
     * The custom property that says which of the shell's sidebar categories is the systems index.
     * <b>Read by {@code docusaurus.config.js}</b>, which hangs one link per system under it: every system is
     * built as a part of its own, so its pages are in no tree the shell's build can see.
     */
    private static final String SYSTEMS_INDEX_PROPERTY = "systemsIndex";


    /**
     * Writes what one part carries of one environment, and reports what that environment's model contributed -
     * or nothing at all when the environment reads no architecture model.
     * <p>
     * An environment with no architecture repository writes nothing, not even an empty index. An empty index
     * would say the landscape is empty rather than that it was not read.
     */
    public Optional<EnvironmentModel> write(Site site, SiteEnvironment environment, SitePart part,
                                           Path environmentDirectory, Instant generatedAt) throws IOException {
        return write(site.id(), environment.id(), part, diagramLinkPrefixOf(site, environment),
                environmentDirectory, generatedAt);
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

    Optional<EnvironmentModel> write(String site, String environment, SitePart part, String diagramLinkPrefix,
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
                properties.limits(), diagramLinkPrefix, properties.apiPaths());

        // The site's own index of the systems belongs to the part that carries whole environment trees. A
        // part that carries one system writes that system and nothing above it: the directory above is another
        // part's, and two parts writing the same page would be two publications claiming one URL.
        if (part.carriesWholeEnvironments()) {
            Path systems = environmentDirectory.resolve(DocumentationPaths.SYSTEMS_SEGMENT);
            Files.createDirectories(systems);
            // Marked so that the site template can find this category among the shell's own and hang one
            // link per system under it. Found by the property and not by the label, because a label is
            // exactly what someone changes.
            Files.writeString(systems.resolve(CategoryFile.NAME),
                    CategoryFile.marked(SYSTEMS_LABEL, 1, SYSTEMS_INDEX_PROPERTY),
                    StandardCharsets.UTF_8);
            writeIndex(model, context, systems);
        }
        if (!part.carriesSystems()) {
            log.debug("Wrote the index of {} systems into the {} tree of {}.",
                    model.systems().size(), environment, part.key());
            return Optional.of(counted(model, snapshot));
        }

        for (DocumentedSystem documented : systemsOf(model, part)) {
            // The schemas of this system, and only this system: the renderings of a whole landscape have no
            // business being held while the site generator runs for minutes afterwards.
            DocumentedSystem system = withArtifacts(withSchemas(documented, environment), environment);
            // Where this part mounts its content. A part carrying a whole environment writes the system
            // below the systems directory of that tree; a part carrying one system writes it at the tree it
            // is mounted at - the same path either way, and the one the site template points its docs plugin
            // at.
            Path directory = part.carriesWholeEnvironments()
                    ? environmentDirectory.resolve(DocumentationPaths.SYSTEMS_SEGMENT).resolve(system.slug())
                    : environmentDirectory.resolve(part.tree());
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
        log.debug("Generated the documentation of {} system(s) into the {} tree of {}.",
                systemsOf(model, part).size(), environment, part.key());
        // Counted off the landscape this run has just generated from, so that the page describing the
        // documentation says what is in it without asking the database again.
        return Optional.of(counted(model, snapshot));
    }

    /**
     * What this environment's landscape contributed, counted over the <b>whole</b> model rather than over what
     * this part wrote. The page describing the documentation says how large the landscape is, which is the same
     * answer whichever part is being built.
     */
    private static EnvironmentModel counted(ArchitectureModel model, ArchitectureSnapshot snapshot) {
        // The systems themselves and not only their number: the shell's sidebar lists them, and they are in
        // other parts' builds - so the only place that can name them is the run that read the landscape.
        List<EnvironmentModel.DocumentedSystemEntry> systems = model.systems().stream()
                .map(system -> new EnvironmentModel.DocumentedSystemEntry(system.name(),
                        DocumentationPaths.system(system.slug())))
                .toList();
        return new EnvironmentModel(systems,
                countOf(model, system -> system.components().size()),
                countOf(model, system -> system.messages().size()),
                snapshot.importedAt());
    }

    /**
     * The systems this part writes: every one of them where it carries whole environment trees, and the one
     * its tree names otherwise.
     * <p>
     * A part whose system is not in this environment's landscape writes nothing into that tree, which is a
     * system that is not deployed on that stage - not an error.
     */
    private static List<DocumentedSystem> systemsOf(ArchitectureModel model, SitePart part) {
        if (part.carriesWholeEnvironments()) {
            return model.systems();
        }
        String slug = part.tree().substring(part.tree().lastIndexOf('/') + 1);
        return model.find(slug).map(List::of).orElseGet(List::of);
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

    /**
     * The same system with the replicated artifacts of its components joined onto them.
     * <p>
     * <b>Read and parsed one component at a time</b>, and never held past the parsing. A specification is
     * among the largest text this service stores - the import bounds one at {@code max-artifact-size}, eight
     * megabytes by default - and the model a build holds stays in memory until the site generator has
     * finished. Reading a whole system's worth in one list would put a component count's multiple of that
     * bound live at once, on a task whose memory is what decides whether a build survives. The extra round
     * trips are two indexed lookups per component.
     * <p>
     * The rows do not need the model's snapshot: an artifact row is replaced whole, so there is nothing a
     * concurrent import could tear.
     * <p>
     * <b>Matched ignoring case, like every other name join in this service.</b> The lookup folds both names
     * the way the unique index does, because the model and these rows carry the spellings of two exports of
     * one upstream. A join that matched exactly would take every schema and every API overview off the
     * system's pages, with no failed build and nothing in the log to find it by.
     * <p>
     * An artifact that could not be read is left out, and the page falls back to what the model knows.
     */
    private DocumentedSystem withArtifacts(DocumentedSystem system, String environment) {
        if (system.components().isEmpty()) {
            return system;
        }
        List<DocumentedComponent> components = new ArrayList<>();
        int joined = 0;
        for (DocumentedComponent component : system.components()) {
            DatabaseSchema schema = readArtifact(environment, system, component,
                    ArchitectureImportKind.DATABASE_SCHEMA, artifactContent::databaseSchema).orElse(null);
            RestApiOverview api = readArtifact(environment, system, component,
                    ArchitectureImportKind.OPENAPI_SPEC, artifactContent::restApi).orElse(null);
            joined += schema == null && api == null ? 0 : 1;
            components.add(component.withArtifacts(schema, api));
        }
        return joined == 0 ? system : system.withComponents(components);
    }

    /**
     * The artifact of one kind for one component, parsed. Nothing is stored, so the bytes are collectable as
     * soon as this returns - which is the whole point of reading them one at a time.
     */
    private <T> Optional<T> readArtifact(String environment, DocumentedSystem system,
                                         DocumentedComponent component, ArchitectureImportKind kind,
                                         Function<ArchitectureArtifact, Optional<T>> parse) {
        return artifacts.find(environment, kind, system.name(), component.name()).flatMap(parse);
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
