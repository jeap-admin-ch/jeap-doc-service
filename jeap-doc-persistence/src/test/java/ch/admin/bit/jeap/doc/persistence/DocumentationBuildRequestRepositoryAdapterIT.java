package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.BuildRequest;
import ch.admin.bit.jeap.doc.domain.BuildTrigger;
import ch.admin.bit.jeap.doc.domain.PartKey;
import ch.admin.bit.jeap.doc.domain.Publication;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentationBuildRequestRepositoryAdapterIT extends PostgresTestContainerBase {

    private static final Instant NOW = Instant.parse("2026-08-25T09:00:00Z");

    @Autowired
    private DocumentationBuildRequestRepository requests;

    @Test
    void request_whenNothingPending_thenTheRequestStartsWaiting() {
        String site = site("fresh");

        assertThat(requests.request(shell(site), BuildTrigger.UPLOAD, NOW, null, false)).isTrue();
        assertThat(requests.pendingSince(site)).contains(NOW);
    }

    /**
     * The parts of a publication are asked for in one transaction, so that another instance sees all of them
     * or none. Seeing the first of fifty and none of the rest, it builds that one, finds nothing else owed,
     * and reports a publication that took seconds.
     */
    @Test
    void requestAll_thenEveryPartIsPendingAndTheNewOnesAreCounted() {
        String site = site("one-transaction");
        PartKey shell = shell(site);
        PartKey orders = PartKey.of(site, "system-orders");
        PartKey shipping = PartKey.of(site, "system-shipping");
        requests.request(orders, BuildTrigger.UPLOAD, NOW, null, false);
        Publication publication = Publication.askedAt(NOW.plusSeconds(60));

        int created = requests.requestAll(List.of(shell, orders, shipping), BuildTrigger.IMPORT,
                NOW.plusSeconds(60), publication, false);

        assertThat(created).describedAs("the two that were not already pending").isEqualTo(2);
        assertThat(requests.pending()).extracting(BuildRequest::part)
                .contains(shell, orders, shipping);
        // The part that was already pending keeps who asked first, and joins the publication all the same.
        assertThat(requests.pending()).filteredOn(request -> request.part().equals(orders)).singleElement()
                .satisfies(request -> {
                    assertThat(request.trigger()).isEqualTo(BuildTrigger.UPLOAD);
                    assertThat(request.requestedAt()).isEqualTo(NOW);
                    assertThat(request.publication().id()).isEqualTo(publication.id());
                });
    }

    @Test
    void request_whenAlreadyPending_thenItIsTheSameRequest() {
        String site = site("collapsing");
        requests.request(shell(site), BuildTrigger.UPLOAD, NOW, null, false);

        assertThat(requests.request(shell(site), BuildTrigger.UPLOAD, NOW.plusSeconds(5), null, false)).isFalse();
        assertThat(requests.request(shell(site), BuildTrigger.IMPORT, NOW.plusSeconds(9), null, false)).isFalse();

        // The instant of the first trigger is kept, so the age of a request is the age of the oldest unserved
        // one rather than of the last one to arrive.
        assertThat(requests.pendingSince(site)).contains(NOW);
        assertThat(requests.pending()).filteredOn(request -> request.site().equals(site))
                .singleElement()
                .extracting(BuildRequest::trigger).isEqualTo(BuildTrigger.UPLOAD);
    }

    /**
     * The one thing an ask does change about the request it joins.
     * <p>
     * Everything else describes who asked first, because two asks for one part are one row - which is exactly
     * why the force cannot be read off the trigger. On a site fed by the hourly import a part is pending for a
     * good part of every hour, so an operator forcing a publication has to reach the row that is already
     * there.
     */
    @Test
    void request_whenAlreadyPendingAndTheAskIsForced_thenThePendingRequestMayNoLongerBeSkipped() {
        String site = site("forcing");
        requests.request(shell(site), BuildTrigger.IMPORT, NOW, null, false);

        assertThat(requests.request(shell(site), BuildTrigger.MANUAL, NOW.plusSeconds(5), null, true))
                .describedAs("it joins the pending request rather than starting one").isFalse();

        assertThat(requests.claim(shell(site))).get()
                // The trigger is still the import's: it asked first, and that is what it records.
                .extracting(BuildRequest::trigger, BuildRequest::forced)
                .containsExactly(BuildTrigger.IMPORT, true);
    }

    /** Forcing does not go back: an upload arriving after it must not let the digest skip the build. */
    @Test
    void request_whenForcedAndAnotherAskJoins_thenItStaysForced() {
        String site = site("stays-forced");
        requests.request(shell(site), BuildTrigger.MANUAL, NOW, null, true);

        assertThat(requests.request(shell(site), BuildTrigger.UPLOAD, NOW.plusSeconds(5), null, false))
                .isFalse();

        assertThat(requests.claim(shell(site))).get().extracting(BuildRequest::forced).isEqualTo(true);
    }

    /**
     * A part already owed a build when a publication is asked for is built by that publication's pass, so it
     * is one of its parts. Left out of it, the publication reads as finished while that part is still owed a
     * build and its wall clock stops before the last part finished - which is the one number those gauges are
     * for.
     */
    @Test
    void request_whenAlreadyPendingAndTheAskIsPartOfAPublication_thenTheRequestJoinsThatPublication() {
        String site = site("adopted");
        Publication publication = new Publication("pub-1", NOW.plusSeconds(5));
        requests.request(shell(site), BuildTrigger.UPLOAD, NOW, null, false);

        assertThat(requests.request(shell(site), BuildTrigger.IMPORT, NOW.plusSeconds(5), publication, false))
                .isFalse();

        assertThat(requests.claim(shell(site))).get()
                .extracting(BuildRequest::trigger, BuildRequest::publication)
                .containsExactly(BuildTrigger.UPLOAD, publication);
    }

    /** The first ask is the one whose wait is being measured, so a second publication does not take it over. */
    @Test
    void request_whenAlreadyInAPublication_thenALaterOneDoesNotTakeItOver() {
        String site = site("keeps-its-publication");
        Publication first = new Publication("pub-1", NOW);
        requests.request(shell(site), BuildTrigger.IMPORT, NOW, first, false);

        requests.request(shell(site), BuildTrigger.MANUAL, NOW.plusSeconds(60),
                new Publication("pub-2", NOW.plusSeconds(60)), true);

        assertThat(requests.claim(shell(site))).get()
                .extracting(BuildRequest::publication, BuildRequest::forced)
                .describedAs("the publication of the first ask, and the force of the second")
                .containsExactly(first, true);
    }

    /**
     * <b>The identity of a request is the part, not the site</b> - which is what the migration to a composite
     * key was for, and what nothing here asserted: two parts of one site are two requests, each claimed on its
     * own, and pendingCount is how many of them a site is owed.
     */
    @Test
    void request_whenTwoPartsOfOneSiteAreAskedFor_thenTheyAreTwoRequests() {
        String site = site("two-parts");
        PartKey shell = shell(site);
        PartKey orders = PartKey.of(site, "system-orders");

        assertThat(requests.request(shell, BuildTrigger.IMPORT, NOW, null, false)).isTrue();
        assertThat(requests.request(orders, BuildTrigger.IMPORT, NOW.plusSeconds(1), null, false)).isTrue();

        assertThat(requests.pendingCount(site)).isEqualTo(2);
        assertThat(requests.claim(shell)).isPresent();
        assertThat(requests.pendingCount(site)).describedAs("claiming one leaves the other").isOne();
        assertThat(requests.pendingSince(site))
                .describedAs("and the age is now the remaining one's")
                .contains(NOW.plusSeconds(1));
        assertThat(requests.claim(orders)).isPresent();
        assertThat(requests.pendingCount(site)).isZero();
    }

    /** Per site, so one site's parts are not counted towards another's. */
    @Test
    void pendingCount_thenItCountsThePartsOfThatSiteAlone() {
        String first = site("counted");
        String second = site("counted-too");
        requests.request(shell(first), BuildTrigger.IMPORT, NOW, null, false);
        requests.request(PartKey.of(first, "system-orders"), BuildTrigger.IMPORT, NOW, null, false);
        requests.request(shell(second), BuildTrigger.IMPORT, NOW, null, false);

        assertThat(requests.pendingCount(first)).isEqualTo(2);
        assertThat(requests.pendingCount(second)).isOne();
        assertThat(requests.pendingCount("a-site-nobody-asked-about")).isZero();
    }

    /** An ordinary ask is not forced, so a part whose content has not moved is skipped as it should be. */
    @Test
    void request_whenNothingPendingAndTheAskIsNotForced_thenTheRequestIsNotForced() {
        String site = site("not-forced");

        requests.request(shell(site), BuildTrigger.UPLOAD, NOW, null, false);

        assertThat(requests.claim(shell(site))).get().extracting(BuildRequest::forced).isEqualTo(false);
    }

    @Test
    void claim_thenTheFlagIsClearedAndFurtherTriggersCollapseIntoTheNextRun() {
        String site = site("claimed");
        requests.request(shell(site), BuildTrigger.IMPORT, NOW, null, false);

        assertThat(requests.claim(shell(site))).get().extracting(BuildRequest::trigger)
                .isEqualTo(BuildTrigger.IMPORT);
        assertThat(requests.pendingSince(site)).isEmpty();

        // Three triggers arriving while the build runs are one request, and the next tick performs exactly one
        // further build - which is the acceptance criterion of the story.
        requests.request(shell(site), BuildTrigger.UPLOAD, NOW.plusSeconds(1), null, false);
        requests.request(shell(site), BuildTrigger.UPLOAD, NOW.plusSeconds(2), null, false);
        requests.request(shell(site), BuildTrigger.UPLOAD, NOW.plusSeconds(3), null, false);
        assertThat(requests.claim(shell(site))).get().extracting(BuildRequest::trigger)
                .isEqualTo(BuildTrigger.UPLOAD);
        assertThat(requests.claim(shell(site))).isEmpty();
    }

    @Test
    void claim_whenNothingPending_thenEmpty() {
        assertThat(requests.claim(shell(site("idle")))).isEmpty();
    }

    @Test
    void claim_whenTwoInstancesClaimTheSameSite_thenOnlyOneBuilds() throws Exception {
        String site = site("contended");
        requests.request(shell(site), BuildTrigger.IMPORT, NOW, null, false);

        List<Optional<BuildRequest>> outcomes = inParallel(
                () -> requests.claim(shell(site)),
                () -> requests.claim(shell(site)));

        assertThat(outcomes).filteredOn(Optional::isPresent).hasSize(1);
    }

    @Test
    void request_whenTwoInstancesAskAtTheSameMoment_thenOneRequest() throws Exception {
        String site = site("raced");

        List<Boolean> started = inParallel(
                () -> requests.request(shell(site), BuildTrigger.UPLOAD, NOW, null, false),
                () -> requests.request(shell(site), BuildTrigger.IMPORT, NOW, null, false));

        assertThat(started).containsExactlyInAnyOrder(true, false);
        assertThat(requests.claim(shell(site))).isPresent();
        assertThat(requests.claim(shell(site))).isEmpty();
    }

    @Test
    void pending_thenOldestFirstAndOneSiteDoesNotHideAnother() {
        String first = site("first");
        String second = site("second");
        requests.request(shell(second), BuildTrigger.UPLOAD, NOW.plusSeconds(60), null, false);
        requests.request(shell(first), BuildTrigger.UPLOAD, NOW, null, false);

        List<String> pending = requests.pending().stream().map(BuildRequest::site)
                .filter(site -> site.equals(first) || site.equals(second)).toList();

        assertThat(pending).containsExactly(first, second);

        // Building one site leaves the other's request where it was.
        requests.claim(shell(first));
        assertThat(requests.pendingSince(second)).isPresent();
    }

    private static <T> List<T> inParallel(Callable<T> one, Callable<T> other) throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<T> first = executor.submit(one);
            Future<T> second = executor.submit(other);
            return List.of(first.get(), second.get());
        }
    }

    /** A site of this test's own, so the tests do not have to run in a particular order. */
    private static String site(String name) {
        return "request-it-" + name;
    }

    /** The shell part of a site, which is the part every one of these requests is for. */
    private static PartKey shell(String site) {
        return PartKey.shellOf(site);
    }
}
