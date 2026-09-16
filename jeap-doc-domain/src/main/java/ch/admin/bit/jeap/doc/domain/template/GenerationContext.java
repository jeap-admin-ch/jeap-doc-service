package ch.admin.bit.jeap.doc.domain.template;

import ch.admin.bit.jeap.doc.domain.DisplayTime;
import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.view.ViewExclusions;

import java.time.Instant;

/**
 * What a template needs to know beyond the system it is writing: the surrounding landscape, and where the pages
 * came from.
 *
 * @param model       the whole landscape of this environment - a system's context is computed across it
 * @param environment the environment being written, which every page names
 * @param archRepoUrl the architecture repository the model was read from, which every page names
 * @param modelImportedAt when the content this page is written from was imported, which every page names.
 *                    It comes out of the same snapshot as the model, so it is the import the content is from
 *                    and not merely the last one that ran. It is not when the build ran either: since the
 *                    model is imported on a schedule of its own, the age of the content and the age of the
 *                    page are two different things
 * @param generatedAt when this build started, which every page names
 * @param limits      how much a diagram of this run may draw
 * @param linkPrefix what has to go in front of a documentation path <b>inside a diagram</b> - see
 *                   {@link #diagramLink(String)}
 * @param apiPaths   which paths of a REST specification this documentation describes. The actuator is what
 *                   it is for: every jEAP service publishes the platform's operational endpoints, and they
 *                   are in its specification without being what a reader came for
 * @param reactions  the reaction graphs of the system being written
 * @param viewExclusions the components left out of the diagrams and relations tables of other pages
 */
public record GenerationContext(
        ArchitectureModel model,
        String environment,
        String archRepoUrl,
        Instant modelImportedAt,
        Instant generatedAt,
        DiagramLimits limits,
        String linkPrefix,
        DocumentedApiPaths apiPaths,
        ReactionViews reactions,
        ViewExclusions viewExclusions) {

    public GenerationContext {
        apiPaths = apiPaths == null ? DocumentedApiPaths.ALL : apiPaths;
        reactions = reactions == null ? ReactionViews.none() : reactions;
        viewExclusions = viewExclusions == null ? ViewExclusions.NONE
                : viewExclusions;
    }

    /** A run that leaves no component out of the views. */
    public GenerationContext(ArchitectureModel model, String environment, String archRepoUrl,
                             Instant modelImportedAt, Instant generatedAt, DiagramLimits limits,
                             String linkPrefix, DocumentedApiPaths apiPaths, ReactionViews reactions) {
        this(model, environment, archRepoUrl, modelImportedAt, generatedAt, limits, linkPrefix, apiPaths,
                reactions, ViewExclusions.NONE);
    }

    /**
     * A run that describes every path of every specification.
     * <p>
     * The generator always passes the configured paths - {@code GeneratorProperties.apiPaths()} - so this is
     * the form for a caller that has no opinion about them, which is every test that is not about the
     * exclusions.
     */
    public GenerationContext(ArchitectureModel model, String environment, String archRepoUrl,
                             Instant modelImportedAt, Instant generatedAt, DiagramLimits limits,
                             String linkPrefix) {
        this(model, environment, archRepoUrl, modelImportedAt, generatedAt, limits, linkPrefix,
                DocumentedApiPaths.ALL, ReactionViews.none());
    }

    /**
     * A run with the configured paths but no reactions - which is every run of an environment whose
     * stage has no reaction observer, and every test that is not about the runtime views.
     */
    public GenerationContext(ArchitectureModel model, String environment, String archRepoUrl,
                             Instant modelImportedAt, Instant generatedAt, DiagramLimits limits,
                             String linkPrefix, DocumentedApiPaths apiPaths) {
        this(model, environment, archRepoUrl, modelImportedAt, generatedAt, limits, linkPrefix,
                apiPaths, ReactionViews.none());
    }

    /**
     * The same run, carrying the reaction graphs of the system whose pages are about to be written.
     * <p>
     * They are joined on per system and let go afterwards: the graphs of a whole landscape held at once
     * would be a multiple of what a build is given, which is the rule a component's replicated
     * artifacts already follow.
     */
    public GenerationContext withReactions(ReactionViews reactions) {
        return new GenerationContext(model, environment, archRepoUrl, modelImportedAt, generatedAt,
                limits, linkPrefix, apiPaths, reactions, viewExclusions);
    }

    /** The same run, leaving the given components out of the views of other pages. */
    public GenerationContext withViewExclusions(ViewExclusions excluded) {
        return new GenerationContext(model, environment, archRepoUrl, modelImportedAt, generatedAt,
                limits, linkPrefix, apiPaths, reactions, excluded);
    }

    /** When the reactions were imported, written where a person reads it. Empty when they never were. */
    public String reactionsImportedAtDisplay() {
        return DisplayTime.orEmpty(reactions.importedAt());
    }

    public String generatedAtDisplay() {
        return DisplayTime.of(generatedAt);
    }

    /** When the model was imported, written where a person reads it. Empty when it never was. */
    public String modelImportedAtDisplay() {
        return DisplayTime.orEmpty(modelImportedAt);
    }

    public boolean hasModelImportedAt() {
        return modelImportedAt != null;
    }

    /**
     * A documentation path as a link inside a diagram.
     * <p>
     * A Markdown link is rewritten twice on its way to the reader: a remark plugin adds the environment
     * prefix, and Docusaurus adds the base URL. Neither looks inside a fence, so a diagram link has to carry
     * both already.
     */
    public String diagramLink(String documentationPath) {
        return linkPrefix + documentationPath.substring(documentationPath.startsWith("/") ? 1 : 0);
    }
}
