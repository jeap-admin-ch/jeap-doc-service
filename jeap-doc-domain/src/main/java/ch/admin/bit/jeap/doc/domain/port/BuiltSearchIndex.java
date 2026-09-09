package ch.admin.bit.jeap.doc.domain.port;

import java.nio.file.Path;

/**
 * What building a search index produced.
 *
 * @param directory where the index lies, to be published from
 * @param records   how many pages went into it
 * @param millis    how long it took, content pass included
 */
public record BuiltSearchIndex(Path directory, int records, long millis) {
}
