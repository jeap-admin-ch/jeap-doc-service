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
    private DocumentationBuildPickup pickup;
    @Mock
    private DocumentationBuildHousekeeping housekeeping;
    @Mock
    private DepartedParts departedParts;
    @Mock
    private DocumentationBuildTrigger trigger;

    private BuildProperties properties;
    private ScheduledTaskRegistrar registrar;

    @BeforeEach
    void setUp() {
        properties = new BuildProperties();
        registrar = new ScheduledTaskRegistrar();
    }

    /**
     * Four tasks: the poll that picks up what has been asked for, the two nightly clean-ups, and the reconcile
     * of the sites no architecture import publishes. A site still has no publication schedule of its own.
     */
    @Test
    void configureTasks_thenThePollTheHousekeepingAndTheReconcileAreRegistered() {
        scheduling().configureTasks(registrar);

        assertThat(registrar.getFixedDelayTaskList())
                .describedAs("the poll, on a fixed delay").hasSize(1);
        assertThat(registrar.getCronTaskList())
                .describedAs("the two clean-ups and the reconcile, and nothing per site").hasSize(3);
    }

    @Test
    void configureTasks_thenTheReconcileIsOnTheConfiguredSchedule() {
        properties.setReconcileCron("0 0 7 * * *");

        scheduling().configureTasks(registrar);

        assertThat(registrar.getCronTaskList()).extracting(task -> task.getExpression())
                .contains("0 0 7 * * *");
    }

    /** A landscape whose every site is imported for needs no reconcile, and can switch it off. */
    @ParameterizedTest
    @ValueSource(strings = {"-", ""})
    void configureTasks_whenTheReconcileIsSwitchedOff_thenOnlyTheCleanUpsAreOnACron(String cron) {
        properties.setReconcileCron(cron);

        scheduling().configureTasks(registrar);

        assertThat(registrar.getCronTaskList())
                .describedAs("the two nightly clean-ups, and nothing else").hasSize(2);
        assertThat(registrar.getCronTaskList()).extracting(task -> task.getExpression())
                .containsOnly(properties.getHistoryCron());
    }

    @Test
    void configureTasks_thenThePollIsOnTheConfiguredPollInterval() {
        properties.setPollInterval(Duration.ofSeconds(45));

        scheduling().configureTasks(registrar);

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

        assertThatThrownBy(() -> scheduling().configureTasks(registrar))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jeap.doc.build.lock-lease")
                .hasMessageContaining("PT5S");
    }

    @Test
    void configureTasks_whenTheLockLeaseIsExactlyTheMinimum_thenItIsAccepted() {
        properties.setLockLease(DocumentationBuildScheduling.MINIMUM_LOCK_LEASE);

        assertThatCode(() -> scheduling().configureTasks(registrar))
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

        assertThatThrownBy(() -> scheduling().configureTasks(registrar))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jeap.doc.build.retention");
    }

    @Test
    void configureTasks_whenTheRetentionIsTheMinimum_thenItIsAccepted() {
        properties.setRetention(DocumentationBuildScheduling.MINIMUM_RETENTION);

        assertThatCode(() -> scheduling().configureTasks(registrar))
                .doesNotThrowAnyException();
    }

    /**
     * The one property here that is about correctness rather than speed. The site generator parses this value
     * and chunks the pages of a part by it, without checking it - and a chunk size below one yields no chunks,
     * so the build succeeds, publishes a site of no pages and replaces a good one with it.
     */
    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void configureTasks_whenAStaticGenerationTaskWouldCarryNoPages_thenTheStartupFails(int taskSize) {
        properties.setSsgTaskSize(taskSize);

        assertThatThrownBy(() -> scheduling().configureTasks(registrar))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jeap.doc.build.ssg-task-size");
    }

    @Test
    void configureTasks_whenAStaticGenerationTaskCarriesOnePage_thenItIsAccepted() {
        properties.setSsgTaskSize(DocumentationBuildScheduling.MINIMUM_SSG_TASK_SIZE);

        assertThatCode(() -> scheduling().configureTasks(registrar)).doesNotThrowAnyException();
    }

    private DocumentationBuildScheduling scheduling() {
        return new DocumentationBuildScheduling(pickup, housekeeping, departedParts, trigger, properties);
    }

}
