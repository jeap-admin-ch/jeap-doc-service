package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.AbandonedSearchIndex;
import ch.admin.bit.jeap.doc.domain.port.ExclusiveWork;
import ch.admin.bit.jeap.doc.domain.port.PublishedSearchIndex;
import ch.admin.bit.jeap.doc.domain.port.SearchIndexRepository;
import ch.admin.bit.jeap.doc.domain.port.SitePublicationStorage;
import ch.admin.bit.jeap.doc.domain.port.PublishedSite;
import ch.admin.bit.jeap.doc.domain.port.PartPublication;
import ch.admin.bit.jeap.doc.domain.port.StoredObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an index run that never finished leaves behind, and who removes it.
 * <p>
 * The case this exists for is invisible: an instance killed between writing an index and recording that it had
 * leaves a prefix in the object storage and a row that says it is still running, and <b>nothing else in the
 * service can reach either</b> - the run is never published, so it is never superseded, so nothing offers it
 * for removal.
 */
class SearchIndexHousekeepingTest {

    private static final Instant NOW = Instant.parse("2026-09-09T02:45:00Z");

    private final RecordingIndexes indexes = new RecordingIndexes();
    private final RecordingStorage storage = new RecordingStorage();
    private final SearchProperties properties = new SearchProperties();

    private final SearchIndexHousekeeping housekeeping = new SearchIndexHousekeeping(indexes, storage,
            properties, Clock.fixed(NOW, ZoneOffset.UTC), new AlwaysTheLock());

    @Test
    void removeAbandonedRuns_thenTheFilesGoBeforeTheRow() {
        indexes.abandoned.add(new AbandonedSearchIndex(7, "default"));

        housekeeping.removeAbandonedRuns();

        assertThat(storage.deleted).containsExactly("default/search/7");
        assertThat(indexes.forgotten).containsExactly(7L);
    }

    /**
     * <b>The prefix is worked out from the run, not read from it.</b> A run that was killed before it
     * published never recorded where it had written - and it did not need to, because a run's prefix follows
     * from its identifier. Without that, what a crash leaves in the bucket could not be found at all.
     */
    @Test
    void removeAbandonedRuns_whenTheRunNeverRecordedAPrefix_thenItsFilesAreStillFound() {
        indexes.abandoned.add(new AbandonedSearchIndex(11, "governance"));

        housekeeping.removeAbandonedRuns();

        assertThat(storage.deleted).containsExactly(SearchIndex.prefixOf("governance", 11));
    }

    /** The two cut-offs it asks with, which are what stop it taking a run that is still writing. */
    @Test
    void removeAbandonedRuns_thenARunningRunIsOnlyOldEnoughLongAfterTheLockCouldHaveHeld() {
        housekeeping.removeAbandonedRuns();

        assertThat(indexes.runningStartedBefore).isEqualTo(NOW.minus(properties.getAbandonedAfter()));
        assertThat(indexes.failedFinishedBefore).isEqualTo(NOW.minus(properties.getFailureRetention()));
        assertThat(properties.getAbandonedAfter()).isGreaterThan(properties.getLockLease());
    }

    /**
     * A prefix that will not delete costs storage; giving up on the rest of the list over it would cost more
     * of it. The row stays, so the next night offers the same run again.
     */
    @Test
    void removeAbandonedRuns_whenOneWillNotDelete_thenTheRestAreStillRemoved() {
        indexes.abandoned.add(new AbandonedSearchIndex(1, "default"));
        indexes.abandoned.add(new AbandonedSearchIndex(2, "default"));
        storage.refuse = "default/search/1";

        housekeeping.removeAbandonedRuns();

        assertThat(indexes.forgotten).describedAs("the one that would not delete keeps its row")
                .containsExactly(2L);
    }

    @Test
    void removeAbandonedRuns_whenAnotherInstanceIsDoingIt_thenThisOneDoesNothing() {
        indexes.abandoned.add(new AbandonedSearchIndex(7, "default"));
        SearchIndexHousekeeping other = new SearchIndexHousekeeping(indexes, storage, properties,
                Clock.fixed(NOW, ZoneOffset.UTC), new NeverTheLock());

        other.removeAbandonedRuns();

        assertThat(storage.deleted).isEmpty();
    }

    @Test
    void removeAbandonedRuns_whenSearchIsSwitchedOff_thenNothingIsAsked() {
        properties.setEnabled(false);

        housekeeping.removeAbandonedRuns();

        assertThat(indexes.asked).isFalse();
    }

    /**
     * <b>A retention of one is refused, and so is an abandonment threshold inside the lock lease.</b> Treating
     * a run as dead while it may still hold its lock would delete the files of a run that is still writing
     * them, which is a corrupt index rather than a missing one.
     */
    @Test
    void check_whenARunWouldBeAbandonedWhileItCouldStillHoldItsLock_thenTheStartupFails() {
        properties.setAbandonedAfter(properties.getLockLease());

        assertThat(org.assertj.core.api.Assertions.catchThrowable(properties::check))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jeap.doc.search.abandoned-after");
    }

    private static final class RecordingIndexes implements SearchIndexRepository {

        private final List<AbandonedSearchIndex> abandoned = new ArrayList<>();
        private final List<Long> forgotten = new ArrayList<>();
        private Instant runningStartedBefore;
        private Instant failedFinishedBefore;
        private boolean asked;

        @Override
        public long start(String site, String instance, Instant startedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void published(long id, String objectPrefix, int records, Instant finishedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void failed(long id, String reason, Instant finishedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<PublishedSearchIndex> currentOf(String site) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PublishedSearchIndex> supersededOf(String site, int keep) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AbandonedSearchIndex> abandoned(Instant runningBefore, Instant failedBefore) {
            asked = true;
            runningStartedBefore = runningBefore;
            failedFinishedBefore = failedBefore;
            return List.copyOf(abandoned);
        }

        @Override
        public void forget(long id) {
            forgotten.add(id);
        }
    }

    private static final class RecordingStorage implements SitePublicationStorage {

        private final List<String> deleted = new ArrayList<>();
        private String refuse;

        @Override
        public PublishedSite publish(PartPublication where, Path directory) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<StoredObject> open(String prefix, String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean exists(String prefix, String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String prefix) {
            if (prefix.equals(refuse)) {
                throw new IllegalStateException("the bucket said no");
            }
            deleted.add(prefix);
        }
    }

    private static final class AlwaysTheLock implements ExclusiveWork {
        @Override
        public <T> Optional<T> underLock(String name, Duration lease, Supplier<T> work) {
            return Optional.ofNullable(work.get());
        }
    }

    private static final class NeverTheLock implements ExclusiveWork {
        @Override
        public <T> Optional<T> underLock(String name, Duration lease, Supplier<T> work) {
            return Optional.empty();
        }
    }
}
