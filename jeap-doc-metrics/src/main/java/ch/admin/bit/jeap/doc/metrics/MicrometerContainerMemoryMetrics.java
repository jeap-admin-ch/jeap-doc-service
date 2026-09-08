package ch.admin.bit.jeap.doc.metrics;

import ch.admin.bit.jeap.doc.metrics.MemoryReadings.CgroupMemory;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What the container this service runs in is using, and what it is killed at.
 * <p>
 * The numbers come from the cgroup memory files and from nowhere else - see {@link MemoryReadings}. They are
 * read at <b>scrape time</b>: there is no thread and no interval, and the resolution of the series is the
 * scrape interval of whoever reads it, which is also where a peak belongs -
 * {@code max_over_time(jeap_doc_container_memory_used_bytes[15m])} is the highest a build got.
 * <p>
 * <b>The gauges, and nothing per build.</b> This used to reset the kernel's high-water mark around each build
 * and put the result on the build's row - which was only ever right while one build ran at a time. With
 * {@code max-concurrent-parts} above one, each of the overlapping builds wiped what the others had
 * accumulated, so the row published a confidently exact number that was the peak since the last reset. The
 * series above answers the question the row was asked, over whatever window is wanted, and no build has to
 * claim a number that is not its own.
 * <p>
 * This is the doc service's own memory concern rather than a general one, because of what a build is: the site
 * generator is a child process whose bundler allocates natively, so <b>the JVM meters say nothing about the
 * largest thing this service does</b> and only the container's own numbers do.
 * <p>
 * <b>Nothing is registered where the files cannot be read</b> - off Linux, or without cgroup files. Three
 * meters that always answer {@code NaN} would be worse than no meters: an alarm cannot tell them from a
 * container that has stopped reporting.
 */
@Slf4j
@Component
public class MicrometerContainerMemoryMetrics implements MeterBinder {

    static final String USED = "jeap.doc.container.memory.used";
    static final String LIMIT = "jeap.doc.container.memory.limit";
    static final String OOM_KILLS = "jeap.doc.container.memory.oom.kills";

    private final MemoryReadings.Availability availability;
    private final MemoryReadings.MemorySource source;

    /**
     * The last count of kills that could be read.
     * <p>
     * A counter may never go backwards and may never be {@code NaN}: a dip reads as a counter reset, and
     * {@code increase()} would report the whole value again as a fresh kill. So a reading that fails answers
     * what the last one said.
     */
    private final AtomicLong lastOomKills = new AtomicLong();

    MicrometerContainerMemoryMetrics() {
        this(MemoryReadings.availability());
    }

    MicrometerContainerMemoryMetrics(MemoryReadings.Availability availability) {
        this(availability, MemoryReadings.sourceFor(availability));
    }

    MicrometerContainerMemoryMetrics(MemoryReadings.Availability availability, MemoryReadings.MemorySource source) {
        this.availability = availability;
        this.source = source;
    }

    /**
     * Registered as a binder rather than from a constructor: Spring Boot applies its meter filters before it
     * binds them, and a meter registered earlier makes the Prometheus registry warn about it on every start.
     */
    @Override
    public void bindTo(MeterRegistry registry) {
        if (!availability.isKnown()) {
            log.debug("The memory of the container cannot be read here, so it is not measured.");
            return;
        }
        Gauge.builder(USED, () -> value(CgroupMemory::currentBytes))
                .description("Memory the container of this service is using, page cache included")
                .baseUnit("bytes")
                .register(registry);
        Gauge.builder(LIMIT, this::limit)
                .description("Memory the container of this service is killed at")
                .baseUnit("bytes")
                .register(registry);
        FunctionCounter.builder(OOM_KILLS, this, self -> self.oomKills())
                .description("Processes the kernel has killed in this container for want of memory")
                .register(registry);
        log.info("The memory of the container is measured from its cgroup ({}).", availability.layout());
    }

    /** The count of kills, or the last one that could be read - never backwards, never {@code NaN}. */
    double oomKills() {
        read().map(CgroupMemory::oomKills)
                .filter(kills -> kills >= 0)
                .ifPresent(lastOomKills::set);
        return lastOomKills.get();
    }

    private double limit() {
        Optional<CgroupMemory> cgroup = read();
        if (cgroup.isEmpty()) {
            return Double.NaN;
        }
        long limit = MemoryReadings.limitOf(cgroup.get(), source.host().orElse(null));
        return limit < 0 ? Double.NaN : limit;
    }

    private double value(java.util.function.ToLongFunction<CgroupMemory> field) {
        return read().map(cgroup -> (double) field.applyAsLong(cgroup)).orElse(Double.NaN);
    }

    /**
     * One reading, or nothing. Never throws: these run on the scrape thread and while a build is being
     * recorded, and a cgroup file that has gone away must cost neither a scrape nor a build.
     */
    private Optional<CgroupMemory> read() {
        try {
            return source.cgroup();
        } catch (RuntimeException e) {
            log.debug("The memory of the container could not be read.", e);
            return Optional.empty();
        }
    }
}
