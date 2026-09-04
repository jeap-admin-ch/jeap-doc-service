package ch.admin.bit.jeap.doc.domain.architecture.imports;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The one import thread and its bounded queue: a cron that finds the queue full is dropped with a warning, and
 * never thrown back into the scheduler that handed it over.
 */
class ArchitectureImportExecutorTest {

    @Test
    void execute_whenTheQueueIsFull_thenTheImportIsDroppedRatherThanThrown() throws InterruptedException {
        ThreadPoolTaskExecutor executor = ArchitectureImportExecutor.create();
        executor.initialize();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch blocked = new CountDownLatch(1);
        AtomicInteger ran = new AtomicInteger();
        try {
            executor.execute(() -> {
                blocked.countDown();
                await(release);
                ran.incrementAndGet();
            });
            assertThat(blocked.await(5, TimeUnit.SECONDS)).as("the one thread is busy").isTrue();

            for (int i = 0; i < ArchitectureImportExecutor.QUEUE_CAPACITY; i++) {
                executor.execute(ran::incrementAndGet);
            }
            assertThatCode(() -> executor.execute(ran::incrementAndGet))
                    .as("the one beyond the queue is dropped, not thrown")
                    .doesNotThrowAnyException();

            release.countDown();
            executor.getThreadPoolExecutor().shutdown();
            assertThat(executor.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            assertThat(ran.get()).isEqualTo(1 + ArchitectureImportExecutor.QUEUE_CAPACITY);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void create_thenItIsOneThreadNamedAfterTheImport() throws InterruptedException {
        ThreadPoolTaskExecutor executor = ArchitectureImportExecutor.create();
        executor.initialize();
        try {
            CountDownLatch done = new CountDownLatch(1);
            StringBuilder thread = new StringBuilder();
            executor.execute(() -> {
                thread.append(Thread.currentThread().getName());
                done.countDown();
            });
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(thread.toString()).startsWith(ArchitectureImportExecutor.THREAD_PREFIX);
            assertThat(executor.getMaxPoolSize()).isOne();
        } finally {
            executor.shutdown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
