package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.BuildRequest;
import ch.admin.bit.jeap.doc.domain.BuildTrigger;
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

        assertThat(requests.request(site, BuildTrigger.UPLOAD, NOW)).isTrue();
        assertThat(requests.pendingSince(site)).contains(NOW);
    }

    @Test
    void request_whenAlreadyPending_thenItIsTheSameRequest() {
        String site = site("collapsing");
        requests.request(site, BuildTrigger.UPLOAD, NOW);

        assertThat(requests.request(site, BuildTrigger.UPLOAD, NOW.plusSeconds(5))).isFalse();
        assertThat(requests.request(site, BuildTrigger.SCHEDULE, NOW.plusSeconds(9))).isFalse();

        // The instant of the first trigger is kept, so the age of a request is the age of the oldest unserved
        // one rather than of the last one to arrive.
        assertThat(requests.pendingSince(site)).contains(NOW);
        assertThat(requests.pending()).filteredOn(request -> request.site().equals(site))
                .singleElement()
                .extracting(BuildRequest::trigger).isEqualTo(BuildTrigger.UPLOAD);
    }

    @Test
    void claim_thenTheFlagIsClearedAndFurtherTriggersCollapseIntoTheNextRun() {
        String site = site("claimed");
        requests.request(site, BuildTrigger.SCHEDULE, NOW);

        assertThat(requests.claim(site)).contains(BuildTrigger.SCHEDULE);
        assertThat(requests.pendingSince(site)).isEmpty();

        // Three triggers arriving while the build runs are one request, and the next tick performs exactly one
        // further build - which is the acceptance criterion of the story.
        requests.request(site, BuildTrigger.UPLOAD, NOW.plusSeconds(1));
        requests.request(site, BuildTrigger.UPLOAD, NOW.plusSeconds(2));
        requests.request(site, BuildTrigger.UPLOAD, NOW.plusSeconds(3));
        assertThat(requests.claim(site)).contains(BuildTrigger.UPLOAD);
        assertThat(requests.claim(site)).isEmpty();
    }

    @Test
    void claim_whenNothingPending_thenEmpty() {
        assertThat(requests.claim(site("idle"))).isEmpty();
    }

    @Test
    void claim_whenTwoInstancesClaimTheSameSite_thenOnlyOneBuilds() throws Exception {
        String site = site("contended");
        requests.request(site, BuildTrigger.SCHEDULE, NOW);

        List<Optional<BuildTrigger>> outcomes = inParallel(
                () -> requests.claim(site),
                () -> requests.claim(site));

        assertThat(outcomes).filteredOn(Optional::isPresent).hasSize(1);
    }

    @Test
    void request_whenTwoInstancesAskAtTheSameMoment_thenOneRequest() throws Exception {
        String site = site("raced");

        List<Boolean> started = inParallel(
                () -> requests.request(site, BuildTrigger.UPLOAD, NOW),
                () -> requests.request(site, BuildTrigger.SCHEDULE, NOW));

        assertThat(started).containsExactlyInAnyOrder(true, false);
        assertThat(requests.claim(site)).isPresent();
        assertThat(requests.claim(site)).isEmpty();
    }

    @Test
    void pending_thenOldestFirstAndOneSiteDoesNotHideAnother() {
        String first = site("first");
        String second = site("second");
        requests.request(second, BuildTrigger.UPLOAD, NOW.plusSeconds(60));
        requests.request(first, BuildTrigger.UPLOAD, NOW);

        List<String> pending = requests.pending().stream().map(BuildRequest::site)
                .filter(site -> site.equals(first) || site.equals(second)).toList();

        assertThat(pending).containsExactly(first, second);

        // Building one site leaves the other's request where it was.
        requests.claim(first);
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
}
