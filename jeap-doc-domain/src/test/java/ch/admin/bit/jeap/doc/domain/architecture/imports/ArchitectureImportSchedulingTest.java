package ch.admin.bit.jeap.doc.domain.architecture.imports;

import ch.admin.bit.jeap.doc.domain.ArchitectureImportProperties;
import ch.admin.bit.jeap.doc.domain.SiteProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

/**
 * The two lease rules, which are only ever exercised while a service starts.
 * <p>
 * Both refuse at startup rather than at the first import, because an import runs a quarter of an hour later
 * and a configuration mistake found then is one found by an operator rather than by a deployment.
 */
class ArchitectureImportSchedulingTest {

    @Test
    void configureTasks_whenTheLeaseIsShorterThanTheKeepAliveMinimum_thenItRefusesToStart() {
        ArchitectureImportProperties properties = new ArchitectureImportProperties();
        properties.setLockLease(Duration.ofSeconds(10));

        assertThatIllegalStateException()
                .isThrownBy(() -> schedulingWith(properties).configureTasks(new ScheduledTaskRegistrar()))
                .withMessageContaining("lock-lease");
    }

    /**
     * A lease that runs out while a run is still fetching asks for a second instance to start one beside it.
     * The keep-alive makes that unlikely rather than impossible, and a check costs an if.
     */
    @Test
    void configureTasks_whenTheLeaseWouldExpireBeforeTheDeadline_thenItRefusesToStart() {
        ArchitectureImportProperties properties = new ArchitectureImportProperties();
        properties.setTimeout(Duration.ofMinutes(10));
        properties.setLockLease(Duration.ofMinutes(5));

        assertThatIllegalStateException()
                .isThrownBy(() -> schedulingWith(properties).configureTasks(new ScheduledTaskRegistrar()))
                .withMessageContaining("timeout");
    }

    @Test
    void configureTasks_whenTheDefaultsAreLeftAlone_thenItStarts() {
        assertThatCode(() -> schedulingWith(new ArchitectureImportProperties())
                .configureTasks(new ScheduledTaskRegistrar())).doesNotThrowAnyException();
    }

    /**
     * The default schedule, asserted by what it does rather than by its text: one import an hour, at a quarter
     * to. More often than that produces nothing a reader can see, because the sites are published hourly.
     */
    @Test
    void cron_whenItIsLeftAtItsDefault_thenAnImportRunsHourlyAtAQuarterTo() {
        CronExpression cron = CronExpression.parse(new ArchitectureImportProperties().getCron());

        LocalDateTime first = cron.next(LocalDateTime.of(2026, 9, 3, 5, 0));
        assertThat(first).isEqualTo(LocalDateTime.of(2026, 9, 3, 5, 45));
        assertThat(cron.next(first)).isEqualTo(LocalDateTime.of(2026, 9, 3, 6, 45));
    }

    /**
     * What the schedule is <b>for</b>: a site on its default publication schedule generates from a model that
     * was imported twenty minutes earlier, not from one that is an hour old.
     */
    @Test
    void cron_whenBothAreLeftAtTheirDefaults_thenAnImportStandsInFrontOfEveryPublication() {
        CronExpression importCron = CronExpression.parse(new ArchitectureImportProperties().getCron());
        CronExpression publication = CronExpression.parse(new SiteProperties.Site().getPublicationSchedule());

        LocalDateTime middleOfTheMorning = LocalDateTime.of(2026, 9, 3, 9, 30);
        LocalDateTime nextPublication = publication.next(middleOfTheMorning);
        LocalDateTime nextImport = importCron.next(middleOfTheMorning);

        assertThat(nextImport).isBefore(nextPublication);
        assertThat(Duration.between(nextImport, nextPublication)).isEqualTo(Duration.ofMinutes(20));
    }

    private static ArchitectureImportScheduling schedulingWith(ArchitectureImportProperties properties) {
        // The job is never asked anything: an instance with no environment configured schedules nothing, which
        // is what makes the checks the only thing under test here.
        ArchitectureImportJob job = mock(ArchitectureImportJob.class);
        return new ArchitectureImportScheduling(job, properties, mock(TaskExecutor.class));
    }
}
