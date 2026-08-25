package ch.admin.bit.jeap.doc.domain;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/**
 * Registers the domain services of the doc service.
 * <p>
 * The domain module holds the business logic and the ports it needs; the adapters are wired to those ports by
 * the auto-configuration of the adapter modules.
 */
@AutoConfiguration
@ComponentScan
@EnableScheduling
// Long enough that a clean-up which takes its time is not run twice, short enough that an instance dying with the
// lock does not skip more than one night.
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
@EnableConfigurationProperties(UploadProperties.class)
public class DocDomainConfiguration {

    /**
     * The clock the domain reads the time from - a test can replace it to control what "now" is.
     */
    @Bean
    @ConditionalOnMissingBean
    Clock docClock() {
        return Clock.systemDefaultZone();
    }
}
