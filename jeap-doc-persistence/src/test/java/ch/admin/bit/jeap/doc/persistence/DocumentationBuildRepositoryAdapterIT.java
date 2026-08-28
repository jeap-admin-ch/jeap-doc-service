package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.BuildState;
import ch.admin.bit.jeap.doc.domain.BuildTrigger;
import ch.admin.bit.jeap.doc.domain.DocumentationBuild;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentationBuildRepositoryAdapterIT extends PostgresTestContainerBase {

    private static final Instant NOW = Instant.parse("2026-08-25T09:00:00Z");
    private static final String INSTANCE = "doc-service-1";

    @Autowired
    private DocumentationBuildRepository builds;

    @Test
    void start_thenRunningWithAnIdentifierOfItsOwn() {
        DocumentationBuild build = builds.start(site("started"), BuildTrigger.SCHEDULE, INSTANCE, NOW);

        assertThat(build.id()).isNotNull();
        assertThat(build.state()).isEqualTo(BuildState.RUNNING);
        assertThat(build.trigger()).isEqualTo(BuildTrigger.SCHEDULE);
        assertThat(build.finishedAt()).isNull();
        assertThat(builds.runningIds()).contains(build.id());
    }

    @Test
    void succeeded_thenTheBuildIsThePublishedOne() {
        String site = site("published");
        DocumentationBuild first = builds.start(site, BuildTrigger.SCHEDULE, INSTANCE, NOW);
        builds.succeeded(first.id(), "sites/" + site + "/" + first.id(), 12, 4096, 3000, NOW.plusSeconds(30));

        assertThat(builds.published(site)).get().extracting(DocumentationBuild::id).isEqualTo(first.id());
        assertThat(builds.lastSuccessAt(site)).contains(NOW.plusSeconds(30));
        assertThat(builds.runningIds()).doesNotContain(first.id());
    }

    @Test
    void published_whenTheNewestBuildFailed_thenTheSitePublishedBeforeItStaysPublished() {
        String site = site("failed-after");
        DocumentationBuild good = builds.start(site, BuildTrigger.SCHEDULE, INSTANCE, NOW);
        builds.succeeded(good.id(), "sites/" + site + "/" + good.id(), 5, 100, 10, NOW.plusSeconds(10));
        DocumentationBuild bad = builds.start(site, BuildTrigger.UPLOAD, INSTANCE, NOW.plusSeconds(60));
        builds.failed(bad.id(), "npm exited with 1", NOW.plusSeconds(70));

        assertThat(builds.published(site)).get().extracting(DocumentationBuild::id).isEqualTo(good.id());
    }

    @Test
    void published_whenNothingEverSucceeded_thenEmpty() {
        String site = site("never");
        builds.failed(builds.start(site, BuildTrigger.SCHEDULE, INSTANCE, NOW).id(), "no", NOW.plusSeconds(1));

        assertThat(builds.published(site)).isEmpty();
        assertThat(builds.lastSuccessAt(site)).isEmpty();
    }

    @Test
    void abandonRunning_thenAStrandedBuildStopsLookingLikeOneInProgress() {
        String site = site("stranded");
        DocumentationBuild stranded = builds.start(site, BuildTrigger.SCHEDULE, INSTANCE, NOW);

        assertThat(builds.abandonRunning(site, NOW.plusSeconds(3600)))
                .singleElement()
                .satisfies(abandoned -> {
                    assertThat(abandoned.id()).isEqualTo(stranded.id());
                    // What triggered it decides whether the site is built again straight away, so the caller is
                    // handed the builds rather than a count.
                    assertThat(abandoned.trigger()).isEqualTo(BuildTrigger.SCHEDULE);
                    assertThat(abandoned.state()).isEqualTo(BuildState.ABANDONED);
                });

        assertThat(builds.runningIds()).doesNotContain(stranded.id());
        assertThat(builds.abandonRunning(site, NOW.plusSeconds(3600))).isEmpty();
    }

    @Test
    void aborted_thenTheBuildIsNeitherFailedNorPublished() {
        String site = site("aborted");
        DocumentationBuild published = builds.start(site, BuildTrigger.SCHEDULE, INSTANCE, NOW);
        builds.succeeded(published.id(), "sites/" + site + "/" + published.id(), 3, 30, 300, NOW.plusSeconds(10));
        DocumentationBuild interrupted = builds.start(site, BuildTrigger.UPLOAD, INSTANCE, NOW.plusSeconds(60));

        DocumentationBuild recorded = builds.aborted(interrupted.id(), "the instance was stopping",
                NOW.plusSeconds(61));

        assertThat(recorded.state()).isEqualTo(BuildState.ABORTED);
        assertThat(recorded.failureReason()).isEqualTo("the instance was stopping");
        assertThat(recorded.finishedAt()).isEqualTo(NOW.plusSeconds(61));
        // The site published before it is still the one being served, and the row no longer pins its workspace.
        assertThat(builds.published(site)).get().extracting(DocumentationBuild::id).isEqualTo(published.id());
        assertThat(builds.runningIds()).doesNotContain(interrupted.id());
    }

    @Test
    void sitesWithRunningBuilds_thenOnlyTheOnesThatOweABuildAndEachOnlyOnce() {
        String running = site("still-running");
        String finished = site("finished");
        builds.start(running, BuildTrigger.SCHEDULE, INSTANCE, NOW);
        builds.start(running, BuildTrigger.UPLOAD, INSTANCE, NOW.plusSeconds(1));
        builds.failed(builds.start(finished, BuildTrigger.SCHEDULE, INSTANCE, NOW).id(), "no", NOW);

        assertThat(builds.sitesWithRunningBuilds()).contains(running).doesNotContain(finished);
        assertThat(builds.sitesWithRunningBuilds().stream().filter(running::equals)).hasSize(1);
    }

    @Test
    void abandonRunning_thenAnotherSitesRunningBuildIsUntouched() {
        DocumentationBuild other = builds.start(site("untouched"), BuildTrigger.SCHEDULE, INSTANCE, NOW);

        builds.abandonRunning(site("stranded-elsewhere"), NOW);

        assertThat(builds.runningIds()).contains(other.id());
    }

    @Test
    void prefixesBeyondRetention_thenOnlyTheOnesPastTheKeptOnes() {
        String site = site("retained");
        for (int run = 0; run < 5; run++) {
            DocumentationBuild build = builds.start(site, BuildTrigger.SCHEDULE, INSTANCE, NOW.plusSeconds(run));
            builds.succeeded(build.id(), "sites/" + site + "/" + build.id(), 1, 1, 1, NOW.plusSeconds(run + 1));
        }

        assertThat(builds.prefixesBeyondRetention(site, 3)).hasSize(2);
        assertThat(builds.prefixesBeyondRetention(site, 5)).isEmpty();
        // The newest is never offered for deletion: it is the site being served.
        assertThat(builds.prefixesBeyondRetention(site, 3))
                .doesNotContain(builds.published(site).orElseThrow().objectPrefix());
    }

    @Test
    void deleteFinishedBefore_thenOnlyFinishedBuildsGo() {
        String site = site("history");
        DocumentationBuild finished = builds.start(site, BuildTrigger.SCHEDULE, INSTANCE, NOW);
        builds.succeeded(finished.id(), site + "/" + finished.id(), 1, 1, 1, NOW.plusSeconds(1));
        DocumentationBuild running = builds.start(site, BuildTrigger.SCHEDULE, INSTANCE, NOW);

        assertThat(builds.deleteFinishedBefore(NOW.plusSeconds(600), Set.of())).isPositive();

        assertThat(builds.published(site)).isEmpty();
        assertThat(builds.runningIds()).contains(running.id());
    }

    /**
     * The newest successful build of a site is not only a record, it is the publication - so a site that is
     * published rarely would otherwise lose what says it is published at all, and start answering that it has
     * never been generated.
     */
    @Test
    void deleteFinishedBefore_thenThePublishedBuildIsKeptWhateverItsAge() {
        String site = site("published-and-old");
        DocumentationBuild superseded = builds.start(site, BuildTrigger.SCHEDULE, INSTANCE, NOW);
        builds.succeeded(superseded.id(), site + "/" + superseded.id(), 1, 1, 1, NOW.plusSeconds(1));
        DocumentationBuild published = builds.start(site, BuildTrigger.UPLOAD, INSTANCE, NOW.plusSeconds(2));
        builds.succeeded(published.id(), site + "/" + published.id(), 1, 1, 1, NOW.plusSeconds(3));

        int removed = builds.deleteFinishedBefore(NOW.plusSeconds(600), Set.of(published.id()));

        assertThat(removed).isPositive();
        assertThat(builds.published(site)).get().extracting(DocumentationBuild::id).isEqualTo(published.id());
    }

    /**
     * A generator that fails can write a thousand lines of bundler stack, and the row is kept for as long as
     * the history retention says. What is stored is the end of it - the whole transcript is in the log.
     */
    @Test
    void failed_whenTheReasonIsEnormous_thenTheEndOfItIsKeptAndTheRowSaysSo() {
        String site = site("chatty-failure");
        DocumentationBuild build = builds.start(site, BuildTrigger.SCHEDULE, INSTANCE, NOW);
        String reason = "x".repeat(50_000) + "the line that actually says what went wrong";

        DocumentationBuild recorded = builds.failed(build.id(), reason, NOW.plusSeconds(10));

        assertThat(recorded.failureReason())
                .hasSizeLessThan(reason.length())
                .contains("truncated")
                .endsWith("the line that actually says what went wrong");
    }

    /**
     * Cutting between the halves of a surrogate pair leaves an unpaired surrogate, which PostgreSQL refuses when
     * it encodes to UTF-8 - turning a recorded failure into a second, unrelated one.
     */
    @Test
    void failed_whenTheReasonIsFullOfEmoji_thenItIsStillStored() {
        String site = site("emoji-failure");
        DocumentationBuild build = builds.start(site, BuildTrigger.SCHEDULE, INSTANCE, NOW);
        String reason = "\uD83D\uDCC4".repeat(20_000);

        DocumentationBuild recorded = builds.failed(build.id(), reason, NOW.plusSeconds(10));

        assertThat(recorded.failureReason()).isNotBlank();
        assertThat(builds.published(site)).isEmpty();
    }

    /**
     * A build whose lease lapsed may be marked ABANDONED by another instance while it is still running. If it
     * succeeds after all, the row must not keep a reason saying its instance stopped - that is the false
     * evidence an operator reads in the one table they read.
     */
    @Test
    void succeeded_whenAnotherInstanceHadGivenUpOnIt_thenTheFailureReasonIsGone() {
        String site = site("abandoned-then-succeeded");
        DocumentationBuild build = builds.start(site, BuildTrigger.SCHEDULE, INSTANCE, NOW);
        builds.abandonRunning(site, NOW.plusSeconds(60));

        DocumentationBuild recorded = builds.succeeded(build.id(), site + "/" + build.id(), 5, 500, 50,
                NOW.plusSeconds(120));

        assertThat(recorded.state()).isEqualTo(BuildState.SUCCEEDED);
        assertThat(recorded.failureReason()).isNull();
    }

    /**
     * Once a superseded site's objects are gone, the retention must stop offering it - otherwise every build
     * from then on lists a prefix that is already empty and logs a removal that happened long ago.
     */
    @Test
    void forgetObjectPrefix_thenTheRetentionStopsOfferingIt() {
        String site = site("forgotten");
        String firstPrefix = null;
        for (int run = 0; run < 4; run++) {
            DocumentationBuild build = builds.start(site, BuildTrigger.SCHEDULE, INSTANCE, NOW.plusSeconds(run));
            String prefix = site + "/" + build.id();
            builds.succeeded(build.id(), prefix, 1, 1, 1, NOW.plusSeconds(run + 1));
            if (run == 0) {
                firstPrefix = prefix;
            }
        }
        assertThat(builds.prefixesBeyondRetention(site, 2)).contains(firstPrefix);

        builds.forgetObjectPrefix(firstPrefix);

        assertThat(builds.prefixesBeyondRetention(site, 2)).doesNotContain(firstPrefix);
        // The build itself is still on the record; only where its objects were is forgotten.
        assertThat(builds.published(site)).isPresent();
    }

    @Test
    void recent_thenTheHistoryOfThatSiteNewestFirst() {
        String site = site("recent-history");
        DocumentationBuild first = builds.start(site, BuildTrigger.SCHEDULE, INSTANCE, NOW);
        builds.succeeded(first.id(), site + "/" + first.id(), 1, 1, 1, NOW.plusSeconds(10));
        DocumentationBuild second = builds.start(site, BuildTrigger.MANUAL, INSTANCE, NOW.plusSeconds(60));
        builds.failed(second.id(), "npm exited with 1", NOW.plusSeconds(70));
        builds.start(site("recent-other-history"), BuildTrigger.UPLOAD, INSTANCE, NOW.plusSeconds(80));

        assertThat(builds.recent(site, 10)).extracting(DocumentationBuild::id)
                .containsExactly(second.id(), first.id());
        assertThat(builds.recent(site, 1)).extracting(DocumentationBuild::id).containsExactly(second.id());
    }

    /**
     * Every state, not only the successful ones: a site whose builds have been failing for a week has a
     * published build that looks perfectly healthy, and the history is where that is visible.
     */
    @Test
    void recent_thenBuildsOfEveryStateAreOnIt() {
        String site = site("every-state");
        DocumentationBuild running = builds.start(site, BuildTrigger.MANUAL, INSTANCE, NOW);
        DocumentationBuild aborted = builds.start(site, BuildTrigger.UPLOAD, INSTANCE, NOW.plusSeconds(1));
        builds.aborted(aborted.id(), "the instance was stopping", NOW.plusSeconds(2));

        assertThat(builds.recent(site, 10)).extracting(DocumentationBuild::state)
                .containsExactly(BuildState.ABORTED, BuildState.RUNNING);
        assertThat(builds.recent(site, 10)).extracting(DocumentationBuild::id).contains(running.id());
    }

    /**
     * A limit of zero is not a query the driver accepts and is not something a caller means either - the API
     * clamps it as well, and this is the last place that can.
     */
    @Test
    void recent_whenTheLimitIsNotPositive_thenOneBuildRatherThanAnError() {
        String site = site("no-limit");
        DocumentationBuild build = builds.start(site, BuildTrigger.MANUAL, INSTANCE, NOW);

        assertThat(builds.recent(site, 0)).extracting(DocumentationBuild::id).containsExactly(build.id());
    }

    /**
     * The identifiers come from one sequence shared by every site, so reading a build by its identifier alone
     * would let the URL of one site answer with a build of another.
     */
    @Test
    void find_whenTheBuildBelongsToAnotherSite_thenEmpty() {
        DocumentationBuild build = builds.start(site("owner"), BuildTrigger.MANUAL, INSTANCE, NOW);

        assertThat(builds.find(site("owner"), build.id())).get()
                .extracting(DocumentationBuild::trigger).isEqualTo(BuildTrigger.MANUAL);
        assertThat(builds.find(site("not-the-owner"), build.id())).isEmpty();
    }

    @Test
    void running_thenTheBuildsThatAreRunningWhicheverSiteTheyBelongTo() {
        DocumentationBuild running = builds.start(site("running-now"), BuildTrigger.MANUAL, INSTANCE, NOW);
        DocumentationBuild finished = builds.start(site("done"), BuildTrigger.SCHEDULE, INSTANCE, NOW);
        builds.succeeded(finished.id(), "done/" + finished.id(), 1, 1, 1, NOW.plusSeconds(5));

        assertThat(builds.running()).extracting(DocumentationBuild::id)
                .contains(running.id()).doesNotContain(finished.id());
        assertThat(builds.running()).filteredOn(build -> build.id().equals(running.id()))
                .singleElement().extracting(DocumentationBuild::instance).isEqualTo(INSTANCE);
    }

    private static String site(String name) {
        return "build-it-" + name;
    }
}
