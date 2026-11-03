package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.governance.GovernanceResource;

/**
 * What one conditional fetch of a system or rule resource came back with.
 * <p>
 * Three answers, because the import has to tell them apart: content to store, a "not modified" that confirms
 * the stored copy, and a resource that could not be replicated - gone between the index and the fetch, or an
 * answer without an entity tag. The third is not the second: a confirmed copy is stored, a skipped resource is
 * not, and the index tag may not be trusted after one, or the resource would never be offered again.
 */
public sealed interface GovernanceResourceFetch {

    /** Content that is newer than what is stored, or that nothing was stored for yet. */
    record Stored(GovernanceResource resource) implements GovernanceResourceFetch {
    }

    /** The upstream answered "not modified" against the tag of the stored copy: it is still current. */
    record Unchanged() implements GovernanceResourceFetch {
    }

    /** The resource was not replicated. The reason has been logged where it was found. */
    record Skipped(String reason) implements GovernanceResourceFetch {
    }

    static GovernanceResourceFetch stored(GovernanceResource resource) {
        return new Stored(resource);
    }

    static GovernanceResourceFetch unchanged() {
        return new Unchanged();
    }

    static GovernanceResourceFetch skipped(String reason) {
        return new Skipped(reason);
    }
}
