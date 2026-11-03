package ch.admin.bit.jeap.doc.domain.governance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * What starts a governance import on this instance.
 * <p>
 * An import runs off the schedule and off the webhook's request thread, and a call that arrives while one is
 * already running has to merge with it rather than starting beside it - the same guarantee
 * {@code DocumentationBuildPickupTest} asserts for a build pass, and the same technique.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GovernanceImportPickupTest {

    @Mock
    private GovernanceImportJob job;

    private GovernanceImportPickup pickup;

    @BeforeEach
    void setUp() {
        // This instance gets the lock unless a case says otherwise.
        doReturn(true).when(job).importGovernanceData();
        pickup = startedPickup(Duration.ofMillis(50));
    }

    @AfterEach
    void tearDown() {
        pickup.stop();
    }

    @Test
    void whenNotifiedByWebhook_thenAnImportRunsWithoutWaitingForThePoll() {
        pickup.whenNotifiedByWebhook();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(job).importGovernanceData());
    }

    /** The caller answers 202; what follows is a call to the Governance Service. */
    @Test
    void whenNotifiedByWebhook_thenTheCallerIsNotTheOneThatImports() throws Exception {
        CountDownLatch inTheImport = new CountDownLatch(1);
        CountDownLatch letItFinish = new CountDownLatch(1);
        doAnswer(invocation -> {
            inTheImport.countDown();
            letItFinish.await(5, TimeUnit.SECONDS);
            return true;
        }).when(job).importGovernanceData();

        pickup.whenNotifiedByWebhook();

        assertThat(inTheImport.await(5, TimeUnit.SECONDS)).isTrue();
        letItFinish.countDown();
    }

    /**
     * A call that arrives while an import is already running must not be dropped - the scoring it reports may
     * not be in the data that run started reading - but it must also not start a second import beside the
     * first: the two merge into exactly one further run.
     */
    @Test
    void whenNotifiedTenTimesWhileAnImportIsRunning_thenExactlyOneMoreImportIsQueued() throws Exception {
        CountDownLatch inTheFirstImport = new CountDownLatch(1);
        CountDownLatch letTheFirstFinish = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        doAnswer(invocation -> {
            if (runs.incrementAndGet() == 1) {
                inTheFirstImport.countDown();
                letTheFirstFinish.await(5, TimeUnit.SECONDS);
            }
            return true;
        }).when(job).importGovernanceData();

        pickup.whenNotifiedByWebhook();
        assertThat(inTheFirstImport.await(5, TimeUnit.SECONDS)).isTrue();
        for (int call = 0; call < 10; call++) {
            pickup.whenNotifiedByWebhook();
        }
        letTheFirstFinish.countDown();

        await().atMost(Duration.ofSeconds(5)).until(() -> runs.get() == 2);
        // And it stays there: the ten calls are one queued import, not ten.
        await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(runs.get()).isEqualTo(2));
    }

    /** The schedule merges with a running import the same way the webhook does. */
    @Test
    void poll_whileAnImportIsRunning_thenExactlyOneMoreImportIsQueued() throws Exception {
        CountDownLatch inTheFirstImport = new CountDownLatch(1);
        CountDownLatch letTheFirstFinish = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        doAnswer(invocation -> {
            if (runs.incrementAndGet() == 1) {
                inTheFirstImport.countDown();
                letTheFirstFinish.await(5, TimeUnit.SECONDS);
            }
            return true;
        }).when(job).importGovernanceData();

        pickup.whenNotifiedByWebhook();
        assertThat(inTheFirstImport.await(5, TimeUnit.SECONDS)).isTrue();
        pickup.poll();
        letTheFirstFinish.countDown();

        await().atMost(Duration.ofSeconds(5)).until(() -> runs.get() == 2);
    }

    /** Once the instance is stopping, nothing queues another import behind the one being given up on. */
    @Test
    void whenNotifiedByWebhook_whenTheInstanceIsStopping_thenNoImportRuns() {
        pickup.stop();

        assertThat(pickup.whenNotifiedByWebhook()).isFalse();

        await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> verify(job, never()).importGovernanceData());
    }

    /** The web server takes calls before the lifecycle starts this, and such a call must not be lost. */
    @Test
    void whenNotifiedByWebhook_beforeTheStart_thenTheImportRunsOnStart() {
        pickup.stop();
        pickup = new GovernanceImportPickup(job, Duration.ofMillis(50));

        assertThat(pickup.whenNotifiedByWebhook()).isTrue();
        verify(job, never()).importGovernanceData();
        pickup.start();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(job).importGovernanceData());
    }

    /** An import that threw is not allowed to take the pickup thread's next import with it. */
    @Test
    void whenAnImportThrows_thenTheNextOneStillRuns() {
        doThrow(new IllegalStateException("no")).doReturn(true)
                .when(job).importGovernanceData();

        pickup.whenNotifiedByWebhook();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(job).importGovernanceData());
        pickup.whenNotifiedByWebhook();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(job, times(2)).importGovernanceData());
    }

    /**
     * Another instance holding the lock may have read the indexes before the scoring the webhook reports, so
     * the webhook's import is tried again until this instance gets to run it.
     */
    @Test
    void whenNotifiedByWebhook_whenAnotherInstanceHoldsTheLock_thenTheImportIsTriedAgainUntilItRuns() {
        doReturn(false).doReturn(false).doReturn(true).when(job).importGovernanceData();

        pickup.whenNotifiedByWebhook();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(job, times(3)).importGovernanceData());
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> verify(job, times(3)).importGovernanceData());
    }

    /** Every instance's schedule fires at once, and one of them importing is enough. */
    @Test
    void poll_whenAnotherInstanceHoldsTheLock_thenItIsNotTriedAgain() {
        doReturn(false).when(job).importGovernanceData();

        pickup.poll();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(job).importGovernanceData());
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> verify(job).importGovernanceData());
    }

    /** A stopping instance drops a retry that is still waiting. */
    @Test
    void whenNotifiedByWebhook_whenTheInstanceStopsBeforeTheRetry_thenTheRetryDoesNotRun() {
        pickup.stop();
        pickup = startedPickup(Duration.ofMillis(300));
        doReturn(false).when(job).importGovernanceData();

        pickup.whenNotifiedByWebhook();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(job).importGovernanceData());
        pickup.stop();

        await().during(Duration.ofMillis(600)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> verify(job).importGovernanceData());
    }

    private GovernanceImportPickup startedPickup(Duration retryDelay) {
        GovernanceImportPickup started = new GovernanceImportPickup(job, retryDelay);
        started.start();
        return started;
    }
}
