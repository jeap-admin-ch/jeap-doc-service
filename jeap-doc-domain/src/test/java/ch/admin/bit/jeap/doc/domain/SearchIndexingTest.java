package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.BuiltSearchIndex;
import ch.admin.bit.jeap.doc.domain.port.ExclusiveWork;
import ch.admin.bit.jeap.doc.domain.port.PartPublication;
import ch.admin.bit.jeap.doc.domain.port.PublishedSearchIndex;
import ch.admin.bit.jeap.doc.domain.port.SearchIndexBuilder;
import ch.admin.bit.jeap.doc.domain.port.SearchIndexRepository;
import ch.admin.bit.jeap.doc.domain.port.SiteBuildException;
import ch.admin.bit.jeap.doc.domain.port.SitePublicationStorage;
import ch.admin.bit.jeap.doc.domain.port.PublishedSite;
import ch.admin.bit.jeap.doc.domain.port.StoredObject;
import org.junit.jupiter.api.BeforeEach;
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
 * When the documentation is indexed, and what happens when it cannot be.
 * <p>
 * The cases that matter are the ones nobody would see going wrong: two instances indexing the same site at
 * once, and a run that fails taking the search down with it instead of leaving the last index serving.
 */
class SearchIndexingTest {

    private static final Instant NOW = Instant.parse("2026-09-08T10:25:00Z");
    private static final Path BUNDLE = Path.of("/tmp/search-index-default/pagefind");

    private final RecordingIndexes indexes = new RecordingIndexes();
    private final RecordingStorage storage = new RecordingStorage();
    private final SearchProperties properties = new SearchProperties();
    private final PublicationRecord publications = new PublicationRecord();

    private StubBuilder builder;
    private SearchIndexing indexing;

    @BeforeEach
    void setUp() {
        builder = new StubBuilder();
        indexing = indexingWith(new AlwaysTheLock());
    }

    private SearchIndexing indexingWith(ExclusiveWork lock) {
        return new SearchIndexing(builder, indexes, publications, storage, properties,
                Clock.fixed(NOW, ZoneOffset.UTC), lock);
    }

    @Test
    void index_thenTheWholeSiteIsIndexedAndPublished() {
        indexing.index(site());

        assertThat(builder.indexed).describedAs("the whole site, not one part of it")
                .singleElement().satisfies(part -> {
                    assertThat(part.carriesWholeEnvironments()).isTrue();
                    assertThat(part.carriesSystems()).isTrue();
                    assertThat(part.environments())
                            .containsExactlyElementsOf(site().environments().stream().map(e -> e.id()).toList());
                });
        assertThat(storage.published).containsExactly("default/search/1");
        assertThat(indexes.published).containsExactly("default/search/1");
    }

    /** The workspace is scratch space of tens of megabytes; a run that leaked one per hour would fill a task. */
    @Test
    void index_thenTheWorkspaceIsDiscardedAfterwards() {
        indexing.index(site());

        assertThat(builder.discarded).isEqualTo(1);
    }

    /**
     * Of several instances, one indexes. Not because two would be wrong in the end - the second would publish
     * a good index too - but because it is the whole site's content pass twice over, on a container that is
     * CPU-bound whenever a publication is running.
     */
    @Test
    void index_whenAnotherInstanceIsIndexingThatSite_thenThisOneDoesNothing() {
        SearchIndexing other = indexingWith(new NeverTheLock());

        assertThat(other.index(site())).isFalse();
        assertThat(builder.indexed).isEmpty();
        assertThat(indexes.started).isZero();
    }

    /**
     * A failure is recorded and swallowed. The index published before it goes on being served, and a schedule
     * that comes round every hour is not something to fail.
     */
    @Test
    void index_whenTheIndexCannotBeBuilt_thenItIsRecordedAndTheSearchKeepsWorking() {
        builder.failWith(new SiteBuildException("the indexer exited with 1"));

        assertThat(indexing.index(site())).isTrue();

        assertThat(indexes.failed).containsExactly("the indexer exited with 1");
        assertThat(storage.published).describedAs("nothing is published from a run that failed").isEmpty();
    }

    /** And it still gives the workspace back - a failing run leaks nothing. */
    @Test
    void index_whenTheIndexCannotBeBuilt_thenNoWorkspaceIsLeftBehind() {
        builder.failWith(new SiteBuildException("no"));

        indexing.index(site());

        assertThat(builder.discarded).describedAs("nothing was built, so there is nothing to discard").isZero();
    }

    /**
     * The index that was replaced outlives the swap. Every file the indexer writes is named by a content hash,
     * so a reader whose browser holds the old manifest is still fetching chunks that exist only under the old
     * prefix - and the retention is what stops the next keystroke being a 404.
     */
    @Test
    void index_thenOnlyTheIndexesBeyondTheRetentionAreRemoved() {
        indexes.superseded.add(supersededIndex());

        indexing.index(site());

        assertThat(indexes.askedToKeep).isEqualTo(properties.getRetention());
        assertThat(storage.deleted).containsExactly("default/search/7");
        assertThat(indexes.forgotten).containsExactly(7L);
    }

    /**
     * A prefix that will not delete costs storage. Failing the run over it would cost the search, and the row
     * stays so the next run offers it again.
     */
    @Test
    void index_whenASupersededIndexCannotBeRemoved_thenTheIndexIsStillPublished() {
        indexes.superseded.add(supersededIndex());
        storage.failToDelete = true;

        indexing.index(site());

        assertThat(indexes.published).containsExactly("default/search/1");
        assertThat(indexes.forgotten).isEmpty();
    }

    /**
     * <b>And the row that was just published stays published.</b> Reading the superseded indexes is clean-up
     * that comes after the swap, so a database blip in it may not be reported as a failed index run: that
     * would take the site back to the index before this one and leave housekeeping to delete a bundle that is
     * being served.
     */
    @Test
    void index_whenTheSupersededIndexesCannotBeRead_thenTheRunIsStillRecordedAsPublished() {
        indexes.failToReadSuperseded = true;

        assertThat(indexing.index(site())).isTrue();

        assertThat(indexes.published).containsExactly("default/search/1");
        assertThat(indexes.failed).describedAs("the index was published; nothing about it failed").isEmpty();
    }

    /**
     * <b>One index per publication, not one per instance.</b> The parts of a publication are built by several
     * instances and each of them indexes when its own pass ends, so the lock alone - taken and released per
     * run - would have the second instance index the same content again a moment later and supersede a bundle
     * that is minutes old. What decides is whether anything has been published since the current index began.
     */
    @Test
    void index_whenTheCurrentIndexIsNewerThanTheNewestPublication_thenNothingIsBuilt() {
        publications.lastSuccessAt = NOW.minus(Duration.ofMinutes(10));
        indexes.current = indexBuiltAfter(NOW.minus(Duration.ofMinutes(5)));

        assertThat(indexing.index(site())).isTrue();

        assertThat(builder.indexed).isEmpty();
        assertThat(indexes.started).describedAs("not even a row: there was nothing to record").isZero();
    }

    /**
     * And the other way round it does index. The content of a run is written when the run starts, so a part
     * published while the last index was being built is not in it, whatever time that index finished at.
     */
    @Test
    void index_whenSomethingWasPublishedAfterTheCurrentIndexBegan_thenItIsIndexedAgain() {
        publications.lastSuccessAt = NOW.minus(Duration.ofMinutes(5));
        indexes.current = indexBuiltAfter(NOW.minus(Duration.ofMinutes(10)));

        indexing.index(site());

        assertThat(builder.indexed).hasSize(1);
        assertThat(indexes.published).containsExactly("default/search/1");
    }

    @Test
    void index_whenSearchIsSwitchedOff_thenNothingIsIndexed() {
        properties.setEnabled(false);

        assertThat(indexing.index(site())).isFalse();
        assertThat(indexes.started).isZero();
    }

    /** A retention of one is a reader's 404 an hour later, so an instance may not be configured with it. */
    @Test
    void check_whenOnlyOneIndexWouldBeKept_thenTheStartupFails() {
        properties.setRetention(1);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(properties::check))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jeap.doc.search.retention");
    }

    private static Site site() {
        return new DocumentationSites(new SiteProperties()).find(Site.DEFAULT_SITE).orElseThrow();
    }

    private static PublishedSearchIndex supersededIndex() {
        return new PublishedSearchIndex(7, "default/search/7", 100, NOW.minusSeconds(7500),
                NOW.minusSeconds(7200));
    }

    /** The index a site is served from, as a run that began at the given instant. */
    private static PublishedSearchIndex indexBuiltAfter(Instant startedAt) {
        return new PublishedSearchIndex(9, "default/search/9", 100, startedAt, startedAt.plusSeconds(120));
    }

    /** What the site has published, which is what an index has to cover to be up to date. */
    private static final class PublicationRecord extends NoBuildHistory {

        private Instant lastSuccessAt;

        @Override
        public Optional<Instant> lastSuccessAt(String site) {
            return Optional.ofNullable(lastSuccessAt);
        }
    }

    private static final class StubBuilder implements SearchIndexBuilder {

        private final List<SitePart> indexed = new ArrayList<>();
        private int discarded;
        private RuntimeException failure;

        void failWith(RuntimeException e) {
            failure = e;
        }

        @Override
        public BuiltSearchIndex build(Site site, SitePart part) {
            if (failure != null) {
                throw failure;
            }
            indexed.add(part);
            return new BuiltSearchIndex(BUNDLE, 4711, 6100);
        }

        @Override
        public void discard(BuiltSearchIndex index) {
            discarded++;
        }
    }

    private static final class RecordingIndexes implements SearchIndexRepository {

        private int started;
        private final List<String> published = new ArrayList<>();
        private final List<String> failed = new ArrayList<>();
        private final List<Long> forgotten = new ArrayList<>();
        private final List<PublishedSearchIndex> superseded = new ArrayList<>();
        private PublishedSearchIndex current;
        private boolean failToReadSuperseded;
        private int askedToKeep;

        @Override
        public long start(String site, String instance, Instant startedAt) {
            return ++started;
        }

        @Override
        public void published(long id, String objectPrefix, int records, Instant finishedAt) {
            published.add(objectPrefix);
        }

        @Override
        public void failed(long id, String reason, Instant finishedAt) {
            failed.add(reason);
        }

        @Override
        public Optional<PublishedSearchIndex> currentOf(String site) {
            return Optional.ofNullable(current);
        }

        @Override
        public List<PublishedSearchIndex> supersededOf(String site, int keep) {
            if (failToReadSuperseded) {
                throw new IllegalStateException("the database said no");
            }
            askedToKeep = keep;
            return List.copyOf(superseded);
        }

        @Override
        public List<ch.admin.bit.jeap.doc.domain.port.AbandonedSearchIndex> abandoned(
                Instant runningStartedBefore, Instant failedFinishedBefore) {
            // What a killed run leaves behind is SearchIndexHousekeepingTest's business.
            return List.of();
        }

        @Override
        public void forget(long id) {
            forgotten.add(id);
        }
    }

    private static final class RecordingStorage implements SitePublicationStorage {

        private final List<String> published = new ArrayList<>();
        private final List<String> deleted = new ArrayList<>();
        private boolean failToDelete;

        @Override
        public PublishedSite publish(PartPublication where, Path directory) {
            published.add(where.prefix());
            return new PublishedSite(where.prefix(), 1, 1L);
        }

        @Override
        public Optional<StoredObject> open(String prefix, String path) {
            return Optional.empty();
        }

        @Override
        public boolean exists(String prefix, String path) {
            return false;
        }

        @Override
        public void delete(String prefix) {
            if (failToDelete) {
                throw new IllegalStateException("the bucket said no");
            }
            deleted.add(prefix);
        }
    }

    /** This instance gets the lock, which is the ordinary case. */
    private static final class AlwaysTheLock implements ExclusiveWork {
        @Override
        public <T> Optional<T> underLock(String name, Duration lease, Supplier<T> work) {
            return Optional.ofNullable(work.get());
        }
    }

    /** Another instance holds it. */
    private static final class NeverTheLock implements ExclusiveWork {
        @Override
        public <T> Optional<T> underLock(String name, Duration lease, Supplier<T> work) {
            return Optional.empty();
        }
    }
}
