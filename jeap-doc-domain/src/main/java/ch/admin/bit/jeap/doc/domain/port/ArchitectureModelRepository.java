package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureSnapshot;

import java.time.Instant;

/**
 * Where the imported architecture model of an environment is kept.
 * <p>
 * There is no method for changing part of a landscape. An import fetches the whole thing and replaces the
 * whole thing, so a build always reads one consistent moment and nothing has to detect that a system was
 * deleted upstream.
 */
public interface ArchitectureModelRepository {

    /**
     * The whole landscape of one environment, assembled from the tables, and when it was imported.
     * {@link ArchitectureSnapshot#empty()} when the environment has never been imported.
     * <p>
     * A generation run calls this once per environment, so it has to cost a fixed number of queries and not
     * one per system.
     * <p>
     * <b>Out of one snapshot of the database.</b> The tables are read one after another, each keyed by the
     * identifiers the query before it returned, while an import may be replacing all of them - so an
     * implementation that lets a statement see a moment its predecessor did not returns systems with no
     * components at all. See {@link ArchitectureSnapshot}.
     */
    ArchitectureSnapshot read(String environment);

    /**
     * Replaces everything stored for one environment with the given landscape, in one transaction.
     *
     * @param importedAt when the landscape was read from the architecture repository, which every generated
     *                   page names
     */
    void replace(String environment, ArchitectureModel model, Instant importedAt);
}
