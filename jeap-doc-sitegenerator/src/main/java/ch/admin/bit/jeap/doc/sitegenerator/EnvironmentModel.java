package ch.admin.bit.jeap.doc.sitegenerator;

import java.time.Instant;

/**
 * What one environment's architecture model contributed to a site, counted while the build read it.
 * <p>
 * Counted here rather than queried, because the run has just read the whole landscape to generate from it: the
 * numbers are in memory, and asking the database for them again would be three queries for something already
 * in hand. It is also why an environment that reads no model has <b>no</b> {@code EnvironmentModel} rather than
 * one full of zeros - a zero says the landscape is empty, and nothing was looked at.
 *
 * @param systems    how many systems the environment documents
 * @param components how many components they have between them
 * @param messages   how many events and commands they define between them
 * @param importedAt when the content of that landscape was imported, or null where it has never been
 */
public record EnvironmentModel(int systems, int components, int messages, Instant importedAt) {

    /** An environment whose architecture repository reports no system at all. */
    static EnvironmentModel empty(Instant importedAt) {
        return new EnvironmentModel(0, 0, 0, importedAt);
    }
}
