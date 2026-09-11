package ch.admin.bit.jeap.doc.domain.custom;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Puts the sweep of unreferenced objects on the schedule the configuration asks for.
 * <p>
 * The schedule is read from {@link CustomProperties} rather than written into an annotation next to it: a
 * default spelled in two places is a default that changes in one of them.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
class CustomDocumentationSweepScheduling implements SchedulingConfigurer {

    private final CustomDocumentationSweep sweep;
    private final CustomProperties properties;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        String cron = properties.getSweepCron();
        if (cron == null || cron.isBlank() || Scheduled.CRON_DISABLED.equals(cron)) {
            log.info("Objects no documentation set names are not swept up: "
                     + "'jeap.doc.custom.sweep-cron' is '{}'.", cron);
            return;
        }
        registrar.addCronTask(sweep::removeUnreferencedObjects, cron);
        log.info("Objects no documentation set names are swept up on the schedule '{}'.", cron);
    }
}
