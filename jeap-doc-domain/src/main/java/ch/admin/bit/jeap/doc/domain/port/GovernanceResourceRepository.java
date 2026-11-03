package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.governance.GovernanceResource;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceResourceKind;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceResourceRef;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Where the resources read from the Governance Service's {@code /docs-api} are kept.
 */
public interface GovernanceResourceRepository {

    /**
     * Every stored resource of one kind, <b>without its content</b>: what deciding which resources to fetch
     * works on. For {@link GovernanceResourceKind#SYSTEMS_INDEX} and {@link GovernanceResourceKind#RULES_INDEX}
     * this is at most the one row that kind ever has.
     */
    List<GovernanceResourceRef> findRefs(GovernanceResourceKind kind);

    /**
     * The content of one resource, by its key - null for the two indexes.
     */
    Optional<GovernanceResource> find(GovernanceResourceKind kind, String key);

    void store(GovernanceResource resource);

    /**
     * Runs the reads in {@code reads} against one snapshot of the stored resources, so that an import
     * committing in between is seen by none of them or by all. A build reads an index and then the resources it
     * lists, and the two must be from the same import.
     */
    <T> T readConsistently(Supplier<T> reads);

    /**
     * Stores an index with the resources it lists and removes the ones it no longer lists, <b>all or nothing</b>:
     * what a reader sees is either the state before or the state after.
     */
    void replace(GovernanceResource index, Collection<GovernanceResource> resources,
                 Collection<GovernanceResourceRef> removed);

    /**
     * Records that the stored copy is still the current one, without rewriting its content.
     */
    void confirm(GovernanceResourceKind kind, String key, Instant checkedAt);

    void remove(Collection<GovernanceResourceRef> resources);
}
