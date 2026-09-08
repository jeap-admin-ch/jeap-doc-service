package ch.admin.bit.jeap.doc.domain.template;

import ch.admin.bit.jeap.doc.domain.DisplayTime;
import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;

import java.net.URI;
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
 */
public record GenerationContext(
        ArchitectureModel model,
        String environment,
        String archRepoUrl,
        Instant modelImportedAt,
        Instant generatedAt,
        DiagramLimits limits,
        String linkPrefix,
        DocumentedApiPaths apiPaths) {

    public GenerationContext {
        apiPaths = apiPaths == null ? DocumentedApiPaths.ALL : apiPaths;
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
                DocumentedApiPaths.ALL);
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
     * An address the architecture repository served as its own path, made absolute so that a browser can
     * follow it - which is what a link on a page needs. A {@code contentUrl} is that shape, unlike a
     * {@code swaggerUrl}, which the upstream serves absolute because a browser follows it directly.
     * <p>
     * <b>Resolved against the origin, not appended to the URL.</b> The architecture repository's content
     * URLs already carry its context path, and so does the configured upstream - appending one to the other
     * would put the context path in twice and the link would answer {@code 404}. It is the same rule the
     * replication resolves an artifact by.
     * <p>
     * Answers the address unchanged where it is absolute already, where this run does not know the
     * architecture repository's URL, or where either is not a URI: a relative address is then still shown as
     * code, which is what {@code Md.linkOrCode} does with a target it cannot link.
     */
    public String archRepoLink(String addressRelativeToTheArchRepo) {
        String address = addressRelativeToTheArchRepo;
        if (address == null || address.isBlank() || address.contains("://")
            || archRepoUrl == null || archRepoUrl.isBlank()) {
            return address;
        }
        try {
            return URI.create(archRepoUrl).resolve(address).toString();
        } catch (IllegalArgumentException e) {
            // Not a URI, on either side. One unusable address must not end the generation of every system.
            return address;
        }
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
