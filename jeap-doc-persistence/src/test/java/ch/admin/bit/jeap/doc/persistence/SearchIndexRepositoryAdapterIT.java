package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.SearchIndex;
import ch.admin.bit.jeap.doc.domain.port.PublishedSearchIndex;
import ch.admin.bit.jeap.doc.domain.port.SearchIndexRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchIndexRepositoryAdapterIT extends PostgresTestContainerBase {

    private static final Instant NOW = Instant.parse("2026-09-08T09:00:00Z");
    private static final String INSTANCE = "doc-service-1";

    @Autowired
    private SearchIndexRepository indexes;

    /** The rows themselves, for the one question the port has no reason to answer: what a failure recorded. */
    @Autowired
    private SearchIndexJpaRepository rows;

    /**
     * A site of this test's own. The database is shared by every test of this module and nothing rolls back,
     * so a test that assumed an empty table would pass alone and fail in the suite - and the site is the
     * natural thing to separate them by, because it is what every one of these questions is asked about.
     */
    private String site;

    @BeforeEach
    void setUp(TestInfo test) {
        site = test.getTestMethod().orElseThrow().getName();
    }

    /** Nothing is served until something has been indexed, and that is not a failure. */
    @Test
    void currentOf_whenNothingWasEverIndexed_thenEmpty() {
        assertThat(indexes.currentOf(site)).isEmpty();
    }

    /** A run that is still going is not something to serve: half an index is worse than the one before it. */
    @Test
    void currentOf_whileTheFirstIndexIsStillBeingBuilt_thenEmpty() {
        indexes.start(site, INSTANCE, NOW);

        assertThat(indexes.currentOf(site)).isEmpty();
    }

    @Test
    void published_thenTheSiteIsServedFromIt() {
        long id = indexes.start(site, INSTANCE, NOW);

        indexes.published(id, SearchIndex.prefixOf(site, id), 4711, NOW.plusSeconds(30));

        assertThat(indexes.currentOf(site)).get().satisfies(index -> {
            assertThat(index.id()).isEqualTo(id);
            assertThat(index.objectPrefix()).isEqualTo(site + "/search/" + id);
            assertThat(index.records()).isEqualTo(4711);
            // Both instants, and the run's start is not decoration: it is what says whether the index covers
            // a publication, because a run writes the content it indexes when it begins.
            assertThat(index.startedAt()).isEqualTo(NOW);
            assertThat(index.builtAt()).isEqualTo(NOW.plusSeconds(30));
        });
    }

    @Test
    void currentOf_whenSeveralWerePublished_thenTheNewestIsServed() {
        long older = publish(1000);
        long newer = publish(1100);

        assertThat(indexes.currentOf(site)).get()
                .extracting(PublishedSearchIndex::id).isEqualTo(newer);
        assertThat(older).isLessThan(newer);
    }

    /** What was published goes on being served, which is the whole point of recording a failure rather than throwing. */
    @Test
    void failed_thenWhatWasPublishedBeforeIsStillServed() {
        long published = publish(2000);

        long failing = indexes.start(site, INSTANCE, NOW.plusSeconds(2100));
        indexes.failed(failing, "the indexer exited with 1", NOW.plusSeconds(2110));

        assertThat(indexes.currentOf(site)).get().extracting(PublishedSearchIndex::id).isEqualTo(published);
    }

    /**
     * The index that was current a moment ago has to outlive the swap. Every file the indexer writes carries a
     * content hash in its name, so a reader whose browser holds the old manifest is still asking for chunks
     * that exist only under the old prefix.
     */
    @Test
    void supersededOf_thenTheNewestAreKeptAndTheRestAreOfferedForRemoval() {
        long first = publish(3000);
        long second = publish(3100);
        long third = publish(3200);

        assertThat(indexes.supersededOf(site, 2)).extracting(PublishedSearchIndex::id)
                .describedAs("the current one and the one it replaced are kept").containsExactly(first);
        assertThat(indexes.supersededOf(site, 3)).isEmpty();
        assertThat(second).isLessThan(third);
    }

    @Test
    void supersededOf_thenAFailedRunIsNotOneToRemove() {
        publish(4000);
        long failing = indexes.start(site, INSTANCE, NOW.plusSeconds(4100));
        indexes.failed(failing, "no", NOW.plusSeconds(4110));

        assertThat(indexes.supersededOf(site, 1)).isEmpty();
    }

    /** One site's index is not another's, whatever the order they were built in. */
    @Test
    void currentOf_thenOneSiteDoesNotSeeAnothersIndex() {
        String otherSite = site + "-handbook";
        long other = indexes.start(otherSite, INSTANCE, NOW);
        indexes.published(other, SearchIndex.prefixOf(otherSite, other), 12, NOW.plusSeconds(5));

        assertThat(indexes.currentOf(site)).isEmpty();
        assertThat(indexes.currentOf(otherSite)).get().extracting(PublishedSearchIndex::id).isEqualTo(other);
    }

    @Test
    void forget_thenItIsNoLongerOfferedForRemoval() {
        publish(6000);
        publish(6100);
        PublishedSearchIndex superseded = indexes.supersededOf(site, 1).getFirst();

        indexes.forget(superseded.id());

        assertThat(indexes.supersededOf(site, 1)).isEmpty();
    }

    /**
     * The generator's output can be a thousand lines of stack. The row is kept, so what is stored is the end of
     * it - the part that says what went wrong - and the whole transcript stays in the instance's log.
     */
    @Test
    void failed_whenTheReasonIsEnormous_thenItIsStoredAndTheEndIsKept() {
        long id = indexes.start(site, INSTANCE, NOW);

        indexes.failed(id, "x".repeat(SearchIndexRepositoryAdapter.MAX_FAILURE_REASON * 2) + "the real reason",
                NOW.plusSeconds(10));

        String stored = rows.findById(id).orElseThrow().getFailureReason();
        assertThat(stored).hasSize(SearchIndexRepositoryAdapter.MAX_FAILURE_REASON);
        assertThat(stored).endsWith("the real reason");
    }

    @Test
    void failed_thenTheReasonIsOnTheRow() {
        long id = indexes.start(site, INSTANCE, NOW);

        indexes.failed(id, "the indexer exited with 1", NOW.plusSeconds(10));

        assertThat(rows.findById(id).orElseThrow()).satisfies(row -> {
            assertThat(row.getState()).isEqualTo(SearchIndexState.FAILED);
            assertThat(row.getFailureReason()).isEqualTo("the indexer exited with 1");
            assertThat(row.getFinishedAt()).isEqualTo(NOW.plusSeconds(10));
        });
    }

    /**
     * <b>A published run is never abandoned, whatever its age.</b> This is the assertion that matters most in
     * the class: what a site is served from is a published run, and an age rule that could reach one would
     * take the search off a site nobody has had to republish.
     */
    @Test
    void abandoned_thenAPublishedRunIsNeverOfferedForRemovalHoweverOldItIs() {
        publish(-1_000_000);

        assertThat(abandonedOfThisSite(NOW, NOW)).isEmpty();
    }

    /** An instance killed between writing the files and recording that it had leaves the row on RUNNING. */
    @Test
    void abandoned_thenARunStillRunningLongEnoughAgoIsOffered() {
        long killed = indexes.start(site, INSTANCE, NOW.minusSeconds(7200));

        assertThat(abandonedOfThisSite(NOW.minusSeconds(3600), NOW.minusSeconds(3600)))
                .extracting(ch.admin.bit.jeap.doc.domain.port.AbandonedSearchIndex::id)
                .containsExactly(killed);
    }

    /** And a run that started a moment ago is one that is very probably still writing. */
    @Test
    void abandoned_thenARunThatStartedRecentlyIsLeftAlone() {
        indexes.start(site, INSTANCE, NOW.minusSeconds(60));

        assertThat(abandonedOfThisSite(NOW.minusSeconds(3600), NOW.minusSeconds(3600))).isEmpty();
    }

    @Test
    void abandoned_thenAFailedRunIsOfferedOnlyOnceItsRetentionHasPassed() {
        long failed = indexes.start(site, INSTANCE, NOW.minusSeconds(7200));
        indexes.failed(failed, "the indexer exited with 1", NOW.minusSeconds(7100));

        assertThat(abandonedOfThisSite(NOW.minusSeconds(9000), NOW.minusSeconds(9000)))
                .describedAs("it failed more recently than the retention").isEmpty();
        assertThat(abandonedOfThisSite(NOW.minusSeconds(9000), NOW.minusSeconds(3600)))
                .extracting(ch.admin.bit.jeap.doc.domain.port.AbandonedSearchIndex::id)
                .containsExactly(failed);
    }

    /** The run carries the site, because the prefix its files are under is worked out from the two. */
    @Test
    void abandoned_thenTheRunSaysWhichSiteItWasIndexing() {
        indexes.start(site, INSTANCE, NOW.minusSeconds(7200));

        assertThat(abandonedOfThisSite(NOW, NOW))
                .extracting(ch.admin.bit.jeap.doc.domain.port.AbandonedSearchIndex::site)
                .containsExactly(site);
    }

    /**
     * What the query offers, of this test's own site.
     * <p>
     * <b>The query itself is deliberately across every site</b> - one nightly pass clears the lot - and this
     * module's tests share a database, so an assertion over everything it returns would be an assertion about
     * whichever tests ran first.
     */
    private List<ch.admin.bit.jeap.doc.domain.port.AbandonedSearchIndex> abandonedOfThisSite(
            Instant runningStartedBefore, Instant failedFinishedBefore) {
        return indexes.abandoned(runningStartedBefore, failedFinishedBefore).stream()
                .filter(run -> run.site().equals(site))
                .toList();
    }

    private long publish(int secondsFromNow) {
        long id = indexes.start(site, INSTANCE, NOW.plusSeconds(secondsFromNow));
        indexes.published(id, SearchIndex.prefixOf(site, id), 100, NOW.plusSeconds(secondsFromNow + 10));
        return id;
    }
}
