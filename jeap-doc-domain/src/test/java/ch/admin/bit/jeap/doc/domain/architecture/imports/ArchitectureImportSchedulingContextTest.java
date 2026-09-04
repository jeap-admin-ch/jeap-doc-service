package ch.admin.bit.jeap.doc.domain.architecture.imports;

import ch.admin.bit.jeap.doc.domain.ArchitectureImportProperties;
import ch.admin.bit.jeap.doc.domain.DocDomainConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.core.task.TaskExecutor;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * That the catch-up import survives an instance whose context holds more than one task executor.
 * <p>
 * It is the shape of a real instance: the database schema publisher and the OpenAPI publisher each contribute
 * an executor of their own, and Spring Boot declares one for the application beside them. Asking such a context
 * for <i>the</i> {@code TaskExecutor} has no answer, and because this runs on
 * {@code ApplicationReadyEvent} the failure lands <b>after</b> the service has started - Spring reports it
 * through the same "application failed to start" path as a context that never came up, and the process exits.
 * Naming the executor is what keeps that from depending on which starters an instance happens to add.
 */
class ArchitectureImportSchedulingContextTest {

    private static final String ENVIRONMENT = "dev";

    @Test
    void importWhatIsMissing_whenTheContextHoldsSeveralTaskExecutors_thenItRunsOnTheOneTheDocServiceDeclares()
            throws InterruptedException {
        CountDownLatch imported = new CountDownLatch(1);
        AtomicReference<String> ranOnThread = new AtomicReference<>();
        ArchitectureImportJob job = mock(ArchitectureImportJob.class);
        when(job.environments()).thenReturn(List.of(ENVIRONMENT));
        doAnswer(invocation -> {
            ranOnThread.set(Thread.currentThread().getName());
            imported.countDown();
            return null;
        }).when(job).importWhatIsMissing();

        new ApplicationContextRunner()
                .withUserConfiguration(ExecutorsOfOtherStarters.class, DocServiceExecutor.class,
                        ArchitectureImportScheduling.class)
                .withBean(ArchitectureImportProperties.class, ArchitectureImportProperties::new)
                .withBean(ArchitectureImportJob.class, () -> job)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    context.getBean(ArchitectureImportScheduling.class).importWhatIsMissing();
                });

        assertThat(imported.await(5, TimeUnit.SECONDS))
                .as("the catch-up import ran")
                .isTrue();
        assertThat(ranOnThread.get())
                .as("it ran on the executor the doc service declares for it, not on another starter's")
                .startsWith("architecture-import-");
    }

    /**
     * The scheduled import is handed off the same way. Run inline, an import held a scheduler thread for
     * minutes - and the cron fires for every environment at once, so it held all of them, and the build poll
     * beside it waited for the last import to end.
     */
    @Test
    void configureTasks_thenTheCronHandsTheImportToTheImportExecutorAndReturns() throws InterruptedException {
        CountDownLatch imported = new CountDownLatch(1);
        AtomicReference<String> ranOnThread = new AtomicReference<>();
        ArchitectureImportJob job = mock(ArchitectureImportJob.class);
        when(job.environments()).thenReturn(List.of(ENVIRONMENT));
        doAnswer(invocation -> {
            ranOnThread.set(Thread.currentThread().getName());
            imported.countDown();
            return null;
        }).when(job).importEnvironment(ENVIRONMENT);

        new ApplicationContextRunner()
                .withUserConfiguration(ExecutorsOfOtherStarters.class, DocServiceExecutor.class,
                        ArchitectureImportScheduling.class)
                .withBean(ArchitectureImportProperties.class, ArchitectureImportProperties::new)
                .withBean(ArchitectureImportJob.class, () -> job)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
                    context.getBean(ArchitectureImportScheduling.class).configureTasks(registrar);
                    assertThat(registrar.getCronTaskList()).hasSize(1);
                    // What the scheduler would run when the cron fires - on this thread, standing in for it.
                    registrar.getCronTaskList().getFirst().getRunnable().run();
                    assertThat(imported.await(5, TimeUnit.SECONDS)).as("the import ran").isTrue();
                });

        assertThat(ranOnThread.get())
                .as("it ran on the import executor, not on the thread the cron fired on")
                .startsWith("architecture-import-")
                .isNotEqualTo(Thread.currentThread().getName());
    }

    /**
     * An instance that has never imported anything and is switched off does not touch the executor at all -
     * the guard is what lets an instance with a broken upstream be started deliberately.
     */
    @Test
    void importWhatIsMissing_whenTheStartupCatchUpIsSwitchedOff_thenNothingIsImported() {
        ArchitectureImportJob job = mock(ArchitectureImportJob.class);
        ArchitectureImportProperties properties = new ArchitectureImportProperties();
        properties.setOnStartup(false);

        new ApplicationContextRunner()
                .withUserConfiguration(ExecutorsOfOtherStarters.class, DocServiceExecutor.class,
                        ArchitectureImportScheduling.class)
                .withBean(ArchitectureImportProperties.class, () -> properties)
                .withBean(ArchitectureImportJob.class, () -> job)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    context.getBean(ArchitectureImportScheduling.class).importWhatIsMissing();
                });

        verify(job, never()).importWhatIsMissing();
    }

    /**
     * What an instance carries beside the doc service. The names are the ones of the real starters, so that the
     * context under test is the one the failure was found in.
     */
    @Configuration(proxyBeanMethods = false)
    static class ExecutorsOfOtherStarters {

        @Bean
        TaskExecutor applicationTaskExecutor() {
            return new SimpleAsyncTaskExecutor("application-");
        }

        @Bean
        TaskExecutor dbSchemaPublisherTaskExecutor() {
            return new SimpleAsyncTaskExecutor("db-schema-publisher-");
        }

        @Bean
        TaskExecutor openApiSpecPublisherTaskExecutor() {
            return new SimpleAsyncTaskExecutor("openapi-spec-publisher-");
        }
    }

    /**
     * The executor of the doc service itself, declared here as {@link DocDomainConfiguration} declares it.
     */
    @Configuration(proxyBeanMethods = false)
    static class DocServiceExecutor {

        @Bean(name = DocDomainConfiguration.ARCHITECTURE_IMPORT_TASK_EXECUTOR)
        ThreadPoolTaskExecutor architectureImportTaskExecutor() {
            return ArchitectureImportExecutor.create();
        }
    }
}
