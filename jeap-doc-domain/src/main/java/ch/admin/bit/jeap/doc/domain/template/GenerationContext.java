package ch.admin.bit.jeap.doc.domain.template;

import ch.admin.bit.jeap.doc.domain.DisplayTime;
import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;

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
 * @param maxDiagramNodes how many other systems a diagram may draw before the rest are left out
 * @param maxEdgeLabels how many names one arrow may carry before it shows their count instead. It is what
 *                    keeps a label small enough for the diagram engine to lay out at all
 * @param linkPrefix what has to go in front of a documentation path <b>inside a diagram</b> - see
 *                   {@link #diagramLink(String)}
 */
public record GenerationContext(
        ArchitectureModel model,
        String environment,
        String archRepoUrl,
        Instant modelImportedAt,
        Instant generatedAt,
        int maxDiagramNodes,
        int maxEdgeLabels,
        String linkPrefix) {

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
