package ch.admin.bit.jeap.doc.domain.architecture.imports;

import ch.admin.bit.jeap.doc.domain.ArchitectureImportProperties;
import ch.admin.bit.jeap.doc.domain.DocDomainConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.time.Duration;

/**
 * Puts the import of the architecture repository on the schedule the configuration asks for, and imports once
 * at startup what has never been imported.
 * <p>
 * The schedule is read from the properties rather than written into an annotation, and it is logged while the
 * service starts - so <i>why is the model old</i> is answered by the first lines of the log rather than by
 * reading the configuration of a running service.
 * <p>
 * <b>Nothing here imports on the scheduler's thread.</b> The cron only hands the environment to the import
 * executor, the same one the catch-up runs on, and returns. An import takes minutes, the cron fires for every
 * environment at the same minute, and the scheduler's threads are what the build poll and the scheduled
 * publications run on: imports run inline held all of them, and a build asked for at the start of the hour was
 * looked for when the last import ended.
 */
@Slf4j
@Configuration
class ArchitectureImportScheduling implements SchedulingConfigurer {

    /**
     * The shortest lease the keep-alive lock provider accepts. The same one the documentation builds use, and
     * checked here rather than left to the provider, which would only refuse at the first import.
     */
    static final Duration MINIMUM_LOCK_LEASE = Duration.ofSeconds(30);

    private final ArchitectureImportJob job;
    private final ArchitectureImportProperties properties;
    /**
     * The executor every import runs on: the one the doc service declares for it, named rather than "whatever
     * {@code TaskExecutor} this context happens to have". An instance may add starters that contribute
     * executors of their own, and asking for the single one of a context then fails an instance that had
     * already started - see {@link DocDomainConfiguration#ARCHITECTURE_IMPORT_TASK_EXECUTOR}.
     */
    private final TaskExecutor taskExecutor;

    /**
     * Written out rather than generated: the qualifier has to reach the constructor <b>parameter</b> for
     * Spring to resolve it, and Lombok does not carry an annotation from the field onto the parameter it
     * generates. A generated constructor leaves the executor ambiguous again, which is the whole defect.
     */
    ArchitectureImportScheduling(ArchitectureImportJob job, ArchitectureImportProperties properties,
                                 @Qualifier(DocDomainConfiguration.ARCHITECTURE_IMPORT_TASK_EXECUTOR)
                                 TaskExecutor taskExecutor) {
        this.job = job;
        this.properties = properties;
        this.taskExecutor = taskExecutor;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        check();
        if (job.environments().isEmpty()) {
            log.info("No architecture repository is configured, so nothing is imported. Configure "
                     + "jeap.doc.archrepo.environments.<environment>.");
            return;
        }
        String cron = properties.getCron();
        if (cron == null || cron.isBlank()) {
            log.info("The architecture repository is not imported on a schedule: "
                     + "'jeap.doc.archrepo.import.cron' is empty.");
            return;
        }
        for (String environment : job.environments()) {
            // Handed off, never run here: the scheduler thread is back within a millisecond, and the imports
            // queue on the one import thread and run one after the other.
            registrar.addCronTask(() -> taskExecutor.execute(() -> job.importEnvironment(environment)), cron);
            log.info("The architecture repository of the environment {} is imported on the schedule '{}'.",
                    environment, cron);
        }
    }

    /**
     * Imports what has never been imported, once, after the service is up.
     * <p>
     * On the import executor rather than inline: an architecture repository that is slow must not hold up the
     * readiness of this service, which can serve the documentation it already published either way. It takes
     * the same lock as the schedule, so of several instances rolling out together one imports and the rest
     * find the model already there.
     * <p>
     * <b>Anything thrown here fails the service after it has started.</b> Spring reports a listener of this
     * event through the same "application failed to start" path as a context that never came up, and the
     * process then exits - so the log says the service started, and the line after it says it did not.
     */
    @EventListener(ApplicationReadyEvent.class)
    void importWhatIsMissing() {
        if (!properties.isOnStartup() || job.environments().isEmpty()) {
            return;
        }
        taskExecutor.execute(job::importWhatIsMissing);
    }

    private void check() {
        if (properties.getLockLease().compareTo(MINIMUM_LOCK_LEASE) < 0) {
            throw new IllegalStateException(("jeap.doc.archrepo.import.lock-lease is %s, and the shortest lease "
                                             + "a lock may be taken with is %s.")
                    .formatted(properties.getLockLease(), MINIMUM_LOCK_LEASE));
        }
        if (properties.getLockLease().compareTo(properties.getTimeout()) <= 0) {
            // A lease that runs out while a run is still fetching invites a second instance to start one
            // alongside it. The keep-alive makes that unlikely rather than impossible, and a check costs an if.
            throw new IllegalStateException(("jeap.doc.archrepo.import.lock-lease is %s and "
                                             + "jeap.doc.archrepo.import.timeout is %s. The lease has to "
                                             + "outlive the deadline, or the lock can expire while an import is "
                                             + "still running and a second instance start one beside it.")
                    .formatted(properties.getLockLease(), properties.getTimeout()));
        }
    }
}
