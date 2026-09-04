package ch.admin.bit.jeap.doc.domain.architecture.imports;

import java.time.Instant;

/**
 * An artifact without its content: what the comparison between what is stored and what the upstream lists
 * works on.
 * <p>
 * It exists so that deciding what to fetch never reads a blob out of the database.
 *
 * @param contentUrl where the content is read from, relative to the architecture repository. Null on a
 *                   reference read from the store, which has no reason to keep it
 * @param checkedAt  when this service last stored or confirmed the artifact - what a run that keeps hitting
 *                   its deadline orders the unchanged ones by. Null on an index entry, which has not been
 */
public record ArchitectureArtifactRef(
        String environment,
        ArchitectureImportKind kind,
        String system,
        String component,
        String version,
        String etag,
        Instant modifiedAt,
        String contentUrl,
        Instant checkedAt) {

    /**
     * Whether the upstream is offering the same bytes as the ones already stored.
     */
    public boolean hasSameContentAs(ArchitectureArtifactRef stored) {
        return stored != null && etag != null && etag.equals(stored.etag());
    }
}
