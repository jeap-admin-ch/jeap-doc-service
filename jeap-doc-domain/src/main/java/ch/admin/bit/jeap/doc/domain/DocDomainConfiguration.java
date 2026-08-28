package ch.admin.bit.jeap.doc.domain;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
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
@EnableConfigurationProperties({UploadProperties.class, SiteProperties.class, BuildProperties.class,
        PublicationProperties.class})
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
