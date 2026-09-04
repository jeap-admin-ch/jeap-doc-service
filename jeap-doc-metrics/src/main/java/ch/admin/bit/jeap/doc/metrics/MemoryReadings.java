package ch.admin.bit.jeap.doc.metrics;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the memory of the container is read from: the kernel's own books, on Linux, and nowhere else.
 * <p>
 * Everything here is a file the kernel keeps - the cgroup memory files of the container - so a reading costs a
 * handful of small reads and measures nothing itself. None of it exists off Linux, which is why
 * {@link #availability} decides once, up front, whether it can be read at all, and why what is built on it
 * registers no meter where the answer is no.
 * <p>
 * The parsing takes strings and the availability takes a filesystem root, so that every layout is testable on
 * any machine; the real files are only touched on Linux.
 */
final class MemoryReadings {

    /** Which cgroup version keeps the container's memory files, if either. */
    enum CgroupLayout { NONE, V1, V2 }

    /**
     * The decision, made once.
     *
     * @param root where {@code sys} hangs - {@code /} outside a test
     */
    record Availability(CgroupLayout layout, Path root) {

        boolean isKnown() {
            return layout != CgroupLayout.NONE;
        }
    }

    /**
     * The container, from its cgroup. Every field but the first may be -1 for <i>not known</i>: a cgroup
     * without a limit, a kernel without {@code memory.peak}.
     *
     * @param currentBytes what the cgroup uses now, page cache included
     * @param limitBytes   the limit it is killed at, or -1 when there is none
     * @param peakBytes    the kernel's own high-water mark of the cgroup
     * @param oomKills     how many processes the kernel has killed in this cgroup so far
     */
    record CgroupMemory(long currentBytes, long limitBytes, long peakBytes, long oomKills) {
    }

    /** The host's view, from the JVM's platform bean - container-aware, and the limit where the cgroup names none. */
    record HostMemory(long totalBytes, long freeBytes) {
    }

    /** What a reader reads through, so that a test can hand it readings of its own. */
    interface MemorySource {

        Optional<CgroupMemory> cgroup();

        Optional<HostMemory> host();

        /**
         * Resets the kernel's high-water mark, and answers whether it could be. Only recent kernels allow it;
         * where they do not, a peak is attributed to a build by comparing against where the mark stood.
         */
        boolean resetPeak();
    }

    private static final Pattern STAT_LINE = Pattern.compile("^(\\S+)\\s+(\\d+)$", Pattern.MULTILINE);

    /** Above this, a cgroup v1 limit means "none": the kernel writes its page counter maximum there. */
    private static final long V1_NO_LIMIT = 1L << 60;

    private static final Path CGROUP_V2 = Path.of("sys", "fs", "cgroup");
    private static final Path CGROUP_V1 = Path.of("sys", "fs", "cgroup", "memory");

    private static final String V2_PEAK = "memory.peak";
    private static final String V1_PEAK = "memory.max_usage_in_bytes";

    private MemoryReadings() {
    }

    /** The decision for this JVM, on this machine. */
    static Availability availability() {
        return availability(System.getProperty("os.name"), Path.of("/"));
    }

    /**
     * The decision for the given platform and filesystem: nothing unless this is Linux with readable cgroup
     * memory files - v2 first, then v1.
     */
    static Availability availability(String osName, Path root) {
        if (osName == null || !osName.startsWith("Linux")) {
            return new Availability(CgroupLayout.NONE, root);
        }
        Path v2 = root.resolve(CGROUP_V2);
        if (Files.isReadable(v2.resolve("memory.current")) && Files.isReadable(v2.resolve("memory.max"))) {
            return new Availability(CgroupLayout.V2, root);
        }
        Path v1 = root.resolve(CGROUP_V1);
        if (Files.isReadable(v1.resolve("memory.usage_in_bytes"))
            && Files.isReadable(v1.resolve("memory.limit_in_bytes"))) {
            return new Availability(CgroupLayout.V1, root);
        }
        return new Availability(CgroupLayout.NONE, root);
    }

    /** The real readings behind an availability. Nothing is read here; every call reads afresh. */
    static MemorySource sourceFor(Availability availability) {
        return new MemorySource() {

            @Override
            public Optional<CgroupMemory> cgroup() {
                return switch (availability.layout()) {
                    case V2 -> Optional.of(readCgroupV2(availability.root().resolve(CGROUP_V2)));
                    case V1 -> Optional.of(readCgroupV1(availability.root().resolve(CGROUP_V1)));
                    case NONE -> Optional.empty();
                };
            }

            @Override
            public Optional<HostMemory> host() {
                return hostMemory();
            }

            @Override
            public boolean resetPeak() {
                return switch (availability.layout()) {
                    case V2 -> reset(availability.root().resolve(CGROUP_V2).resolve(V2_PEAK));
                    case V1 -> reset(availability.root().resolve(CGROUP_V1).resolve(V1_PEAK));
                    case NONE -> false;
                };
            }
        };
    }

    /**
     * The memory the container is killed at: the cgroup's limit, or the total the JVM sees when the cgroup
     * names none - which is what Fargate does, where the limit lives in a cgroup above the container's own.
     * <p>
     * One method, because the number it answers is logged with a build and exported as a gauge, and the two
     * must not be able to disagree.
     */
    static long limitOf(CgroupMemory cgroup, HostMemory host) {
        if (cgroup != null && cgroup.limitBytes() >= 0) {
            return cgroup.limitBytes();
        }
        return host == null ? -1 : host.totalBytes();
    }

    static CgroupMemory readCgroupV2(Path cgroup) {
        return parseCgroupV2(read(cgroup.resolve("memory.current")), read(cgroup.resolve("memory.max")),
                readOptional(cgroup.resolve(V2_PEAK)), readOptional(cgroup.resolve("memory.events")));
    }

    /** The cgroup v2 files; {@code peak} and {@code events} may be null when the kernel has none. */
    static CgroupMemory parseCgroupV2(String current, String max, String peak, String events) {
        long limit = max.strip().equals("max") ? -1 : parseLong(max);
        return new CgroupMemory(parseLong(current), limit, peak == null ? -1 : parseLong(peak),
                statValue(events, "oom_kill"));
    }

    static CgroupMemory readCgroupV1(Path cgroup) {
        return parseCgroupV1(read(cgroup.resolve("memory.usage_in_bytes")),
                read(cgroup.resolve("memory.limit_in_bytes")), readOptional(cgroup.resolve(V1_PEAK)),
                readOptional(cgroup.resolve("memory.oom_control")));
    }

    /** The cgroup v1 files; {@code maxUsage} and {@code oomControl} may be null. */
    static CgroupMemory parseCgroupV1(String usage, String limit, String maxUsage, String oomControl) {
        long limitBytes = parseLong(limit);
        return new CgroupMemory(parseLong(usage), limitBytes >= V1_NO_LIMIT ? -1 : limitBytes,
                maxUsage == null ? -1 : parseLong(maxUsage), statValue(oomControl, "oom_kill"));
    }

    /** The JVM's platform bean, which knows the container's limit on a JDK that reads cgroups - or nothing. */
    static Optional<HostMemory> hostMemory() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            return Optional.of(new HostMemory(os.getTotalMemorySize(), os.getFreeMemorySize()));
        }
        return Optional.empty();
    }

    /** One {@code key value} line of a stat file, or -1 when the file or the key is absent. */
    static long statValue(String text, String key) {
        if (text == null) {
            return -1;
        }
        Matcher matcher = STAT_LINE.matcher(text);
        while (matcher.find()) {
            if (matcher.group(1).equals(key)) {
                return Long.parseLong(matcher.group(2));
            }
        }
        return -1;
    }

    /**
     * Writing to the high-water mark resets it - on the kernels that allow it. Everywhere else the write is
     * refused, and that is an answer rather than a fault: the caller then attributes a peak to a build by
     * comparing against where the mark stood when it started.
     */
    private static boolean reset(Path peak) {
        try {
            Files.writeString(peak, "0", StandardCharsets.UTF_8);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static long parseLong(String text) {
        return Long.parseLong(text.strip());
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String readOptional(Path file) {
        return Files.isReadable(file) ? read(file) : null;
    }
}
