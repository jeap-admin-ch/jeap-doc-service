package ch.admin.bit.jeap.doc.metrics;

import ch.admin.bit.jeap.doc.domain.port.ContainerMemory;
import ch.admin.bit.jeap.doc.metrics.MemoryReadings.Availability;
import ch.admin.bit.jeap.doc.metrics.MemoryReadings.CgroupLayout;
import ch.admin.bit.jeap.doc.metrics.MemoryReadings.CgroupMemory;
import ch.admin.bit.jeap.doc.metrics.MemoryReadings.HostMemory;
import ch.admin.bit.jeap.doc.metrics.MemoryReadings.MemorySource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the doc service says about the memory of its container.
 * <p>
 * Two things are asserted here that nothing else can: that the meters are <b>absent</b> where the numbers
 * cannot be read, rather than present and {@code NaN} - an alarm cannot tell those from a container that has
 * stopped reporting - and that the count of kills never goes backwards, because a counter that dips reads as a
 * reset and would report every earlier kill again as a fresh one.
 */
class MicrometerContainerMemoryMetricsTest {

    private static final long GB = 1024L * 1024 * 1024;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final FakeCgroup cgroup = new FakeCgroup();

    @Test
    void bindTo_thenTheUsageAndTheLimitAreTheNumbersTheCgroupHolds() {
        cgroup.memory = new CgroupMemory(7L * GB, 16L * GB, 11L * GB, 0);

        metrics().bindTo(registry);

        assertThat(registry.get(MicrometerContainerMemoryMetrics.USED).gauge().value()).isEqualTo(7L * GB);
        assertThat(registry.get(MicrometerContainerMemoryMetrics.LIMIT).gauge().value()).isEqualTo(16L * GB);
        assertThat(registry.get(MicrometerContainerMemoryMetrics.USED).gauge().getId().getBaseUnit())
                .isEqualTo("bytes");
    }

    /**
     * On Fargate the container's own {@code memory.max} says {@code max}, the limit being enforced a level
     * above it. The total the JVM sees is container-aware and stands in - the same number the peak of a build
     * is reported against, which is why one method answers both.
     */
    @Test
    void bindTo_whenTheCgroupNamesNoLimit_thenTheTotalTheJvmSeesStandsIn() {
        cgroup.memory = new CgroupMemory(7L * GB, -1, -1, 0);
        cgroup.host = new HostMemory(16L * GB, 9L * GB);

        metrics().bindTo(registry);

        assertThat(registry.get(MicrometerContainerMemoryMetrics.LIMIT).gauge().value()).isEqualTo(16L * GB);
    }

    @Test
    void bindTo_thenTheKillsOfTheKernelAreCounted() {
        cgroup.memory = new CgroupMemory(GB, 16L * GB, GB, 2);

        metrics().bindTo(registry);

        assertThat(registry.get(MicrometerContainerMemoryMetrics.OOM_KILLS).functionCounter().count())
                .isEqualTo(2);
    }

    /** A counter that dips reads as a reset, and every kill before it would be reported again as a new one. */
    @Test
    void oomKills_whenAReadingFails_thenItAnswersWhatTheLastOneSaid() {
        cgroup.memory = new CgroupMemory(GB, 16L * GB, GB, 3);
        MicrometerContainerMemoryMetrics metrics = metrics();
        metrics.bindTo(registry);
        assertThat(metrics.oomKills()).isEqualTo(3);

        cgroup.failing = true;

        assertThat(metrics.oomKills()).isEqualTo(3);
        assertThat(registry.get(MicrometerContainerMemoryMetrics.OOM_KILLS).functionCounter().count())
                .isEqualTo(3);
    }

    /** Three meters that always answer NaN are worse than none: an alarm cannot tell them from a dead task. */
    @Test
    void bindTo_whenTheContainerCannotBeRead_thenNoMeterIsRegisteredAtAll() {
        new MicrometerContainerMemoryMetrics(new Availability(CgroupLayout.NONE, Path.of("/")), cgroup)
                .bindTo(registry);

        assertThat(registry.getMeters()).isEmpty();
    }

    /**
     * The peak of one build, where the kernel lets its high-water mark be reset: what is read at the end is
     * this build's own.
     */
    @Test
    void measure_whenTheMarkCanBeReset_thenThePeakIsThisBuildsOwn() {
        cgroup.memory = new CgroupMemory(GB, 16L * GB, 10L * GB, 0);
        cgroup.resettable = true;

        ContainerMemory.Measurement measurement = metrics().measure();
        cgroup.memory = new CgroupMemory(2 * GB, 16L * GB, 11L * GB, 0);

        assertThat(measurement.peak()).contains(new ContainerMemory.Peak(11L * GB, 16L * GB, true));
    }

    /** Where it cannot be reset, a mark that rose is still this build's: nothing else was running. */
    @Test
    void measure_whenTheMarkRoseAboveWhereItStood_thenThePeakIsThisBuildsOwn() {
        cgroup.memory = new CgroupMemory(GB, 16L * GB, 8L * GB, 0);

        ContainerMemory.Measurement measurement = metrics().measure();
        cgroup.memory = new CgroupMemory(2 * GB, 16L * GB, 11L * GB, 0);

        assertThat(measurement.peak()).contains(new ContainerMemory.Peak(11L * GB, 16L * GB, true));
    }

    /**
     * Where it did not rise, the mark is still an earlier build's. All this build is known to have done is
     * stay under it, and that is what it says rather than claiming the earlier build's peak as its own.
     */
    @Test
    void measure_whenTheMarkDidNotRise_thenThePeakIsReportedAsAnUpperBound() {
        cgroup.memory = new CgroupMemory(GB, 16L * GB, 11L * GB, 0);

        ContainerMemory.Measurement measurement = metrics().measure();

        assertThat(measurement.peak()).contains(new ContainerMemory.Peak(11L * GB, 16L * GB, false));
    }

    /** An older kernel keeps no high-water mark, and a usage read after the build has ended is not a peak. */
    @Test
    void measure_whenTheKernelKeepsNoHighWaterMark_thenNothingIsClaimed() {
        cgroup.memory = new CgroupMemory(GB, 16L * GB, -1, 0);

        assertThat(metrics().measure().peak()).isEmpty();
    }

    @Test
    void measure_whenTheContainerCannotBeRead_thenNothingIsClaimed() {
        ContainerMemory memory =
                new MicrometerContainerMemoryMetrics(new Availability(CgroupLayout.NONE, Path.of("/")), cgroup);

        assertThat(memory.measure().peak()).isEmpty();
    }

    private MicrometerContainerMemoryMetrics metrics() {
        return new MicrometerContainerMemoryMetrics(new Availability(CgroupLayout.V2, Path.of("/")), cgroup);
    }

    /** The kernel's files, as this test decides they read. */
    private static final class FakeCgroup implements MemorySource {

        private CgroupMemory memory;
        private HostMemory host;
        private boolean resettable;
        private boolean failing;

        @Override
        public Optional<CgroupMemory> cgroup() {
            if (failing) {
                throw new IllegalStateException("the cgroup files went away");
            }
            return Optional.ofNullable(memory);
        }

        @Override
        public Optional<HostMemory> host() {
            return Optional.ofNullable(host);
        }

        @Override
        public boolean resetPeak() {
            if (resettable) {
                memory = new CgroupMemory(memory.currentBytes(), memory.limitBytes(), 0, memory.oomKills());
            }
            return resettable;
        }
    }
}
