package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.BuildProperties;
import ch.admin.bit.jeap.doc.domain.port.SiteBuildException;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Runs the real Node against small scripts of this test's own - Node is a precondition of this build, in the way
 * Docker is for the tests that need a database.
 */
class NodeProcessTest {

    @TempDir
    Path workingDirectory;

    /** What captureLog attached, so that the @AfterEach can take it off again. Null when none did. */
    private ListAppender<ILoggingEvent> attached;

    /** The level the logger had before a test turned it down - usually null, which means "inherited". */
    private Level levelBeforeTheTest;

    private BuildProperties properties;
    private NodeProcess node;

    @BeforeEach
    void setUp() {
        properties = new BuildProperties();
        node = new NodeProcess(properties);
    }

    @Test
    void run_whenTheScriptSucceeds_thenItReturns() throws IOException {
        script("ok.mjs", "process.stdout.write('done')");

        node.run(workingDirectory, "ok.mjs");
    }

    @Test
    void run_whenTheScriptFails_thenTheReasonCarriesTheExitCodeAndTheLastOutput() throws IOException {
        script("fail.mjs", """
                console.log('compiling');
                console.error('Error: something in the configuration is wrong');
                process.exit(2);
                """);

        assertThatThrownBy(() -> node.run(workingDirectory, "fail.mjs"))
                .isInstanceOf(SiteBuildException.class)
                .hasMessageContaining("exited with 2")
                .hasMessageContaining("something in the configuration is wrong");
    }

    @Test
    void run_whenTheScriptWritesMoreThanIsKept_thenTheLastLinesAreTheOnesReported() throws IOException {
        int written = NodeProcess.KEPT_LOG_LINES + 500;
        script("chatty.mjs", """
                for (let line = 0; line < %d; line++) {
                    console.log(`line ${line}`);
                }
                process.exit(1);
                """.formatted(written));

        assertThatThrownBy(() -> node.run(workingDirectory, "chatty.mjs"))
                .isInstanceOf(SiteBuildException.class)
                .hasMessageContaining("line " + (written - 1))
                .hasMessageNotContaining("line 0\n");
    }

    @Test
    void run_whenTheScriptHangs_thenItIsGivenUpOnAndKilled() throws IOException {
        properties.setTimeout(Duration.ofMillis(500));
        script("hang.mjs", "setInterval(() => {}, 1000);");

        assertThatThrownBy(() -> node.run(workingDirectory, "hang.mjs"))
                .isInstanceOf(SiteBuildException.class)
                .hasMessageContaining("did not finish within");
    }

    @Test
    void run_whenNodeIsNotWhereItIsConfigured_thenTheReasonSaysWhichPropertyToLookAt() throws IOException {
        properties.setNodeCommand("/nowhere/node");
        script("ok.mjs", "process.exit(0)");

        assertThatThrownBy(() -> node.run(workingDirectory, "ok.mjs"))
                .isInstanceOf(SiteBuildException.class)
                .hasMessageContaining("jeap.doc.build.node-command");
    }

    /**
     * The service's environment holds the credentials of the database and of the object storage. A documentation
     * build has no business seeing them, so its environment is built from nothing rather than inherited.
     */
    @Test
    void run_thenTheChildSeesNothingOfTheServicesOwnEnvironment() throws IOException {
        script("environment.mjs", """
                import {writeFileSync} from 'node:fs';
                writeFileSync('environment.txt', Object.keys(process.env).sort().join('\\n'));
                """);

        node.run(workingDirectory, "environment.mjs");

        String seen = Files.readString(workingDirectory.resolve("environment.txt"), StandardCharsets.UTF_8);
        assertThat(seen.lines()).containsExactlyInAnyOrder("PATH", "HOME", "CI", "NODE_OPTIONS",
                "DOCUSAURUS_PERF_LOGGER", "MIMALLOC_PURGE_DELAY", "MIMALLOC_ABANDONED_PAGE_PURGE");
    }

    /**
     * The bundler is native code, its memory is not the Node heap, and it holds what it has freed unless it is
     * told otherwise - which is the difference between the peak of a build and the size of a container.
     */
    @Test
    void run_thenTheNativeAllocatorOfTheChildGivesFreedMemoryBack() throws IOException {
        script("purge.mjs", """
                import {writeFileSync} from 'node:fs';
                writeFileSync('delay.txt', process.env.MIMALLOC_PURGE_DELAY ?? 'unset');
                writeFileSync('abandoned.txt', process.env.MIMALLOC_ABANDONED_PAGE_PURGE ?? 'unset');
                """);

        node.run(workingDirectory, "purge.mjs");

        assertThat(Files.readString(workingDirectory.resolve("delay.txt"))).isEqualTo("0");
        assertThat(Files.readString(workingDirectory.resolve("abandoned.txt"))).isEqualTo("1");
    }

    @Test
    void run_whenTheNativeMemoryPurgeIsOff_thenTheChildKnowsNothingOfIt() throws IOException {
        properties.setPurgeNativeMemory(false);
        script("purge.mjs", """
                import {writeFileSync} from 'node:fs';
                writeFileSync('delay.txt', process.env.MIMALLOC_PURGE_DELAY ?? 'unset');
                writeFileSync('abandoned.txt', process.env.MIMALLOC_ABANDONED_PAGE_PURGE ?? 'unset');
                """);

        node.run(workingDirectory, "purge.mjs");

        assertThat(Files.readString(workingDirectory.resolve("delay.txt"))).isEqualTo("unset");
        assertThat(Files.readString(workingDirectory.resolve("abandoned.txt"))).isEqualTo("unset");
    }

    @Test
    void run_thenTheHeapOfTheChildIsCappedAndTheCollectorExposedForThePerfLog() throws IOException {
        script("options.mjs", """
                import {writeFileSync} from 'node:fs';
                writeFileSync('options.txt', process.env.NODE_OPTIONS ?? '');
                writeFileSync('perf.txt', process.env.DOCUSAURUS_PERF_LOGGER ?? '');
                writeFileSync('gc.txt', typeof globalThis.gc);
                """);

        node.run(workingDirectory, "options.mjs");

        assertThat(Files.readString(workingDirectory.resolve("options.txt")))
                .isEqualTo("--max-old-space-size=1024 --expose-gc");
        assertThat(Files.readString(workingDirectory.resolve("perf.txt"))).isEqualTo("true");
        assertThat(Files.readString(workingDirectory.resolve("gc.txt"))).isEqualTo("function");
    }

    @Test
    void run_whenThePerfLogIsOff_thenTheChildKnowsNothingOfIt() throws IOException {
        properties.setPerfLog(false);
        script("options.mjs", """
                import {writeFileSync} from 'node:fs';
                writeFileSync('options.txt', process.env.NODE_OPTIONS ?? '');
                writeFileSync('perf.txt', process.env.DOCUSAURUS_PERF_LOGGER ?? 'unset');
                """);

        node.run(workingDirectory, "options.mjs");

        assertThat(Files.readString(workingDirectory.resolve("options.txt")))
                .isEqualTo("--max-old-space-size=1024");
        assertThat(Files.readString(workingDirectory.resolve("perf.txt"))).isEqualTo("unset");
    }

    /**
     * The performance log is the one part of the generator's output that is wanted while a build succeeds, so
     * it is logged at INFO; everything else stays at DEBUG and is kept for the reason of a failed build.
     */
    @Test
    void run_thenThePerfLogLinesAreLoggedAtInfoAndTheRestAtDebug() throws IOException {
        ListAppender<ILoggingEvent> logged = captureLog();
        script("perf.mjs", """
                console.log('[PERF] Load site - 12.00 ms - (Heap 40mb -> 41mb / Total 60mb)');
                console.log('[INFO] Compiling Client');
                """);

        node.run(workingDirectory, "perf.mjs");

        assertThat(logged.list)
                .filteredOn(event -> event.getFormattedMessage().startsWith("[site generator]"))
                .extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
                .containsExactly(
                        tuple(Level.INFO,
                                "[site generator] [PERF] Load site - 12.00 ms - (Heap 40mb -> 41mb / Total 60mb)"),
                        tuple(Level.DEBUG,
                                "[site generator] [INFO] Compiling Client"));
    }

    @Test
    void run_whenThePerfLogIsOff_thenAPerfLineIsOrdinaryOutput() throws IOException {
        properties.setPerfLog(false);
        ListAppender<ILoggingEvent> logged = captureLog();
        script("perf.mjs", "console.log('[PERF] Load site - 12.00 ms')");

        node.run(workingDirectory, "perf.mjs");

        assertThat(logged.list)
                .filteredOn(event -> event.getFormattedMessage().startsWith("[site generator]"))
                .extracting(ILoggingEvent::getLevel)
                .containsExactly(Level.DEBUG);
    }

    /**
     * Attaches an appender and turns the logger down to DEBUG, both of which are undone again in
     * {@link #restoreTheLogger()}. A logger is global: left attached, the appenders accumulate over the class,
     * and the level stays turned down for every test that runs after these in the same JVM.
     */
    private ListAppender<ILoggingEvent> captureLog() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) getLogger(NodeProcess.class);
        levelBeforeTheTest = logger.getLevel();
        attached = appender;
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);
        return appender;
    }

    @AfterEach
    void restoreTheLogger() {
        if (attached == null) {
            return;
        }
        Logger logger = (Logger) getLogger(NodeProcess.class);
        logger.detachAppender(attached);
        logger.setLevel(levelBeforeTheTest);
        attached.stop();
        attached = null;
    }

    /**
     * What a stopping instance does to a build in flight: the generator is destroyed from another thread, and
     * the thread that was waiting for it comes back within a second rather than at the timeout.
     */
    @Test
    void abort_whenAScriptIsRunning_thenItEndsAtOnceAndSaysWhy() throws Exception {
        properties.setTimeout(Duration.ofMinutes(15));
        // The script says when it is up, so that the abort lands on a process that is really running rather
        // than after a guessed delay.
        script("hang.mjs", """
                import {writeFileSync} from 'node:fs';
                writeFileSync('started.txt', 'yes');
                setInterval(() => {}, 1000);
                """);

        ExecutorService running = Executors.newSingleThreadExecutor();
        try {
            Future<?> build = running.submit(() -> node.run(workingDirectory, "hang.mjs"));
            awaitUntil(Duration.ofSeconds(20), () -> Files.exists(workingDirectory.resolve("started.txt")));

            long startedAt = System.nanoTime();
            node.abort();

            assertThatThrownBy(build::get)
                    .cause()
                    .isInstanceOf(SiteBuildException.class)
                    .hasMessageContaining("this instance is stopping");
            assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(10));
            assertThat(node.isAborted()).isTrue();
        } finally {
            running.shutdownNow();
        }
    }

    /**
     * The abort landing before the process is even started must still leave nothing running: an instance that
     * was told to stop cannot be the one whose child outlives it.
     */
    @Test
    void abort_whenNothingIsRunningYet_thenTheNextScriptIsKilledStraightAway() throws IOException {
        properties.setTimeout(Duration.ofMinutes(15));
        script("hang.mjs", "setInterval(() => {}, 1000);");

        node.abort();

        assertThatThrownBy(() -> node.run(workingDirectory, "hang.mjs"))
                .isInstanceOf(SiteBuildException.class)
                .hasMessageContaining("this instance is stopping");
    }

    @Test
    void abort_whenNothingIsRunning_thenItIsHarmless() {
        node.abort();

        assertThat(node.isAborted()).isTrue();
    }

    /**
     * A bare command name must not put this service's working directory at the front of the child's PATH: the
     * generator spawns helpers that are looked up on it, and the working directory is not a place to find them.
     */
    @Test
    void run_whenTheNodeCommandIsABareName_thenTheChildsPathIsOnlyTheSystemDirectories() throws IOException {
        properties.setNodeCommand("node");
        script("path.mjs", """
                import {writeFileSync} from 'node:fs';
                writeFileSync('path.txt', process.env.PATH);
                """);

        node.run(workingDirectory, "path.mjs");

        assertThat(Files.readString(workingDirectory.resolve("path.txt"), StandardCharsets.UTF_8))
                .isEqualTo("/usr/bin:/bin");
    }

    /**
     * A command that names a directory puts that directory on the PATH, which is the point of it in a
     * container where Node is not on the system path at all.
     */
    @Test
    void run_whenTheNodeCommandNamesADirectory_thenThatDirectoryIsOnTheChildsPath() throws IOException {
        String nodeCommand = nodeOnThePath();
        properties.setNodeCommand(nodeCommand);
        script("path.mjs", """
                import {writeFileSync} from 'node:fs';
                writeFileSync('path.txt', process.env.PATH);
                """);

        node.run(workingDirectory, "path.mjs");

        assertThat(Files.readString(workingDirectory.resolve("path.txt"), StandardCharsets.UTF_8))
                .startsWith(Path.of(nodeCommand).toAbsolutePath().getParent().toString() + ":")
                .endsWith("/usr/bin:/bin");
    }

    /**
     * The thread draining the child's output is a daemon: a helper the generator spawned can inherit the pipe
     * and keep it open after the process tree is destroyed, and a non-daemon thread parked in that read would
     * stop the JVM exiting at all.
     */
    @Test
    void run_thenTheThreadReadingTheOutputIsADaemon() throws Exception {
        properties.setTimeout(Duration.ofMinutes(15));
        script("hang.mjs", """
                import {writeFileSync} from 'node:fs';
                writeFileSync('started.txt', 'yes');
                setInterval(() => {}, 1000);
                """);

        ExecutorService running = Executors.newSingleThreadExecutor();
        try {
            running.submit(() -> node.run(workingDirectory, "hang.mjs"));
            awaitUntil(Duration.ofSeconds(20), () -> Files.exists(workingDirectory.resolve("started.txt")));

            assertThat(Thread.getAllStackTraces().keySet())
                    .filteredOn(thread -> "site-generator-output".equals(thread.getName()))
                    .isNotEmpty()
                    .allMatch(Thread::isDaemon);
        } finally {
            node.abort();
            running.shutdownNow();
        }
    }

    /**
     * Waits for something to become true, rather than for a length of time - a test that sleeps for a guessed
     * duration is either slow or flaky, and usually both.
     */
    private static void awaitUntil(Duration atMost, BooleanSupplier condition) {
        long deadline = System.nanoTime() + atMost.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
        }
        throw new AssertionError("The condition was not met within " + atMost);
    }

    /** Where the Node this build runs on actually lives, so the test can configure it by an absolute path. */
    private static String nodeOnThePath() {
        return java.util.Arrays.stream(System.getenv("PATH").split(java.io.File.pathSeparator))
                .map(directory -> Path.of(directory, "node"))
                .filter(Files::isExecutable)
                .findFirst()
                .map(Path::toString)
                .orElseThrow(() -> new IllegalStateException("Node is a precondition of this build."));
    }

    private void script(String name, String content) throws IOException {
        Files.writeString(workingDirectory.resolve(name), content, StandardCharsets.UTF_8);
    }
}
