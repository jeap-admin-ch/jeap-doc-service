package ch.admin.bit.jeap.doc.persistence;

import java.time.Instant;

/**
 * A reaction graph without its bytes.
 * <p>
 * A closed interface projection rather than the entity, so that Spring Data selects these columns and only
 * these: comparing entity tags must never read the graphs, which is the whole reason the replication is cheap.
 */
interface ReactionGraphRefView {

    String getEnvironment();

    String getKind();

    String getName();

    String getUpstreamName();

    String getVariant();

    String getSystemName();

    String getEtag();

    int getDrawableNodes();

    Instant getCheckedAt();
}
