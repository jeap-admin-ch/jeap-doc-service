package ch.admin.bit.jeap.doc.metrics;

import ch.admin.bit.jeap.doc.metrics.MemoryReadings.Availability;
import ch.admin.bit.jeap.doc.metrics.MemoryReadings.CgroupLayout;
import ch.admin.bit.jeap.doc.metrics.MemoryReadings.CgroupMemory;
import ch.admin.bit.jeap.doc.metrics.MemoryReadings.HostMemory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The kernel's files, parsed - and the decision whether they can be read at all.
 * <p>
 * Every layout is exercised on every platform: the parsers take strings, and the availability takes a
 * filesystem root, so a fake cgroup under a temp directory stands in for the real one.
 */
class MemoryReadingsTest {

    @TempDir
    Path root;

    @Test
    void parseCgroupV2_thenEveryFieldIsRead() {
        CgroupMemory memory = MemoryReadings.parseCgroupV2("2841575424\n", "4294967296\n", "2925895680\n",
                "low 0\nhigh 0\nmax 3\noom 1\noom_kill 1\n");

        assertThat(memory).isEqualTo(new CgroupMemory(2841575424L, 4294967296L, 2925895680L, 1));
    }

    /** No limit reads as {@code max}, and an older kernel has neither {@code memory.peak} nor the events. */
    @Test
    void parseCgroupV2_whenThereIsNoLimitAndTheOptionalFilesAreAbsent_thenTheyAreNotKnown() {
        CgroupMemory memory = MemoryReadings.parseCgroupV2("1000\n", "max\n", null, null);

        assertThat(memory).isEqualTo(new CgroupMemory(1000, -1, -1, -1));
    }

    @Test
    void parseCgroupV1_thenEveryFieldIsRead() {
        CgroupMemory memory = MemoryReadings.parseCgroupV1("2841575424\n", "4294967296\n", "2925895680\n",
                "oom_kill_disable 0\nunder_oom 0\noom_kill 2\n");

        assertThat(memory).isEqualTo(new CgroupMemory(2841575424L, 4294967296L, 2925895680L, 2));
    }

    /** cgroup v1 writes its page counter maximum where there is no limit - a number, not a word. */
    @Test
    void parseCgroupV1_whenThereIsNoLimit_thenTheSentinelReadsAsNone() {
        CgroupMemory memory = MemoryReadings.parseCgroupV1("1000\n", "9223372036854771712\n", null, null);

        assertThat(memory.limitBytes()).isEqualTo(-1);
        assertThat(memory.peakBytes()).isEqualTo(-1);
    }

    /**
     * The limit is the cgroup's where it names one - and the total the JVM sees where it does not, which is
     * what a Fargate container's own {@code memory.max} says: the limit is enforced a level above it, and the
     * JVM's view of the machine is container-aware. Without this the usage would be reported against nothing.
     */
    @Test
    void limitOf_whenTheCgroupNamesNoLimit_thenTheTotalTheJvmSeesStandsIn() {
        CgroupMemory withLimit = new CgroupMemory(1000, 4096, -1, -1);
        CgroupMemory withoutLimit = new CgroupMemory(1000, -1, -1, -1);
        HostMemory host = new HostMemory(16384, 4096);

        assertThat(MemoryReadings.limitOf(withLimit, host)).isEqualTo(4096);
        assertThat(MemoryReadings.limitOf(withoutLimit, host)).isEqualTo(16384);
        assertThat(MemoryReadings.limitOf(withoutLimit, null)).isEqualTo(-1);
        assertThat(MemoryReadings.limitOf(null, host)).isEqualTo(16384);
    }

    @Test
    void availability_whenThisIsNotLinux_thenNothingIsKnownWhateverTheFilesystemHolds() throws IOException {
        fakeCgroupV2();

        assertThat(MemoryReadings.availability("Mac OS X", root).isKnown()).isFalse();
        assertThat(MemoryReadings.availability("Windows 11", root).isKnown()).isFalse();
        assertThat(MemoryReadings.availability(null, root).isKnown()).isFalse();
    }

    /** A hardened container may not expose the cgroup files; without them there is nothing to read. */
    @Test
    void availability_whenLinuxHasNoCgroupFiles_thenNothingIsKnown() {
        assertThat(MemoryReadings.availability("Linux", root))
                .isEqualTo(new Availability(CgroupLayout.NONE, root));
    }

    @Test
    void availability_whenTheCgroupV2FilesAreThere_thenTheyAreRead() throws IOException {
        fakeCgroupV2();

        assertThat(MemoryReadings.availability("Linux", root))
                .isEqualTo(new Availability(CgroupLayout.V2, root));
    }

    @Test
    void availability_whenOnlyTheCgroupV1FilesAreThere_thenTheV1LayoutIsRead() throws IOException {
        fakeCgroupV1();

        assertThat(MemoryReadings.availability("Linux", root))
                .isEqualTo(new Availability(CgroupLayout.V1, root));
    }

    @Test
    void availability_whenBothLayoutsAreThere_thenV2Wins() throws IOException {
        fakeCgroupV1();
        fakeCgroupV2();

        assertThat(MemoryReadings.availability("Linux", root).layout()).isEqualTo(CgroupLayout.V2);
    }

    /** The source built on a fake root reads that root, so what it answers is what the files say. */
    @Test
    void sourceFor_thenItReadsTheFilesOfTheRootItWasGiven() throws IOException {
        fakeCgroupV2();
        MemoryReadings.MemorySource source = MemoryReadings.sourceFor(MemoryReadings.availability("Linux", root));

        assertThat(source.cgroup()).map(CgroupMemory::limitBytes).contains(4294967296L);
        assertThat(source.cgroup()).map(CgroupMemory::currentBytes).contains(2841575424L);
        assertThat(source.host()).isPresent();
    }

    /**
     * Writing to the high-water mark resets it, on the kernels that allow it - and where the write is refused
     * that is an answer rather than a fault, because the caller then compares against where the mark stood.
     */
    @Test
    void resetPeak_whenTheKernelTakesTheWrite_thenItSaysSo() throws IOException {
        fakeCgroupV2();
        Files.writeString(root.resolve("sys/fs/cgroup/memory.peak"), "2925895680\n", StandardCharsets.UTF_8);
        MemoryReadings.MemorySource source = MemoryReadings.sourceFor(MemoryReadings.availability("Linux", root));

        assertThat(source.resetPeak()).isTrue();
        assertThat(source.cgroup()).map(CgroupMemory::peakBytes).contains(0L);
    }

    @Test
    void resetPeak_whenThereIsNoHighWaterMarkToWrite_thenItIsRefusedRatherThanThrown() throws IOException {
        fakeCgroupV2();
        Files.createDirectory(root.resolve("sys/fs/cgroup/memory.peak"));
        MemoryReadings.MemorySource source = MemoryReadings.sourceFor(MemoryReadings.availability("Linux", root));

        assertThat(source.resetPeak()).isFalse();
    }

    @Test
    void hostMemory_thenThePlatformBeanAnswersOnThisJdk() {
        Optional<HostMemory> host = MemoryReadings.hostMemory();

        assertThat(host).isPresent();
        assertThat(host.get().totalBytes()).isPositive();
    }

    private void fakeCgroupV2() throws IOException {
        Path cgroup = Files.createDirectories(root.resolve("sys/fs/cgroup"));
        Files.writeString(cgroup.resolve("memory.current"), "2841575424\n", StandardCharsets.UTF_8);
        Files.writeString(cgroup.resolve("memory.max"), "4294967296\n", StandardCharsets.UTF_8);
    }

    private void fakeCgroupV1() throws IOException {
        Path cgroup = Files.createDirectories(root.resolve("sys/fs/cgroup/memory"));
        Files.writeString(cgroup.resolve("memory.usage_in_bytes"), "2841575424\n", StandardCharsets.UTF_8);
        Files.writeString(cgroup.resolve("memory.limit_in_bytes"), "4294967296\n", StandardCharsets.UTF_8);
    }
}
