package ch.admin.bit.jeap.doc.persistence;

import java.time.Instant;

/**
 * One stored message type version without its schemas.
 * <p>
 * A closed interface projection rather than the entity, so that Spring Data selects these columns and only
 * these. The two rendering columns are the largest in this database and deciding which versions to fetch has
 * no use for them - reading them to find out which versions exist would pull the whole replication into the
 * heap once an hour, which is the twin of what {@link ArchitectureArtifactRefView} avoids for the artifacts.
 */
interface MessageVersionRefView {

    String getEnvironment();

    String getSystemName();

    String getMessageName();

    String getVersion();

    String getEtag();

    Instant getCheckedAt();
}
