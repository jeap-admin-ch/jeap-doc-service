package ch.admin.bit.jeap.doc.domain;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Puts the clean-up of the uploads on the schedule the configuration asks for.
 * <p>
 * The schedule is read from {@link UploadProperties.Housekeeping} rather than written into an annotation next to
 * it: a default that is spelled in two places is a default that changes in one of them.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
class DocumentationUploadHousekeepingScheduling implements SchedulingConfigurer {

    private final DocumentationUploadHousekeeping housekeeping;
    private final UploadProperties uploadProperties;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        UploadProperties.Housekeeping configuration = uploadProperties.getHousekeeping();
        if (!configuration.isEnabled()) {
            log.info("Old uploads are not removed: 'jeap.doc.upload.housekeeping.enabled' is false.");
            return;
        }
        registrar.addCronTask(housekeeping::removeOldUploads, configuration.getCron());
        log.info("Uploads received more than {} ago are removed on the schedule '{}'.",
                configuration.getRetention(), configuration.getCron());
    }
}
