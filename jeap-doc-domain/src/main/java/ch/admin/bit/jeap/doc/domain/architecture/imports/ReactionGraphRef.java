package ch.admin.bit.jeap.doc.domain.architecture.imports;

import java.time.Instant;

/**
 * Snapshot metadata, without loading its bytes to decide whether to fetch it again.
 *
 * @param sourceFormat the format the entity tag belongs to; empty for legacy V1 snapshots
 * @param etag the upstream entity tag verbatim, including quotes
 * @param checkedAt when the snapshot was last stored or confirmed unchanged
 * @param drawable whether it contains observations, used to refuse an unexpectedly empty replacement
 */
public record ReactionGraphRef(String environment, String sourceFormat, String etag,
                               Instant checkedAt, boolean drawable) {}
