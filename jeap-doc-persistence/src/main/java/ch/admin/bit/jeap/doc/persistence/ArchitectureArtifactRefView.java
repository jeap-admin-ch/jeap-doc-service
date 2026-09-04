package ch.admin.bit.jeap.doc.persistence;

import java.time.Instant;

/**
 * An artifact without its content.
 * <p>
 * A closed interface projection rather than the entity, so that Spring Data selects these columns and only
 * these: comparing entity tags must never read the blobs, which is the whole reason the replication is cheap.
 */
interface ArchitectureArtifactRefView {

    String getEnvironment();

    String getKind();

    String getSystemName();

    String getComponentName();

    String getVersion();

    String getEtag();

    Instant getModifiedAt();

    Instant getCheckedAt();
}
