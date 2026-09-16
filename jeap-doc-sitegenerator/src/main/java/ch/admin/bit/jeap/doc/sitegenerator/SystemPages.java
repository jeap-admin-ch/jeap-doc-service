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
import ch.admin.bit.jeap.doc.domain.custom.CustomDocumentation;
import ch.admin.bit.jeap.doc.domain.custom.CustomProperties;
import ch.admin.bit.jeap.doc.domain.custom.CustomSubject;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationRepository;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationStorage;
import ch.admin.bit.jeap.doc.domain.template.SystemDocumentation;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessage;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessageVersion;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.architecture.MessageVersionSchemas;
import ch.admin.bit.jeap.doc.domain.architecture.Team;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureArtifactContent;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureArtifactRepository;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ReactionGraphRef;
import ch.admin.bit.jeap.doc.domain.architecture.imports.StoredReactionGraph;
import ch.admin.bit.jeap.doc.domain.architecture.view.ReactionModelIndex;
import ch.admin.bit.jeap.doc.domain.architecture.view.ReactionView;
import ch.admin.bit.jeap.doc.domain.template.ReactionViews;
import ch.admin.bit.jeap.doc.domain.port.ReactionGraphContent;
import ch.admin.bit.jeap.doc.domain.port.ReactionGraphRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    private final ReactionGraphRepository reactionGraphs;
    private final ReactionGraphContent reactionContent;

    /** What has been uploaded, and where its bundles are read from - the other half of what a page shows. */
    private final CustomDocumentationRepository documentation;

    private final CustomDocumentationStorage documentationStorage;

    private final CustomProperties customProperties;

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
        // What has been uploaded for this part, whatever the architecture model of this environment holds.
        // It is per site rather than per environment: an upload names no environment, so the same
        // documentation is written into every tree of the site.
        List<String> documentedSlugs = documentedSlugsOf(site, part);

        if (!architectureModel.isConfiguredFor(environment)) {
            if (documentedSlugs.isEmpty()) {
                log.debug("No architecture repository is configured for the environment {} and nothing is "
                          + "documented for {}; nothing is generated into that tree.", environment, part.key());
                // Not the same as none: an environment that reads no model has nothing to say about how many
                // systems there are, and the root page must not claim there are zero.
                return Optional.empty();
            }
            // Nothing to generate from and something to publish: what a team uploaded is written into this
            // tree on its own. A site whose environments have no architecture repository is a legitimate
            // instance, and its documentation is all custom.
            log.debug("No architecture repository is configured for the environment {}; the {} documented "
                      + "system(s) of {} are written from what was uploaded.",
                    environment, documentedSlugs.size(), part.key());
            return Optional.of(writeSystems(site, environment, part, diagramLinkPrefix, environmentDirectory,
                    generatedAt, ArchitectureSnapshot.empty(), documentedSlugs));
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
        if (model.isEmpty() && documentedSlugs.isEmpty()) {
            log.warn("The architecture repository of the environment {} reports no system at all, and nothing "
                     + "is documented for {}. Nothing is generated into that tree.", environment, part.key());
            return Optional.of(EnvironmentModel.empty(snapshot.importedAt()));
        }
        warnWhenTheImportIsBehind(environment, generatedAt);
        return Optional.of(writeSystems(site, environment, part, diagramLinkPrefix, environmentDirectory,
                generatedAt, snapshot, documentedSlugs));
    }

    /**
     * Writes the systems of one environment tree: those the architecture model holds, and those only the
     * uploaded documentation knows.
     *
     * @param snapshot        what the import stored, which is empty for an environment that reads no model
     * @param documentedSlugs the systems of this part something has been uploaded for
     */
    private EnvironmentModel writeSystems(String site, String environment, SitePart part,
                                          String diagramLinkPrefix, Path environmentDirectory,
                                          Instant generatedAt, ArchitectureSnapshot snapshot,
                                          List<String> documentedSlugs) throws IOException {
        ArchitectureModel model = snapshot.model();
        GenerationContext context = new GenerationContext(model, environment,
                architectureModel.sourceUrlOf(environment).orElse(""),
                snapshot.importedAt(), generatedAt,
                properties.limits(), diagramLinkPrefix, properties.apiPaths())
                .withViewExcludedComponents(properties.viewExclusions());

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
            writeIndex(model, documentedSlugs, context, systems);
        }
        if (!part.carriesSystems()) {
            log.debug("Wrote the index of {} systems into the {} tree of {}.",
                    model.systems().size(), environment, part.key());
            return counted(model, documentedSlugs, snapshot);
        }

        // Once for the environment, ahead of the systems: what it answers is the same for every one of them,
        // and most landscapes run no reaction observer at all.
        EnvironmentReactions reactions = reactionsOfEnvironment(environment, model);

        List<String> slugs = slugsToWrite(model, part, documentedSlugs);
        for (String slug : slugs) {
            Optional<DocumentedSystem> found = model.find(slug);
            // The schemas of this system, and only this system: the renderings of a whole landscape have no
            // business being held while the site generator runs for minutes afterwards.
            Optional<DocumentedSystem> system =
                    found.map(one -> withArtifacts(withSchemas(one, environment), environment));
            // The reaction graphs of this system, and only this system's: they are handed to the templates
            // and let go when the system has been written, exactly as its artifacts are.
            GenerationContext systemContext = system
                    .map(one -> context.withReactions(reactionsOf(one, environment, reactions)))
                    .orElse(context);
            // Where this part mounts its content. A part carrying a whole environment writes the system
            // below the systems directory of that tree; a part carrying one system writes it at the tree it
            // is mounted at - the same path either way, and the one the site template points its docs plugin
            // at.
            Path directory = part.carriesWholeEnvironments()
                    ? environmentDirectory.resolve(DocumentationPaths.SYSTEMS_SEGMENT).resolve(slug)
                    : environmentDirectory.resolve(part.tree());
            Files.createDirectories(directory);
            String name = system.map(DocumentedSystem::name).orElse(slug);
            Files.writeString(directory.resolve(CategoryFile.NAME), CategoryFile.of(name),
                    StandardCharsets.UTF_8);
            // The templates first, so the landing page links only to the subtrees that exist. A template is
            // allowed to write nothing, and a link to a page nothing wrote fails the whole site build.
            List<StructureTemplate> written = new ArrayList<>();
            CustomDocumentation uploaded = documentation.of(site, slug);
            for (StructureTemplate template : templates.all()) {
                // Narrowed to what this template publishes before anything asks it a question: a subject may
                // carry a second methodology beside this one. The microsites stay in - a chapter that holds
                // only one is still a chapter, and the page that frames it is the only way to reach it -
                // while every question about pages narrows again to the Markdown set behind it.
                CustomDocumentation ofTemplate = uploaded.ofTemplate(template.id());
                // One writer per template: it holds the bundles of that template's sets open while the
                // template walks, and closing it is what lets go of them.
                try (CustomPagesWriter pages = new CustomPagesWriter(ofTemplate, documentationStorage,
                        template, customProperties)) {
                    SystemDocumentation documented = system
                            .map(one -> SystemDocumentation.of(site, one, ofTemplate, pages))
                            .orElseGet(() -> SystemDocumentation.ofUploadsOnly(site, slug, ofTemplate, pages));
                    template.writeSystem(documented, systemContext, directory);
                }
                if (Files.isDirectory(directory.resolve(template.systemPathSegment()))) {
                    written.add(template);
                }
            }
            writeLandingPage(name, slug, system, systemContext, directory, written);
        }
        log.debug("Generated the documentation of {} system(s) into the {} tree of {}.",
                slugs.size(), environment, part.key());
        // Counted off the landscape this run has just generated from, so that the page describing the
        // documentation says what is in it without asking the database again.
        return counted(model, documentedSlugs, snapshot);
    }

    /**
     * The systems this part writes into this tree: those of the landscape, and those something has been
     * uploaded for. Sorted and without duplicates, so a system that is both is written once.
     */
    private static List<String> slugsToWrite(ArchitectureModel model, SitePart part,
                                             List<String> documentedSlugs) {
        SortedSet<String> slugs = new TreeSet<>(documentedSlugs);
        if (part.carriesWholeEnvironments()) {
            model.systems().forEach(system -> slugs.add(system.slug()));
            return List.copyOf(slugs);
        }
        String slug = part.tree().substring(part.tree().lastIndexOf('/') + 1);
        model.find(slug).ifPresent(system -> slugs.add(system.slug()));
        return List.copyOf(slugs);
    }

    /**
     * The systems of this part that something has been uploaded for.
     * <p>
     * Per site rather than per environment, because an upload names no environment: the same documentation
     * is written into every tree of the site.
     */
    private List<String> documentedSlugsOf(String site, SitePart part) {
        SortedSet<String> slugs = new TreeSet<>();
        for (CustomSubject subject : documentation.subjectsOf(site)) {
            slugs.add(subject.system());
        }
        if (part.carriesWholeEnvironments()) {
            return List.copyOf(slugs);
        }
        String slug = part.tree().substring(part.tree().lastIndexOf('/') + 1);
        return slugs.contains(slug) ? List.of(slug) : List.of();
    }

    /**
     * What this environment's landscape contributed, counted over the <b>whole</b> model rather than over what
     * this part wrote. The page describing the documentation says how large the landscape is, which is the same
     * answer whichever part is being built.
     */
    private static EnvironmentModel counted(ArchitectureModel model, List<String> documentedSlugs,
                                            ArchitectureSnapshot snapshot) {
        // The systems themselves and not only their number: the shell's sidebar lists them, and they are in
        // other parts' builds - so the only place that can name them is the run that read the landscape.
        List<EnvironmentModel.DocumentedSystemEntry> systems = new ArrayList<>(model.systems().stream()
                .map(system -> new EnvironmentModel.DocumentedSystemEntry(system.name(),
                        DocumentationPaths.system(system.slug())))
                .toList());
        // And the ones only the uploads know, after them. They are written into this tree exactly as the
        // others are, so leaving them out here would publish a system the sidebar, the footer and the systems
        // index all fail to link - reachable only by typing its URL.
        for (String slug : documentedSlugs) {
            if (model.find(slug).isEmpty()) {
                systems.add(new EnvironmentModel.DocumentedSystemEntry(slug, DocumentationPaths.system(slug)));
            }
        }
        return new EnvironmentModel(List.copyOf(systems), model.systems().size(),
                countOf(model, system -> system.components().size()),
                countOf(model, system -> system.messages().size()),
                snapshot.importedAt());
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
     * The reaction graphs of one system, drawn-ready: its own, its components' and those of the message types
     * it defines.
     * <p>
     * <b>Read one system at a time</b>, for the reason its artifacts are: what a build holds stays in memory
     * until the site generator has finished, and a landscape's worth of graphs is a multiple of one system's.
     * <p>
     * The age a page names is the newest of these graphs rather than the state row's: the row says when the
     * import last <i>ran</i>, and what a reader is being told is how old the picture in front of them is.
     */
    private ReactionViews reactionsOf(DocumentedSystem system, String environment,
                                      EnvironmentReactions reactions) {
        if (!reactions.any()) {
            return ReactionViews.none();
        }
        List<StoredReactionGraph> stored = new ArrayList<>();
        ReactionView systemView = reactionGraphs
                .find(environment, ArchitectureImportKind.SYSTEM_REACTIONS, system.name(),
                        ReactionGraphRef.NO_SYSTEM, ReactionGraphRef.NO_VARIANT)
                .map(graph -> viewOf(graph, stored, reactions.names(), system))
                .orElseGet(ReactionView::empty);
        Map<String, ReactionView> components = new LinkedHashMap<>();
        for (DocumentedComponent component : system.components()) {
            // With the system, because two systems may each call a component gateway and one graph is not
            // both of theirs.
            reactionGraphs.find(environment, ArchitectureImportKind.COMPONENT_REACTIONS, component.name(),
                            system.name(), ReactionGraphRef.NO_VARIANT)
                    .map(graph -> viewOf(graph, stored, reactions.names(), system))
                    .filter(view -> !view.isEmpty())
                    .ifPresent(view -> components.put(component.name(), view));
        }
        Map<String, List<ReactionViews.VariantView>> messages = new LinkedHashMap<>();
        for (DocumentedMessage message : system.messages()) {
            List<ReactionViews.VariantView> variants = reactionGraphs
                    .findVariants(environment, message.name()).stream()
                    .map(graph -> new ReactionViews.VariantView(graph.variant(),
                            viewOf(graph, stored, reactions.names(), system)))
                    .filter(variant -> !variant.view().isEmpty())
                    .toList();
            if (!variants.isEmpty()) {
                messages.put(message.name(), variants);
            }
        }
        if (systemView.isEmpty() && components.isEmpty() && messages.isEmpty()) {
            return ReactionViews.none();
        }
        return ReactionViews.of(newestOf(stored), systemView, components, messages,
                reactions.componentsWithAGraph());
    }

    /**
     * What the reaction graphs of one environment say before any of its systems is written: whether there are
     * any at all, and which components have one.
     * <p>
     * <b>Three reads for the environment</b>, rather than one per component and per message of every system it
     * carries. Most landscapes have no reaction observer, and this is the difference between three queries a
     * build and some hundreds of them that all answer nothing.
     * <p>
     * The component names are read here and not per system because a reaction drawn on one system's graph is
     * regularly a component of another - a link to it may only be written where that component's chapter 6 is
     * written too, and a single system's graphs cannot answer that. It is the refs and not the graphs: a
     * reference is a row without its bytes, and reading a landscape's worth of graphs is exactly what
     * generating one system at a time avoids.
     */
    private EnvironmentReactions reactionsOfEnvironment(String environment, ArchitectureModel model) {
        List<ReactionGraphRef> components =
                reactionGraphs.findRefs(environment, ArchitectureImportKind.COMPONENT_REACTIONS);
        boolean any = !components.isEmpty()
                      || !reactionGraphs.findRefs(environment, ArchitectureImportKind.SYSTEM_REACTIONS)
                              .isEmpty()
                      || !reactionGraphs.findRefs(environment, ArchitectureImportKind.MESSAGE_REACTIONS)
                              .isEmpty();
        // Only the graphs that draw something. A stored graph is not a written page: the observer can serve
        // one with no node in it, and so can a payload whose every node is of a kind this version does not
        // know - and chapter 6 is not written for either, so a link into it would be a 404 the reader is
        // offered. What counts a graph's drawable nodes is the adapter that read it, once, while storing it.
        Set<String> drawable = components.stream()
                .filter(ReactionGraphRef::drawable)
                .map(ReactionGraphRef::name)
                .collect(Collectors.toSet());
        return new EnvironmentReactions(any, drawable, ReactionModelIndex.of(model));
    }

    /**
     * What one environment's reaction graphs say, read once for all of its systems.
     *
     * @param any whether the environment has any reaction graph at all - which is what an environment whose
     *            stage runs no reaction observer answers, and what every environment answered before the
     *            observer was configured
     * @param names the model, indexed by the names a graph carries. Built once per environment rather than
     *            per graph: a landscape has a graph per system, per component and per message variant, and
     *            every node of every one of them has to be looked up in the model
     */
    private record EnvironmentReactions(boolean any, Set<String> componentsWithAGraph,
                                        ReactionModelIndex names) {
    }

    /** One stored graph as a view, remembering the row so that the pages can say how old the picture is. */
    private ReactionView viewOf(StoredReactionGraph graph, List<StoredReactionGraph> stored,
                                ReactionModelIndex names, DocumentedSystem system) {
        stored.add(graph);
        return ReactionView.of(reactionContent.read(graph), names, system);
    }

    private static Instant newestOf(List<StoredReactionGraph> graphs) {
        return graphs.stream().map(StoredReactionGraph::importedAt).filter(Objects::nonNull)
                .max(Instant::compareTo).orElse(null);
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
    private void writeIndex(ArchitectureModel model, List<String> documentedSlugs,
                            GenerationContext context, Path systems)
            throws IOException {
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(frontMatter()
                        .put("title", SYSTEMS_LABEL)
                        .put("sidebar_label", SYSTEMS_LABEL)
                        .put("sidebar_position", 0)
                        .put("doc_status", "generated")
                        // The doc service's own page: it lists what the model holds and what has been
                        // uploaded, and neither half alone is where it comes from.
                        .put("doc_source", "doc-service")
                        .put("doc_environment", context.environment())
                        .put("doc_generated_at", context.generatedAt().toString())
                        .put("doc_generated_at_display", context.generatedAtDisplay()))
                .heading(1, SYSTEMS_LABEL)
                .paragraph(Md.sentence("Shows all known systems for the {} environment, and the "
                                       + "documentation generated and published for each of them.",
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
        // The systems only the uploads know, which this tree publishes exactly as it does the others. No
        // counts: a system the model does not hold has no team, no components and no messages to count, and
        // zeros would read as a system that has none rather than as one nothing knows about.
        for (String slug : documentedSlugs) {
            if (model.find(slug).isPresent()) {
                continue;
            }
            rows.add(List.of(
                    Md.link(DocumentationPaths.system(slug), slug),
                    Md.italic("unknown"),
                    Md.text(""), Md.text(""), Md.text(""),
                    Md.italic("The architecture model of this environment does not hold this system.")));
        }
        page.table(List.of("System", "Team", "Components", "Events", "Commands", "Description"), rows);
        Files.writeString(systems.resolve("index.md"), page.text(), StandardCharsets.UTF_8);
    }

    /**
     * What a system is, who owns it, and which documentation structures it carries. It is where a reader
     * arrives from the system list.
     */
    private void writeLandingPage(String name, String slug, Optional<DocumentedSystem> found,
                                  GenerationContext context, Path directory,
                                  List<StructureTemplate> written)
            throws IOException {
        String description = found.map(DocumentedSystem::description).orElse(null);
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(frontMatter()
                        .put("title", name)
                        .put("sidebar_label", name)
                        .put("sidebar_position", 0)
                        .put("description", description)
                        .put("doc_status", "generated")
                        // Where the facts on this page come from. A system the architecture model does not
                        // hold has none from it, and a page saying otherwise would name a source it has not
                        // read.
                        .put("doc_source", found.isPresent() ? "archrepo" : "doc-service")
                        .put("doc_environment", context.environment())
                        .put("doc_generated_at", context.generatedAt().toString())
                        .put("doc_generated_at_display", context.generatedAtDisplay()))
                .heading(1, name)
                .paragraphOrNothing(Md.text(description),
                        found.isPresent()
                                ? "The architecture repository holds no description of this system."
                                : "The architecture model of this environment does not hold this system. "
                                  + "What is documented here was written by the team that owns it.");

        // The facts of the model, and only where there is one: a system that is documented and deployed
        // nowhere has no team, no components and no messages to count, and a table of zeros would read as
        // a system that has none rather than as one nothing knows about.
        if (found.isPresent()) {
            DocumentedSystem system = found.get();
            List<List<Markdown>> rows = new ArrayList<>();
            rows.add(List.of(Md.text("Responsible team"), teamOf(system.team())));
            if (!system.otherNames().isEmpty()) {
                rows.add(List.of(Md.text("Also known as"),
                        Md.joinWith(", ", system.otherNames().stream().map(Md::code).toList())));
            }
            rows.add(List.of(Md.text("Components"), Md.text(String.valueOf(system.components().size()))));
            rows.add(List.of(Md.text("Events"), Md.text(String.valueOf(system.events().size()))));
            rows.add(List.of(Md.text("Commands"), Md.text(String.valueOf(system.commands().size()))));
            page.table(List.of("", ""), rows);
        }

        if (!written.isEmpty()) {
            page.heading(2, "Documentation");
            page.bulletList(written.stream()
                    .map(template -> Md.link(
                            DocumentationPaths.structure(slug, template.systemPathSegment()),
                            template.systemLabel()))
                    .toList());
        }
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
