package ch.admin.bit.jeap.doc.domain;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What starts a build pass on this instance.
 * <p>
 * A pass runs off the task scheduler and off the request thread, and a burst of triggers has to be one pass:
 * an architecture import asks for fifty parts in a row, and fifty passes would be forty-nine that find nothing.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentationBuildPickupTest {

    @Mock
    private DocumentationBuildRunner runner;

    private BuildProperties properties;
    private DocumentationBuildPickup pickup;

    @BeforeEach
    void setUp() {
        properties = new BuildProperties();
        pickup = new DocumentationBuildPickup(runner, properties);
        pickup.start();
    }

    @AfterEach
    void tearDown() {
        pickup.stop();
    }

    @Test
    void whenAskedFor_thenAPassRunsWithoutWaitingForThePoll() {
        pickup.whenAskedFor();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(runner).runOnce());
    }

    /** The caller is a request thread or the import; what follows a wake-up is a Docusaurus build. */
    @Test
    void whenAskedFor_thenTheCallerIsNotTheOneThatBuilds() throws Exception {
        CountDownLatch inThePass = new CountDownLatch(1);
        CountDownLatch letItFinish = new CountDownLatch(1);
        when(runner.runOnce()).thenAnswer(invocation -> {
            inThePass.countDown();
            return letItFinish.await(5, TimeUnit.SECONDS);
        });

        pickup.whenAskedFor();

        assertThat(inThePass.await(5, TimeUnit.SECONDS)).isTrue();
        letItFinish.countDown();
    }

    /**
     * An import asks for every part of a site, one request at a time. What that must not become is a pass per
     * part: a pass reads what is owed for itself, so one waiting behind the running one covers all of them.
     */
    @Test
    void whenAskedForFiftyTimesWhileAPassIsRunning_thenExactlyOneMorePassIsQueued() throws Exception {
        CountDownLatch inTheFirstPass = new CountDownLatch(1);
        CountDownLatch letTheFirstFinish = new CountDownLatch(1);
        AtomicInteger passes = new AtomicInteger();
        when(runner.runOnce()).thenAnswer(invocation -> {
            if (passes.incrementAndGet() == 1) {
                inTheFirstPass.countDown();
                letTheFirstFinish.await(5, TimeUnit.SECONDS);
            }
            return true;
        });

        pickup.whenAskedFor();
        assertThat(inTheFirstPass.await(5, TimeUnit.SECONDS)).isTrue();
        for (int trigger = 0; trigger < 50; trigger++) {
            pickup.whenAskedFor();
        }
        letTheFirstFinish.countDown();

        await().atMost(Duration.ofSeconds(5)).until(() -> passes.get() == 2);
        // And it stays there: the fifty are one queued pass, not fifty.
        await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(passes.get()).isEqualTo(2));
    }

    /**
     * Switched off, a trigger asks for nothing and the poll interval is the only thing that starts a pass.
     * It is what the tests of the other modules run with, so that a case asserts what it set off itself.
     */
    @Test
    void whenAskedFor_whenPickingUpOnATriggerIsOff_thenNoPassRuns() {
        properties.setPickUpOnTrigger(false);

        pickup.whenAskedFor();

        await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> verify(runner, org.mockito.Mockito.never()).runOnce());
    }

    /** The poll is not the trigger, and is not switched off with it. */
    @Test
    void poll_whenPickingUpOnATriggerIsOff_thenAPassStillRuns() {
        properties.setPickUpOnTrigger(false);

        pickup.poll();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(runner).runOnce());
    }

    /** Once the instance is stopping, nothing queues another pass behind the one being given up on. */
    @Test
    void whenAskedFor_whenTheInstanceIsStopping_thenNoPassRuns() {
        pickup.stop();

        pickup.whenAskedFor();

        await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> verify(runner, org.mockito.Mockito.never()).runOnce());
    }

    /** A pass that threw is not allowed to take the pickup thread's next pass with it. */
    @Test
    void whenAPassThrows_thenTheNextOneStillRuns() {
        when(runner.runOnce()).thenThrow(new IllegalStateException("no")).thenReturn(true);

        pickup.whenAskedFor();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(runner).runOnce());
        pickup.whenAskedFor();

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> verify(runner, org.mockito.Mockito.times(2)).runOnce());
    }
}
