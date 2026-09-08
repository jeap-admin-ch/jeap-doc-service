package ch.admin.bit.jeap.doc.domain;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.time.Duration;

/**
 * Runs the two jobs of the build: picking up what has been asked for, and forgetting what is over.
 * <p>
 * <b>A site has no publication schedule of its own.</b> What publishes it hourly is the architecture import,
 * which asks for every part of every site documenting the environment it imported. One schedule rather than
 * one per site, and no way for the two to disagree about how often a site is rebuilt.
 * <p>
 * A site no import publishes - one whose environments have no architecture repository - is reconciled on
 * {@code jeap.doc.build.reconcile-cron} instead, so that a template or generator change reaches it without
 * somebody uploading to it.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
class DocumentationBuildScheduling implements SchedulingConfigurer {

    private final DocumentationBuildPickup pickup;
    private final DocumentationBuildHousekeeping housekeeping;
    private final DocumentationBuildTrigger trigger;
    private final BuildProperties properties;

    /**
     * The shortest lock lease the keep-alive provider accepts - it extends a lock at half its lease and refuses
     * one it could not extend often enough to be worth wrapping. Checked here rather than left to the provider,
     * because the provider would only refuse at the first build, which is a quarter of an hour into a
     * deployment that already looked successful.
     */
    static final Duration MINIMUM_LOCK_LEASE = Duration.ofSeconds(30);

    /**
     * The fewest published sites an instance may keep: the one being served, and the one the other instances
     * may still be serving from while their publication cache has not expired.
     */
    static final int MINIMUM_RETENTION = 2;

    /** The fewest pages a static-generation task may carry: below one the generator renders nothing. */
    static final int MINIMUM_SSG_TASK_SIZE = 1;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (properties.getLockLease().compareTo(MINIMUM_LOCK_LEASE) < 0) {
            throw new IllegalStateException(("jeap.doc.build.lock-lease is %s, and the shortest lease a build "
                                             + "lock may be taken with is %s.")
                    .formatted(properties.getLockLease(), MINIMUM_LOCK_LEASE));
        }
        if (properties.getRetention() < MINIMUM_RETENTION) {
            // One is not enough either: the superseded site is deleted the moment a build succeeds, while the
            // other instances still serve from it until their publication cache expires - so every build would
            // give their readers a few seconds of 404 and 503. Zero would delete the site it had just
            // published, and the site would never come back.
            throw new IllegalStateException(("jeap.doc.build.retention is %d. At least %d has to be kept: the "
                                             + "site being served, and the one other instances may still be "
                                             + "serving from their publication cache.")
                    .formatted(properties.getRetention(), MINIMUM_RETENTION));
        }
        if (properties.getSsgTaskSize() < MINIMUM_SSG_TASK_SIZE) {
            // The site generator does not check this one. It parses the value and chunks the pages by it, and
            // a chunk size of zero or less yields no chunks at all - so the build would succeed, publish a
            // site of no pages and replace a good one with it.
            throw new IllegalStateException(("jeap.doc.build.ssg-task-size is %d. At least %d is needed: the "
                                             + "site generator hands its workers that many pages at a time, "
                                             + "and fewer means it renders no page at all and reports no "
                                             + "error.")
                    .formatted(properties.getSsgTaskSize(), MINIMUM_SSG_TASK_SIZE));
        }
        // The pass runs on the pickup's own thread and this task only asks for one, so a build that takes
        // minutes does not hold the scheduler thread that the architecture import and the clean-up share.
        registrar.addFixedDelayTask(pickup::poll, properties.getPollInterval());
        log.info("Documentation builds are picked up every {}, and {}.", properties.getPollInterval(),
                properties.isPickUpOnTrigger() ? "as soon as one is asked for" : "only then");
        registrar.addCronTask(housekeeping::removeOldBuilds, properties.getHistoryCron());
        log.info("The record of builds that finished more than {} ago is removed on the schedule '{}'.",
                properties.getHistoryRetention(), properties.getHistoryCron());
        registerReconcile(registrar);
    }

    /**
     * The reconcile schedule, which is only about the sites no import publishes. Switched off with {@code "-"},
     * the value Spring reads as no schedule at all - a landscape whose every site is imported for needs none.
     */
    private void registerReconcile(ScheduledTaskRegistrar registrar) {
        String cron = properties.getReconcileCron();
        if (cron == null || cron.isBlank() || Scheduled.CRON_DISABLED.equals(cron)) {
            log.info("The sites no architecture import publishes are not reconciled on a schedule.");
            return;
        }
        registrar.addCronTask(trigger::requestBecauseNothingElsePublishesTheSite, cron);
        log.info("The sites no architecture import publishes are asked for on the schedule '{}'.", cron);
    }
}
