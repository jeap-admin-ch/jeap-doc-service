package ch.admin.bit.jeap.doc.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What is put on the schedule while the service starts.
 * <p>
 * The sites are configured rather than discovered, so this is where the configuration becomes tasks - and where
 * a configuration that could not work has to fail, rather than a quarter of an hour into the first build of a
 * deployment that already looked successful.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentationBuildSchedulingTest {

    @Mock
    private DocumentationBuildTrigger trigger;
    @Mock
    private DocumentationBuildRunner runner;
    @Mock
    private DocumentationBuildHousekeeping housekeeping;

    private BuildProperties properties;
    private ScheduledTaskRegistrar registrar;

    @BeforeEach
    void setUp() {
        properties = new BuildProperties();
        registrar = new ScheduledTaskRegistrar();
    }

    @Test
    void configureTasks_thenTheRunnerTheHousekeepingAndEverySiteWithAScheduleAreRegistered() {
        SiteProperties.Site governance = new SiteProperties.Site();
        governance.setPublicationSchedule("0 15 * * * *");

        scheduling(propertiesOf(Map.of(Site.DEFAULT_SITE, new SiteProperties.Site(), "governance", governance)))
                .configureTasks(registrar);

        assertThat(registrar.getFixedDelayTaskList())
                .describedAs("the runner, on a fixed delay").hasSize(1);
        // The build housekeeping, and one task per site that configures a schedule.
        assertThat(registrar.getCronTaskList()).hasSize(3);
    }

    /**
     * A site with no schedule is published only when something is uploaded to it. That is a legitimate thing to
     * want and needs no separate flag - so nothing must be registered for it.
     */
    @Test
    void configureTasks_whenASiteConfiguresNoSchedule_thenNoTaskIsRegisteredForIt() {
        SiteProperties.Site onUploadOnly = new SiteProperties.Site();
        onUploadOnly.setPublicationSchedule(null);

        scheduling(propertiesOf(Map.of(Site.DEFAULT_SITE, onUploadOnly))).configureTasks(registrar);

        // The build housekeeping only - nothing for the site itself.
        assertThat(registrar.getCronTaskList()).hasSize(1);
    }

    @Test
    void configureTasks_thenTheRunnerIsOnTheConfiguredPollInterval() {
        properties.setPollInterval(Duration.ofSeconds(45));

        scheduling(new SiteProperties()).configureTasks(registrar);

        assertThat(registrar.getFixedDelayTaskList()).singleElement()
                .extracting(task -> task.getIntervalDuration())
                .isEqualTo(Duration.ofSeconds(45));
    }

    /**
     * The keep-alive provider refuses a lease it could not extend often enough to be worth wrapping, and it
     * refuses it at the first build. Caught here instead, while the service starts.
     */
    @Test
    void configureTasks_whenTheLockLeaseIsShorterThanTheMinimum_thenTheStartupFails() {
        properties.setLockLease(Duration.ofSeconds(5));

        assertThatThrownBy(() -> scheduling(new SiteProperties()).configureTasks(registrar))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jeap.doc.build.lock-lease")
                .hasMessageContaining("PT5S");
    }

    @Test
    void configureTasks_whenTheLockLeaseIsExactlyTheMinimum_thenItIsAccepted() {
        properties.setLockLease(DocumentationBuildScheduling.MINIMUM_LOCK_LEASE);

        assertThatCode(() -> scheduling(new SiteProperties()).configureTasks(registrar))
                .doesNotThrowAnyException();
    }

    /**
     * One is not enough: the superseded site is deleted the moment a build succeeds, while the other instances
     * still serve from it until their publication cache expires. Zero would delete the site it had just
     * published, and it would never come back.
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void configureTasks_whenTooFewSitesAreKept_thenTheStartupFails(int retention) {
        properties.setRetention(retention);

        assertThatThrownBy(() -> scheduling(new SiteProperties()).configureTasks(registrar))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jeap.doc.build.retention");
    }

    @Test
    void configureTasks_whenTheRetentionIsTheMinimum_thenItIsAccepted() {
        properties.setRetention(DocumentationBuildScheduling.MINIMUM_RETENTION);

        assertThatCode(() -> scheduling(new SiteProperties()).configureTasks(registrar))
                .doesNotThrowAnyException();
    }

    private DocumentationBuildScheduling scheduling(SiteProperties siteProperties) {
        return new DocumentationBuildScheduling(new DocumentationSites(siteProperties), trigger, runner,
                housekeeping, properties);
    }

    private static SiteProperties propertiesOf(Map<String, SiteProperties.Site> sites) {
        SiteProperties properties = new SiteProperties();
        properties.setSites(new LinkedHashMap<>(sites));
        return properties;
    }
}
