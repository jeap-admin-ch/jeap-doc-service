package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.BuildState;
import ch.admin.bit.jeap.doc.domain.BuildTrigger;
import ch.admin.bit.jeap.doc.domain.DocumentationBuild;
import ch.admin.bit.jeap.doc.domain.PartKey;
import ch.admin.bit.jeap.doc.domain.SitePart;
import ch.admin.bit.jeap.doc.domain.Publication;
import ch.admin.bit.jeap.doc.domain.port.CompletedPublication;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import ch.admin.bit.jeap.doc.domain.port.PublicationTotals;
import ch.admin.bit.jeap.doc.domain.port.PublishedPart;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class DocumentationBuildRepositoryAdapterIT extends PostgresTestContainerBase {

    private static final Instant NOW = Instant.parse("2026-08-25T09:00:00Z");
    private static final String INSTANCE = "doc-service-1";

    @Autowired
    private DocumentationBuildRepository builds;

    /** A publication is not over while one of its parts is still owed a build, which needs the requests. */
    @Autowired
    private ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRequestRepository requests;


    @Test
    void start_thenRunningWithAnIdentifierOfItsOwn() {
        DocumentationBuild build = builds.start(shell(site("started")), BuildTrigger.IMPORT, INSTANCE, NOW, null);

        assertThat(build.id()).isNotNull();
        assertThat(build.state()).isEqualTo(BuildState.RUNNING);
        assertThat(build.trigger()).isEqualTo(BuildTrigger.IMPORT);
        assertThat(build.finishedAt()).isNull();
        assertThat(builds.runningIds()).contains(build.id());
    }

    @Test
    void succeeded_thenTheBuildIsThePublishedOne() {
        String site = site("published");
        DocumentationBuild first = builds.start(shell(site), BuildTrigger.IMPORT, INSTANCE, NOW, null);
        builds.succeeded(first.id(), "sites/" + site + "/" + first.id(), 12, 4096, 3000, "digest", NOW.plusSeconds(30));

        assertThat(builds.published(shell(site))).get().extracting(DocumentationBuild::id).isEqualTo(first.id());
        assertThat(builds.lastSuccessAt(site)).contains(NOW.plusSeconds(30));
        assertThat(builds.runningIds()).doesNotContain(first.id());
    }

    /**
     * The freshness question, which the last publication cannot answer any more: a part whose content has not
     * moved is never generated, so a site nobody changes goes days without a publication and is nonetheless
     * being looked at every hour. A skip is one of the two outcomes that mean "this part is up to date".
     */
    @Test
    void lastCheckAt_thenASkipCountsAndAFailureDoesNot() {
        String site = site("checked");
        DocumentationBuild published = builds.start(shell(site), BuildTrigger.IMPORT, INSTANCE, NOW, null);
        builds.succeeded(published.id(), "sites/" + site + "/" + published.id(), 5, 100, 10, "digest",
                NOW.plusSeconds(10));
        DocumentationBuild unchanged = builds.start(shell(site), BuildTrigger.IMPORT, INSTANCE,
                NOW.plusSeconds(3600), null);
        builds.skipped(unchanged.id(), NOW.plusSeconds(3610));

        assertThat(builds.lastCheckAt(site)).contains(NOW.plusSeconds(3610));
        assertThat(builds.lastSuccessAt(site))
                .describedAs("nothing was published by the skip, and the row still says so")
                .contains(NOW.plusSeconds(10));

        DocumentationBuild broken = builds.start(shell(site), BuildTrigger.IMPORT, INSTANCE,
                NOW.plusSeconds(7200), null);
        builds.failed(broken.id(), "npm exited with 1", NOW.plusSeconds(7210));

        assertThat(builds.lastCheckAt(site))
                .describedAs("a failure says the part is not up to date, so it moves neither")
                .contains(NOW.plusSeconds(3610));
    }

    /**
     * A skipped build publishes nothing and takes nothing away: the part goes on being served by the build
     * that last changed it.
     * <p>
     * <b>The ordinary outcome now</b>, and nothing asserted it. A skip that recorded an object prefix would
     * point the reader at a publication that was never uploaded; one that cleared the digest would make the
     * next build run again, for ever.
     */
    @Test
    void skipped_thenNothingIsPublishedAndThePreviousPublicationStands() {
        String site = site("skipped");
        DocumentationBuild published = builds.start(shell(site), BuildTrigger.IMPORT, INSTANCE, NOW, null);
        builds.succeeded(published.id(), "sites/" + site + "/" + published.id(), 7, 700, 70, "digest",
                NOW.plusSeconds(10));
        DocumentationBuild unchanged = builds.start(shell(site), BuildTrigger.IMPORT, INSTANCE,
                NOW.plusSeconds(3600), null);

        DocumentationBuild recorded = builds.skipped(unchanged.id(), NOW.plusSeconds(3610));

        assertThat(recorded.state()).isEqualTo(BuildState.SKIPPED);
        assertThat(recorded.objectPrefix()).describedAs("nothing was uploaded, so nothing is pointed at")
                .isNull();
        assertThat(builds.published(shell(site))).get()
                .describedAs("the build that last changed this part is still the published one")
                .extracting(DocumentationBuild::id, DocumentationBuild::contentDigest)
                .containsExactly(published.id(), "digest");
        assertThat(builds.recent(site, 1)).describedAs("and it is the newest build all the same")
                .singleElement().extracting(DocumentationBuild::id).isEqualTo(unchanged.id());
    }

    @Test
    void published_whenTheNewestBuildFailed_thenTheSitePublishedBeforeItStaysPublished() {
        String site = site("failed-after");
        DocumentationBuild good = builds.start(shell(site), BuildTrigger.IMPORT, INSTANCE, NOW, null);
        builds.succeeded(good.id(), "sites/" + site + "/" + good.id(), 5, 100, 10, "digest", NOW.plusSeconds(10));
        DocumentationBuild bad = builds.start(shell(site), BuildTrigger.UPLOAD, INSTANCE, NOW.plusSeconds(60), null);
        builds.failed(bad.id(), "npm exited with 1", NOW.plusSeconds(70));

        assertThat(builds.published(shell(site))).get().extracting(DocumentationBuild::id).isEqualTo(good.id());
    }

    @Test
    void published_whenNothingEverSucceeded_thenEmpty() {
        String site = site("never");
        builds.failed(builds.start(shell(site), BuildTrigger.IMPORT, INSTANCE, NOW, null).id(), "no",
                NOW.plusSeconds(1));

        assertThat(builds.published(shell(site))).isEmpty();
        assertThat(builds.lastSuccessAt(site)).isEmpty();
    }

    @Test
    void abandonRunning_thenAStrandedBuildStopsLookingLikeOneInProgress() {
        String site = site("stranded");
        DocumentationBuild stranded = builds.start(shell(site), BuildTrigger.IMPORT, INSTANCE, NOW, null);

        assertThat(builds.abandonRunning(shell(site), NOW.plusSeconds(3600)))
                .singleElement()
                .satisfies(abandoned -> {
                    assertThat(abandoned.id()).isEqualTo(stranded.id());
                    // What triggered it decides whether the site is built again straight away, so the caller is
                    // handed the builds rather than a count.
                    assertThat(abandoned.trigger()).isEqualTo(BuildTrigger.IMPORT);
                    assertThat(abandoned.state()).isEqualTo(BuildState.ABANDONED);
                });

        assertThat(builds.runningIds()).doesNotContain(stranded.id());
        assertThat(builds.abandonRunning(shell(site), NOW.plusSeconds(3600))).isEmpty();
    }

    @Test
    void aborted_thenTheBuildIsNeitherFailedNorPublished() {
        String site = site("aborted");
        DocumentationBuild published = builds.start(shell(site), BuildTrigger.IMPORT, INSTANCE, NOW, null);
        builds.succeeded(published.id(), "sites/" + site + "/" + published.id(), 3, 30, 300, "digest", NOW.plusSeconds(10));
        DocumentationBuild interrupted = builds.start(shell(site), BuildTrigger.UPLOAD, INSTANCE, NOW.plusSeconds(60), null);

        DocumentationBuild recorded = builds.aborted(interrupted.id(), "the instance was stopping",
                NOW.plusSeconds(61));

        assertThat(recorded.state()).isEqualTo(BuildState.ABORTED);
        assertThat(recorded.failureReason()).isEqualTo("the instance was stopping");
        assertThat(recorded.finishedAt()).isEqualTo(NOW.plusSeconds(61));
        // The site published before it is still the one being served, and the row no longer pins its workspace.
        assertThat(builds.published(shell(site))).get().extracting(DocumentationBuild::id).isEqualTo(published.id());
        assertThat(builds.runningIds()).doesNotContain(interrupted.id());
    }

    @Test
    void partsWithRunningBuilds_thenOnlyTheOnesThatOweABuildAndEachOnlyOnce() {
        String running = site("still-running");
        String finished = site("finished");
        builds.start(shell(running), BuildTrigger.IMPORT, INSTANCE, NOW, null);
        builds.start(shell(running), BuildTrigger.UPLOAD, INSTANCE, NOW.plusSeconds(1), null);
        builds.failed(builds.start(shell(finished), BuildTrigger.IMPORT, INSTANCE, NOW, null).id(), "no", NOW);

        assertThat(builds.partsWithRunningBuilds()).contains(shell(running)).doesNotContain(shell(finished));
        assertThat(builds.partsWithRunningBuilds().stream().filter(shell(running)::equals)).hasSize(1);
    }

    @Test
    void abandonRunning_thenAnotherSitesRunningBuildIsUntouched() {
        DocumentationBuild other = builds.start(shell(site("untouched")), BuildTrigger.IMPORT, INSTANCE, NOW, null);

        builds.abandonRunning(shell(site("stranded-elsewhere")), NOW);

        assertThat(builds.runningIds()).contains(other.id());
    }

    @Test
    void prefixesBeyondRetention_thenOnlyTheOnesPastTheKeptOnes() {
        String site = site("retained");
        for (int run = 0; run < 5; run++) {
            DocumentationBuild build = builds.start(shell(site), BuildTrigger.IMPORT, INSTANCE, NOW.plusSeconds(run), null);
            builds.succeeded(build.id(), "sites/" + site + "/" + build.id(), 1, 1, 1, "digest", NOW.plusSeconds(run + 1));
        }

        assertThat(builds.prefixesBeyondRetention(shell(site), 3)).hasSize(2);
        assertThat(builds.prefixesBeyondRetention(shell(site), 5)).isEmpty();
        // The newest is never offered for deletion: it is the site being served.
        assertThat(builds.prefixesBeyondRetention(shell(site), 3))
                .doesNotContain(builds.published(shell(site)).orElseThrow().objectPrefix());
    }

    /**
     * What is served for a site: the newest succeeded build of <b>each</b> part, with its prefix, its digest
     * and its page count in the right components.
     * <p>
     * The three publication queries all rest on one correlated subquery, and their projections put same-typed
     * values next to each other - a swapped {@code part} and {@code objectPrefix} would pass validation and
     * serve the wrong prefix.
     */
    @Test
    void publishedPartsOf_thenItIsTheNewestSucceededBuildOfEveryPart() {
        String site = site("two-parts");
        PartKey orders = PartKey.of(site, "system-orders");
        superseded(shell(site));
        DocumentationBuild shell = builds.start(shell(site), BuildTrigger.IMPORT, INSTANCE, NOW.plusSeconds(10), null);
        builds.succeeded(shell.id(), "sites/" + site + "/shell", 12, 4096, 900, "shell-digest", NOW.plusSeconds(11));
        DocumentationBuild ofOrders = builds.start(orders, BuildTrigger.IMPORT, INSTANCE, NOW.plusSeconds(12), null);
        builds.succeeded(ofOrders.id(), "sites/" + site + "/orders", 30, 8192, 900, "orders-digest", NOW.plusSeconds(13));
        // A later failure of one part does not change what is published for it.
        DocumentationBuild failed = builds.start(orders, BuildTrigger.IMPORT, INSTANCE, NOW.plusSeconds(14), null);
        builds.failed(failed.id(), "exited with 1", NOW.plusSeconds(15));
        // Another site's parts are none of this site's business.
        String other = site("elsewhere");
        DocumentationBuild elsewhere = builds.start(shell(other), BuildTrigger.IMPORT, INSTANCE, NOW, null);
        builds.succeeded(elsewhere.id(), "sites/" + other + "/shell", 99, 99, 99, "other-digest", NOW.plusSeconds(1));

        assertThat(builds.publishedPartsOf(site))
                .extracting(PublishedPart::part, PublishedPart::objectPrefix, PublishedPart::contentDigest,
                        PublishedPart::pageCount)
                .containsExactlyInAnyOrder(
                        tuple(SitePart.SHELL, "sites/" + site + "/shell", "shell-digest", 12),
                        tuple("system-orders", "sites/" + site + "/orders", "orders-digest", 30));
    }

    /** What the site adds up to: the published build of each part, and not the ones they superseded. */
    @Test
    void publishedTotalsOf_thenItSumsThePublishedPartsAndNothingElse() {
        String site = site("totals");
        superseded(shell(site));
        DocumentationBuild shell = builds.start(shell(site), BuildTrigger.IMPORT, INSTANCE, NOW.plusSeconds(10), null);
        builds.succeeded(shell.id(), "sites/" + site + "/shell", 12, 4096, 900, "digest", NOW.plusSeconds(11));
        DocumentationBuild orders = builds.start(PartKey.of(site, "system-orders"), BuildTrigger.IMPORT,
                INSTANCE, NOW.plusSeconds(12), null);
        builds.succeeded(orders.id(), "sites/" + site + "/orders", 30, 8192, 900, "digest", NOW.plusSeconds(13));

        assertThat(builds.publishedTotalsOf(site))
                .extracting(PublicationTotals::parts, PublicationTotals::pages, PublicationTotals::bytes)
                .containsExactly(2, 42, 12288L);
    }

    /**
     * The age of the oldest published part is what says a part has quietly stopped being rebuilt, which the
     * newest publication of the site cannot.
     */
    @Test
    void oldestPublicationAt_thenItIsTheOlderOfTheTwoParts() {
        String site = site("oldest-part");
        DocumentationBuild shell = builds.start(shell(site), BuildTrigger.IMPORT, INSTANCE, NOW, null);
        builds.succeeded(shell.id(), "sites/" + site + "/shell", 1, 1, 1, "digest", NOW.plusSeconds(1));
        DocumentationBuild orders = builds.start(PartKey.of(site, "system-orders"), BuildTrigger.IMPORT,
                INSTANCE, NOW.plusSeconds(3600), null);
        builds.succeeded(orders.id(), "sites/" + site + "/orders", 1, 1, 1, "digest", NOW.plusSeconds(3601));

        assertThat(builds.oldestPublicationAt(site)).contains(NOW.plusSeconds(1));
    }

    /** A build of the part that succeeded and was then superseded by a later one. */
    private void superseded(PartKey part) {
        DocumentationBuild old = builds.start(part, BuildTrigger.IMPORT, INSTANCE, NOW, null);
        builds.succeeded(old.id(), "sites/superseded/" + old.id(), 7, 7, 7, "old-digest", NOW.plusSeconds(1));
    }

    @Test
    void deleteFinishedBefore_thenOnlyFinishedBuildsGo() {
        String site = site("history");
        DocumentationBuild finished = builds.start(shell(site), BuildTrigger.IMPORT, INSTANCE, NOW, null);
        builds.failed(finished.id(), "exited with 1", NOW.plusSeconds(1));
        DocumentationBuild running = builds.start(shell(site), BuildTrigger.IMPORT, INSTANCE, NOW, null);

        assertThat(builds.deleteFinishedBefore(NOW.plusSeconds(600))).isPositive();

        assertThat(builds.recentOf(shell(site), 10)).extracting(DocumentationBuild::id)
                .doesNotContain(finished.id());
        assertThat(builds.runningIds()).contains(running.id());
    }

    /**
     * The newest succeeded build of a part is not only a record, it is the publication - and a part whose
     * content does not move is not rebuilt at all, so its publication is routinely older than the retention.
     * The rule is in the statement: nothing has to name the row to spare.
     */
    @Test
    void deleteFinishedBefore_thenWhatEachPartPublishedIsKeptWhateverItsAge() {
        String site = site("published-and-old");
        DocumentationBuild superseded = builds.start(shell(site), BuildTrigger.IMPORT, INSTANCE, NOW, null);
        builds.succeeded(superseded.id(), site + "/" + superseded.id(), 1, 1, 1, "digest", NOW.plusSeconds(1));
        DocumentationBuild shell = builds.start(shell(site), BuildTrigger.UPLOAD, INSTANCE, NOW.plusSeconds(2), null);
        builds.succeeded(shell.id(), site + "/" + shell.id(), 1, 1, 1, "digest", NOW.plusSeconds(3));
        PartKey orders = PartKey.of(site, "system-orders");
        DocumentationBuild ofOrders = builds.start(orders, BuildTrigger.IMPORT, INSTANCE, NOW, null);
        builds.succeeded(ofOrders.id(), site + "/" + ofOrders.id(), 1, 1, 1, "digest", NOW.plusSeconds(1));

        int removed = builds.deleteFinishedBefore(NOW.plusSeconds(600));

        assertThat(removed).describedAs("the superseded build of the shell").isPositive();
        // Both parts keep theirs, and the part nothing configures any more would too: it is the rows that
        // decide, not a keep-set a caller assembled from the model.
        assertThat(builds.published(shell(site))).get().extracting(DocumentationBuild::id)
                .isEqualTo(shell.id());
        assertThat(builds.published(orders)).get().extracting(DocumentationBuild::id)
                .isEqualTo(ofOrders.id());
        assertThat(builds.recentOf(shell(site), 10)).extracting(DocumentationBuild::id)
                .doesNotContain(superseded.id());
    }

    /**
     * A generator that fails can write a thousand lines of bundler stack, and the row is kept for as long as
     * the history retention says. What is stored is the end of it - the whole transcript is in the log.
     */
    @Test
    void failed_whenTheReasonIsEnormous_thenTheEndOfItIsKeptAndTheRowSaysSo() {
        String site = site("chatty-failure");
        DocumentationBuild build = builds.start(shell(site), BuildTrigger.IMPORT, INSTANCE, NOW, null);
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
        DocumentationBuild build = builds.start(shell(site), BuildTrigger.IMPORT, INSTANCE, NOW, null);
        String reason = "\uD83D\uDCC4".repeat(20_000);

        DocumentationBuild recorded = builds.failed(build.id(), reason, NOW.plusSeconds(10));

        assertThat(recorded.failureReason()).isNotBlank();
        assertThat(builds.published(shell(site))).isEmpty();
    }

    /**
     * A build whose lease lapsed may be marked ABANDONED by another instance while it is still running. If it
     * succeeds after all, the row must not keep a reason saying its instance stopped - that is the false
     * evidence an operator reads in the one table they read.
     */
    @Test
    void succeeded_whenAnotherInstanceHadGivenUpOnIt_thenTheFailureReasonIsGone() {
        String site = site("abandoned-then-succeeded");
        DocumentationBuild build = builds.start(shell(site), BuildTrigger.IMPORT, INSTANCE, NOW, null);
        builds.abandonRunning(shell(site), NOW.plusSeconds(60));

        DocumentationBuild recorded = builds.succeeded(build.id(), site + "/" + build.id(), 5, 500, 50,
                "digest", NOW.plusSeconds(120));

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
            DocumentationBuild build = builds.start(shell(site), BuildTrigger.IMPORT, INSTANCE, NOW.plusSeconds(run), null);
            String prefix = site + "/" + build.id();
            builds.succeeded(build.id(), prefix, 1, 1, 1, "digest", NOW.plusSeconds(run + 1));
            if (run == 0) {
                firstPrefix = prefix;
            }
        }
        assertThat(builds.prefixesBeyondRetention(shell(site), 2)).contains(firstPrefix);

        builds.forgetObjectPrefix(firstPrefix);

        assertThat(builds.prefixesBeyondRetention(shell(site), 2)).doesNotContain(firstPrefix);
        // The build itself is still on the record; only where its objects were is forgotten.
        assertThat(builds.published(shell(site))).isPresent();
    }

    @Test
    void recent_thenTheHistoryOfThatSiteNewestFirst() {
        String site = site("recent-history");
        DocumentationBuild first = builds.start(shell(site), BuildTrigger.IMPORT, INSTANCE, NOW, null);
        builds.succeeded(first.id(), site + "/" + first.id(), 1, 1, 1, "digest", NOW.plusSeconds(10));
        DocumentationBuild second = builds.start(shell(site), BuildTrigger.MANUAL, INSTANCE, NOW.plusSeconds(60), null);
        builds.failed(second.id(), "npm exited with 1", NOW.plusSeconds(70));
        builds.start(shell(site("recent-other-history")), BuildTrigger.UPLOAD, INSTANCE, NOW.plusSeconds(80), null);

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
        DocumentationBuild running = builds.start(shell(site), BuildTrigger.MANUAL, INSTANCE, NOW, null);
        DocumentationBuild aborted = builds.start(shell(site), BuildTrigger.UPLOAD, INSTANCE, NOW.plusSeconds(1), null);
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
        DocumentationBuild build = builds.start(shell(site), BuildTrigger.MANUAL, INSTANCE, NOW, null);

        assertThat(builds.recent(site, 0)).extracting(DocumentationBuild::id).containsExactly(build.id());
    }

    /**
     * The identifiers come from one sequence shared by every site, so reading a build by its identifier alone
     * would let the URL of one site answer with a build of another.
     */
    @Test
    void find_whenTheBuildBelongsToAnotherSite_thenEmpty() {
        DocumentationBuild build = builds.start(shell(site("owner")), BuildTrigger.MANUAL, INSTANCE, NOW, null);

        assertThat(builds.find(site("owner"), build.id())).get()
                .extracting(DocumentationBuild::trigger).isEqualTo(BuildTrigger.MANUAL);
        assertThat(builds.find(site("not-the-owner"), build.id())).isEmpty();
    }

    @Test
    void running_thenTheBuildsThatAreRunningWhicheverSiteTheyBelongTo() {
        DocumentationBuild running = builds.start(shell(site("running-now")), BuildTrigger.MANUAL, INSTANCE, NOW, null);
        DocumentationBuild finished = builds.start(shell(site("done")), BuildTrigger.IMPORT, INSTANCE, NOW, null);
        builds.succeeded(finished.id(), "done/" + finished.id(), 1, 1, 1, "digest", NOW.plusSeconds(5));

        assertThat(builds.running()).extracting(DocumentationBuild::id)
                .contains(running.id()).doesNotContain(finished.id());
        assertThat(builds.running()).filteredOn(build -> build.id().equals(running.id()))
                .singleElement().extracting(DocumentationBuild::instance).isEqualTo(INSTANCE);
    }

    // ---- the wall clock of a full publication ----

    /**
     * <b>The number an operator waits for.</b> Its parts are built on several instances, so no single one of
     * them knows when the last of them finished - only the rows do, and only once every part is done.
     */
    @Test
    void lastCompletedPublicationOf_thenItIsTheElapsedTimeOfTheWholeAskAndNotTheSumOfItsParts() {
        String site = site("publication-elapsed");
        Publication publication = new Publication("one-ask", NOW);
        finished(site, "system-orders", publication, NOW.plusSeconds(100));
        finished(site, "system-shipping", publication, NOW.plusSeconds(300));

        CompletedPublication completed = builds.lastCompletedPublicationOf(site).orElseThrow();

        // 300 seconds of elapsed, over 400 seconds of work: the parts ran at the same time.
        assertThat(completed.duration()).isEqualTo(Duration.ofSeconds(300));
        assertThat(completed.parts()).isEqualTo(2);
        assertThat(completed.id()).isEqualTo("one-ask");
    }

    /** The newest, so that a graph of it steps once per publication rather than reporting the first ever. */
    @Test
    void lastCompletedPublicationOf_whenTwoAreOver_thenItIsTheNewer() {
        String site = site("publication-newest");
        finished(site, "system-orders", new Publication("older", NOW), NOW.plusSeconds(60));
        finished(site, "system-orders", new Publication("newer", NOW.plusSeconds(3600)),
                NOW.plusSeconds(3700));

        assertThat(builds.lastCompletedPublicationOf(site).orElseThrow().id()).isEqualTo("newer");
    }

    /**
     * A publication with a part still building is not over. Reporting its elapsed time would read as a
     * publication getting slower, when it is simply not finished.
     */
    @Test
    void lastCompletedPublicationOf_whenOnePartIsStillBuilding_thenThatPublicationIsNotReported() {
        String site = site("publication-running");
        Publication publication = new Publication("half-done", NOW);
        finished(site, "system-orders", publication, NOW.plusSeconds(100));
        builds.start(PartKey.of(site, "system-shipping"), BuildTrigger.MANUAL, INSTANCE, NOW, publication);

        assertThat(builds.lastCompletedPublicationOf(site)).isEmpty();
    }

    /**
     * Nor is one whose parts have not all started. Without this the publication would report the elapsed time
     * of the few parts that got through before the rest were queued behind another instance's work.
     */
    @Test
    void lastCompletedPublicationOf_whenOnePartIsStillOwedABuild_thenThatPublicationIsNotReported() {
        String site = site("publication-owed");
        Publication publication = new Publication("still-owed", NOW);
        finished(site, "system-orders", publication, NOW.plusSeconds(100));
        requests.request(PartKey.of(site, "system-shipping"), BuildTrigger.MANUAL, NOW, publication, true);

        assertThat(builds.lastCompletedPublicationOf(site)).isEmpty();

        requests.claim(PartKey.of(site, "system-shipping"));
    }

    /** An upload builds one part and is no publication, so it must not be reported as one. */
    @Test
    void lastCompletedPublicationOf_whenTheBuildsBelongToNoPublication_thenThereIsNothingToReport() {
        String site = site("publication-none");
        finished(site, "system-orders", null, NOW.plusSeconds(100));

        assertThat(builds.lastCompletedPublicationOf(site)).isEmpty();
    }

    /** A build of one part of a publication, run and finished. */
    private void finished(String site, String part, Publication publication, Instant finishedAt) {
        DocumentationBuild build = builds.start(PartKey.of(site, part), BuildTrigger.MANUAL, INSTANCE,
                publication == null ? NOW : publication.requestedAt(), publication);
        builds.succeeded(build.id(), site + "/" + build.id(), 12, 4096, 3000, "digest-" + part,
                finishedAt);
    }

    private static String site(String name) {
        return "build-it-" + name;
    }

    /** The shell part of a site, which is the part every one of these builds is of. */
    private static PartKey shell(String site) {
        return PartKey.shellOf(site);
    }
}
