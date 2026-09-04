package ch.admin.bit.jeap.doc.domain.architecture.imports;

import java.time.Instant;

/**
 * What the last import of one environment and kind did.
 * <p>
 * It is three things at once: the evidence an operator reads, the memory that lets a run skip work the one
 * before it already did, and what the staleness gauge is read from. It plays the role for an import that a
 * build record plays for a build.
 *
 * @param contentHash    the hash of the whole landscape as it was last fetched, for {@link
 *                       ArchitectureImportKind#MODEL} only. A run whose fetch hashes to this writes nothing
 * @param indexEtag      the entity tag of the artifact index, for the artifact kinds only. Sent as
 *                       {@code If-None-Match}, but only after a complete run - see {@link #complete()}
 * @param complete       whether the last run got through its whole list and stored or confirmed every entry
 *                       in it - what makes the index tag trustworthy. An index that answers "not modified"
 *                       says the landscape is unchanged, not that everything in it was fetched, so a run that
 *                       stopped early, or that could not replicate an entry, must not trust that answer next
 *                       time: the entry it missed would never be offered again
 * @param itemCount      how many things are stored for this environment and kind
 * @param lastAttemptAt  when a run last started, successful or not
 * @param lastSuccessAt  when a run last succeeded, or null if none ever has
 * @param lastOutcome    what the last run did, or null if none ever has. It is <b>not</b> derivable from the
 *                       rest of this row: a run that stopped at its deadline stored what it had reached and is
 *                       neither a success nor a failure, and reading it off {@code lastSuccessAt} and
 *                       {@code failureReason} would report exactly that run - the one most worth telling
 *                       apart - as a failure
 * @param failureReason  why the last run failed, or null if it did not
 */
public record ArchitectureImportState(
        String environment,
        ArchitectureImportKind kind,
        String contentHash,
        String indexEtag,
        boolean complete,
        int itemCount,
        Instant lastAttemptAt,
        Instant lastSuccessAt,
        ImportOutcome lastOutcome,
        String failureReason) {

    /**
     * The state of an environment and kind that has never been imported.
     */
    public static ArchitectureImportState none(String environment, ArchitectureImportKind kind) {
        return new ArchitectureImportState(environment, kind, null, null, false, 0, null, null, null, null);
    }

    public ArchitectureImportState withContentHash(String contentHash) {
        return new ArchitectureImportState(environment, kind, contentHash, indexEtag, complete, itemCount,
                lastAttemptAt, lastSuccessAt, lastOutcome, failureReason);
    }

    public ArchitectureImportState withIndexEtag(String indexEtag) {
        return new ArchitectureImportState(environment, kind, contentHash, indexEtag, complete, itemCount,
                lastAttemptAt, lastSuccessAt, lastOutcome, failureReason);
    }

    public boolean hasEverSucceeded() {
        return lastSuccessAt != null;
    }

    /**
     * The entity tag to send as {@code If-None-Match} on the artifact index, or null when the last run left
     * something unfetched or unreplicated and its answer may therefore not be trusted.
     */
    public String conditionalIndexEtag() {
        return complete ? indexEtag : null;
    }
}
