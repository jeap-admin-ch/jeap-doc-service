package ch.admin.bit.jeap.doc.domain.architecture.imports;

import java.time.Instant;

/** Snapshot metadata, without loading its bytes to decide whether to fetch it again. */
public record ReactionGraphRef(String environment, String sourceFormat, String etag,
                               Instant checkedAt, boolean drawable) {}
