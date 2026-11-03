package ch.admin.bit.jeap.doc.domain.governance;

import java.time.Instant;

/**
 * What the last import of one kind ({@link GovernanceResourceKind#SYSTEM} or {@link
 * GovernanceResourceKind#RULE}) did.
 * <p>
 * It is what lets a run skip work the one before it already did, and what a status endpoint reads to say how
 * current the governance site is. There is no environment: the Governance Service is read once per instance.
 *
 * @param indexEtag     the entity tag of the index, sent as {@code If-None-Match} - but only after a complete
 *                      run, see {@link #conditionalIndexEtag()}
 * @param complete      whether the last run got through its whole list and stored or confirmed every entry in
 *                      it, which is what makes the index tag trustworthy. An index that answers "not modified"
 *                      says the data is unchanged, not that everything in it was fetched, so a run that could
 *                      not replicate an entry must not trust that answer next time: the entry it missed would
 *                      never be offered again
 * @param itemCount     how many resources of this kind are stored
 * @param lastAttemptAt when a run last started, successful or not
 * @param lastSuccessAt when a run last succeeded, or null if none ever has
 * @param lastOutcome   what the last run did, or null if none ever has
 * @param failureReason why the last run failed, or null if it did not
 */
public record GovernanceImportState(
        GovernanceResourceKind kind,
        String indexEtag,
        boolean complete,
        int itemCount,
        Instant lastAttemptAt,
        Instant lastSuccessAt,
        GovernanceImportOutcome lastOutcome,
        String failureReason) {

    /**
     * The state of a kind that has never been imported.
     */
    public static GovernanceImportState none(GovernanceResourceKind kind) {
        return new GovernanceImportState(kind, null, false, 0, null, null, null, null);
    }

    public GovernanceImportState withIndexEtag(String indexEtag) {
        return new GovernanceImportState(kind, indexEtag, complete, itemCount, lastAttemptAt, lastSuccessAt,
                lastOutcome, failureReason);
    }

    public boolean hasEverSucceeded() {
        return lastSuccessAt != null;
    }

    /**
     * The entity tag to send as {@code If-None-Match} on the index, or null when the last run left something
     * unfetched and its answer may therefore not be trusted.
     */
    public String conditionalIndexEtag() {
        return complete ? indexEtag : null;
    }
}
