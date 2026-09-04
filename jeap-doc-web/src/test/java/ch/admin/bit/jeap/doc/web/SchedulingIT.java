package ch.admin.bit.jeap.doc.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the scheduled tasks of the doc service run on Spring Boot's scheduler, with the pool the default
 * properties ask for - and not on the lock keep-alive thread.
 * <p>
 * Boot declares its scheduler only while the context holds no {@code ScheduledExecutorService}. The keep-alive
 * executor of the lock provider used to be one, so Boot stood down, every scheduled task fell back to that one
 * thread, and a build asked for during the imports was first looked for when the last import had ended. This
 * is the test that would have caught it.
 */
class SchedulingIT extends DocServiceIntegrationTestBase {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ProbeTask probe;

    @Test
    void theSchedulerIsSpringBootsThreadPool_andNoScheduledExecutorServiceStandsInForIt() {
        assertThat(context.getBean(TaskScheduler.class)).isInstanceOf(ThreadPoolTaskScheduler.class);
        assertThat(context.getBeansOfType(ScheduledExecutorService.class))
                .as("a ScheduledExecutorService bean would make Boot stand its scheduler down")
                .isEmpty();
        assertThat(((ThreadPoolTaskScheduler) context.getBean(TaskScheduler.class)).getPoolSize())
                .as("spring.task.scheduling.pool.size takes effect")
                .isGreaterThan(1);
    }

    @Test
    void aTaskRegisteredThroughASchedulingConfigurer_runsOnASchedulerThread() throws InterruptedException {
        assertThat(probe.ranOnThread(Duration.ofSeconds(10)))
                .startsWith("scheduling-")
                .doesNotStartWith("shedlock-keep-alive");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {

        @Bean
        ProbeTask probeTask() {
            return new ProbeTask();
        }
    }

    /** A fixed-delay task registered the way the doc service registers its own, recording where it ran. */
    static class ProbeTask implements SchedulingConfigurer {

        private final AtomicReference<String> thread = new AtomicReference<>();
        private final CountDownLatch ran = new CountDownLatch(1);

        @Override
        public void configureTasks(ScheduledTaskRegistrar registrar) {
            registrar.addFixedDelayTask(() -> {
                if (thread.compareAndSet(null, Thread.currentThread().getName())) {
                    ran.countDown();
                }
            }, Duration.ofMillis(100));
        }

        String ranOnThread(Duration atMost) throws InterruptedException {
            assertThat(ran.await(atMost.toMillis(), TimeUnit.MILLISECONDS)).as("the probe task ran").isTrue();
            return thread.get();
        }
    }
}
