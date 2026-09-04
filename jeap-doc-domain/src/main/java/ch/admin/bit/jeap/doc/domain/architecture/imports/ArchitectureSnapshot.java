package ch.admin.bit.jeap.doc.domain.architecture.imports;

import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import java.time.Instant;

/**
 * The landscape of one environment as one moment: every system with everything below it, and when that content
 * was imported.
 * <p>
 * The two travel together because they have to be read together, out of <b>one snapshot</b> of the database. An
 * import replaces a whole landscape and gives every row an identifier that has never existed before, so a
 * reader that takes the systems from one moment and their components from the next gets systems whose
 * components, messages and relations are silently <i>empty</i> - and a build that publishes that says nothing
 * went wrong. Reading the timestamp on its own is the same defect in miniature: the page would name an import
 * its content did not come from.
 *
 * @param model      the systems of the environment, with everything below them
 * @param importedAt when this content was read from the architecture repository, or {@code null} where the
 *                   environment has never been imported. It is <b>not</b> when the architecture repository was
 *                   last read successfully: a run that finds the landscape unchanged writes nothing and leaves
 *                   this as it was. That other question - whether the import is still working - is
 *                   {@code ArchitectureModelSource.lastSuccessfulImportAt}
 */
public record ArchitectureSnapshot(ArchitectureModel model, Instant importedAt) {

    /** What an environment that has never been imported reads as. */
    public static ArchitectureSnapshot empty() {
        return new ArchitectureSnapshot(ArchitectureModel.empty(), null);
    }
}
