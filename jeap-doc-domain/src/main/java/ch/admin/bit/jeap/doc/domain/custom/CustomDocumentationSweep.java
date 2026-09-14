package ch.admin.bit.jeap.doc.domain.custom;

import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationRepository;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationStorage;
import ch.admin.bit.jeap.doc.domain.port.ExclusiveWork;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Removes the objects under the current documentation that no set names.
 * <p>
 * <b>It selects on references, never on age.</b> A set's bundle is the only copy there is and a component that
 * publishes once and stays stable for a year is the normal case, so nothing here may be removed for being
 * old. What is safe to remove is an object nothing points at: the rows are what name an object, so one that
 * no row names can never be read again.
 * <p>
 * Three things leave such an object behind. A set that is replaced writes a new object, and its predecessor is
 * dereferenced - the upload deletes it, and this takes it if that failed. An attempt that was given up on and
 * kept running copies an object the upload that took over never names. And an instance that dies between
 * copying the object and committing the rows leaves an object nothing ever named.
 * <p>
 * <b>An object still has to be old enough to judge.</b> An upload copies its object before it commits the
 * rows that name it, so an object written moments ago may legitimately have no row yet. Only objects older
 * than {@link #YOUNG_ENOUGH_TO_STILL_BE_ARRIVING} are considered, which is not an expiry: it is the
 * difference between an orphan and an upload that is still in flight.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomDocumentationSweep {

    static final String LOCK = "customDocumentationSweep";

    /** How long the lock of this nightly job survives an instance that dies holding it. */
    private static final Duration LEASE = Duration.ofMinutes(30);

    /**
     * How recently an object may have been written and still be taken for one an upload is about to name. Far
     * longer than an upload takes, because the cost of being wrong is a team's documentation and the cost of
     * waiting is one night.
     */
    static final Duration YOUNG_ENOUGH_TO_STILL_BE_ARRIVING = Duration.ofHours(6);

    private final CustomDocumentationRepository documentation;
    private final CustomDocumentationStorage storage;
    private final ExclusiveWork exclusiveWork;
    private final Clock clock;

    /** Of several instances, one runs this and the others find the lock taken. */
    public void removeUnreferencedObjects() {
        exclusiveWork.underLock(LOCK, LEASE, this::removeUnreferencedObjectsNow);
    }

    private void removeUnreferencedObjectsNow() {
        // The objects first, then the rows. An upload that commits its rows between the two reads has them in
        // the referenced set; one that commits after is spared by the age bound above.
        List<String> stored =
                storage.listWrittenBefore(clock.instant().minus(YOUNG_ENOUGH_TO_STILL_BE_ARRIVING));
        List<String> named = documentation.allObjectKeys();
        Set<String> referenced = new HashSet<>(named);
        // An HTML set's row names the prefix its files lie under rather than one object, so everything
        // beneath it is referenced. Without this every file of every microsite would be unreferenced, and
        // the first sweep after an upload would delete a team's documentation six hours later.
        List<String> referencedPrefixes = named.stream().filter(key -> key.endsWith("/")).toList();
        int unreferenced = 0;
        int removed = 0;
        for (String objectKey : stored) {
            if (referenced.contains(objectKey)
                || referencedPrefixes.stream().anyMatch(objectKey::startsWith)) {
                continue;
            }
            unreferenced++;
            if (delete(objectKey)) {
                removed++;
            }
        }
        if (unreferenced > 0) {
            log.info("Removed {} of the {} object(s) under the current documentation that no set names, of {} "
                     + "in all.", removed, unreferenced, stored.size());
        } else {
            log.debug("Every one of the {} object(s) under the current documentation is named by a set.",
                    stored.size());
        }
    }

    /** Answers whether the object was removed, which is whether the storage took the request at all. */
    private boolean delete(String objectKey) {
        try {
            log.info("The object {} is named by no documentation set and is removed.", objectKey);
            storage.delete(objectKey);
            return true;
        } catch (RuntimeException e) {
            log.warn("The unreferenced object {} could not be removed. The next run tries again.", objectKey, e);
            return false;
        }
    }
}
