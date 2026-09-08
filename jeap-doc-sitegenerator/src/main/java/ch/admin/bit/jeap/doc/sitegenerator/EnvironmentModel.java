package ch.admin.bit.jeap.doc.sitegenerator;

import java.time.Instant;
import java.util.List;

/**
 * What one environment's architecture model contributed to a site, read while the build generated from it.
 * <p>
 * Read here rather than queried, because the run has just read the whole landscape: the answers are in memory,
 * and asking the database for them again would be three queries for something already in hand. It is also why
 * an environment that reads no model has <b>no</b> {@code EnvironmentModel} rather than an empty one - empty
 * says the landscape has nothing in it, and nothing was looked at.
 *
 * @param systems    the systems the environment documents, in the order the index lists them
 * @param components how many components they have between them
 * @param messages   how many events and commands they define between them
 * @param importedAt when the content of that landscape was imported, or null where it has never been
 */
public record EnvironmentModel(List<DocumentedSystemEntry> systems, int components, int messages,
                               Instant importedAt) {

    public EnvironmentModel {
        systems = systems == null ? List.of() : List.copyOf(systems);
    }

    /** An environment whose architecture repository reports no system at all. */
    static EnvironmentModel empty(Instant importedAt) {
        return new EnvironmentModel(List.of(), 0, 0, importedAt);
    }

    /** How many systems it documents, which is what the counts on a page and the gauges are. */
    public int systemCount() {
        return systems.size();
    }

    /**
     * One system as the navigation names it.
     * <p>
     * <b>The path is the environment's own, root-anchored</b> - {@code /systems/orders/} - the same form a
     * page writes a link in. What has to go in front of it to leave a part is the site's base URL and the
     * environment's prefix, which only the site template knows.
     *
     * @param label the system's name, as a reader sees it
     * @param path  where its documentation is served within the environment's tree
     */
    public record DocumentedSystemEntry(String label, String path) {
    }
}
