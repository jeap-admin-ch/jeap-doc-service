package ch.admin.bit.jeap.doc.domain.governance;

import ch.admin.bit.jeap.doc.domain.GovernanceImportProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Puts the governance import on the schedule the configuration asks for.
 * <p>
 * The schedule is read from {@link GovernanceImportProperties} rather than written into an annotation next to
 * it, for the same reason {@code ArchitectureImportScheduling} does: so that <i>why is the site stale</i> is
 * answered by the first lines of the log rather than by reading the configuration of a running service.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
class GovernanceImportScheduling implements SchedulingConfigurer {

    private final GovernanceImportPickup pickup;
    private final GovernanceImportProperties properties;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        String cron = properties.getCron();
        if (cron == null || cron.isBlank() || Scheduled.CRON_DISABLED.equals(cron)) {
            log.info("The Governance Service is not imported on a schedule: "
                     + "'jeap.doc.governance.import.cron' is '{}'.", cron);
            return;
        }
        // Handed to the pickup, never run here: the scheduler thread is back within a millisecond, and a
        // webhook call that arrives at the same moment merges with this run instead of starting beside it.
        registrar.addCronTask(pickup::poll, cron);
        log.info("The Governance Service is imported on the schedule '{}', and after every webhook call.", cron);
    }
}
