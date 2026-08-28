package ch.admin.bit.jeap.doc.domain;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.time.Duration;

/**
 * Puts every documentation site on the schedule it configures.
 * <p>
 * One task per site, registered while the service starts: the sites are configured rather than discovered, so
 * there is nothing to enumerate later and no default task standing in for sites it has never heard of. A site
 * that configures no schedule is published only when something is uploaded to it, which is a legitimate thing to
 * want and needs no separate flag - a schedule that is not there is a schedule that does not run.
 * <p>
 * The schedules are logged at startup, so <i>why is this site not updating</i> is answered by the first lines of
 * the log rather than by reading the configuration of a running service.
 * <p>
 * The runner that serves the requests is registered here too, on a fixed delay: asking for a build and running
 * it are two things, and everything that wants a site rebuilt goes through the request.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
class DocumentationBuildScheduling implements SchedulingConfigurer {

    private final DocumentationSites sites;
    private final DocumentationBuildTrigger trigger;
    private final DocumentationBuildRunner runner;
    private final DocumentationBuildHousekeeping housekeeping;
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
        registrar.addFixedDelayTask(runner::runOnce, properties.getPollInterval());
        log.info("Documentation builds are picked up every {}.", properties.getPollInterval());
        registrar.addCronTask(housekeeping::removeOldBuilds, properties.getHistoryCron());
        log.info("The record of builds that finished more than {} ago is removed on the schedule '{}'.",
                properties.getHistoryRetention(), properties.getHistoryCron());
        for (Site site : sites.all()) {
            site.schedule().ifPresentOrElse(
                    cron -> {
                        registrar.addCronTask(() -> trigger.requestBecauseOfSchedule(site.id()), cron);
                        log.info("The documentation site {} is published on the schedule '{}'.", site.id(), cron);
                    },
                    () -> log.info("The documentation site {} is published only when something is uploaded to "
                                   + "it: it configures no publication schedule.", site.id()));
        }
    }
}
