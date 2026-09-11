package ch.admin.bit.jeap.doc.domain.custom;

import java.time.Instant;

/**
 * Where a documentation set came from. Every page of it says so, and it is the same values the upload
 * carried.
 *
 * @param sourceRepository the repository the documents were written in
 * @param sourceRef        the branch or tag that was built
 * @param sourceRevision   the commit they were built from
 * @param sourceTimestamp  when that commit was made
 * @param version          the version of the component or library, null for a system
 * @param uploadedAt       when the set was last uploaded
 */
public record CustomProvenance(
        String sourceRepository,
        String sourceRef,
        String sourceRevision,
        Instant sourceTimestamp,
        String version,
        Instant uploadedAt) {
}
