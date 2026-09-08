package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.BuildLogContext;
import ch.admin.bit.jeap.doc.domain.BuildProperties;
import ch.admin.bit.jeap.doc.domain.PartKey;
import ch.admin.bit.jeap.doc.domain.port.SiteBuildException;
import ch.admin.bit.jeap.doc.domain.port.SiteBuildTimeoutException;
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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

        // The type, not only the message: it is what tells the runner to count this apart from a build that
        // broke, and a message match would go on passing after somebody threw the plain exception again.
        assertThatThrownBy(() -> node.run(workingDirectory, "hang.mjs"))
                .isInstanceOf(SiteBuildTimeoutException.class)
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
                "DOCUSAURUS_PERF_LOGGER", "DOCUSAURUS_SSG_WORKER_THREAD_TASK_SIZE", "MIMALLOC_PURGE_DELAY",
                "MIMALLOC_ABANDONED_PAGE_PURGE");
    }

    /**
     * The pages one static-generation worker is handed at a time. The generator's own default is ten, at which
     * the pool spends most of its time waiting for the thread that hands the chunks out.
     */
    @Test
    void run_thenTheChildIsToldHowManyPagesAStaticGenerationTaskCarries() throws IOException {
        properties.setSsgTaskSize(250);
        script("tasks.mjs", """
                import {writeFileSync} from 'node:fs';
                writeFileSync('tasks.txt', process.env.DOCUSAURUS_SSG_WORKER_THREAD_TASK_SIZE ?? 'unset');
                """);

        node.run(workingDirectory, "tasks.mjs");

        assertThat(Files.readString(workingDirectory.resolve("tasks.txt"))).isEqualTo("250");
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
     * <b>Every line of the generator's output is DEBUG</b>, the performance lines included.
     * <p>
     * A build writes hundreds of lines and a publication is dozens of builds, so at INFO the generator's own
     * output is most of what an instance logs - and none of it answers a question an operator asks. What the
     * perf log is for is a build that grows or slows down, which is a debug session rather than a running
     * concern; what a failure was is in the tail, which is kept whatever the level.
     */
    @Test
    void run_thenEveryLineOfTheGeneratorsOutputIsLoggedAtDebug() throws IOException {
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
                        tuple(Level.DEBUG,
                                "[site generator] [PERF] Load site - 12.00 ms - (Heap 40mb -> 41mb / Total 60mb)"),
                        tuple(Level.DEBUG,
                                "[site generator] [INFO] Compiling Client"));
    }

    /**
     * <b>Every line of the generator's output names the build it came from.</b>
     * <p>
     * The pump runs on a thread of its own, and the MDC is thread-local: a thread started while a build runs
     * inherits none of it. So an instance building a dozen parts at once wrote thousands of lines under one
     * thread name with nothing to tell them apart, and attributing a performance trace to a build meant adding
     * the trace up and matching the total against a build row. Asserted on the logging event rather than on
     * the MDC of this thread, because what has to carry the fields is the line.
     */
    @Test
    void run_thenEveryLineOfTheOutputCarriesTheSiteThePartAndTheBuild() throws IOException {
        ListAppender<ILoggingEvent> logged = captureLog();
        script("chatter.mjs", """
                console.log('[PERF] Load site - 12.00 ms');
                console.log('[INFO] Compiling Client');
                """);

        try (BuildLogContext ignored = BuildLogContext.of(PartKey.of("default", "system-orders"))) {
            BuildLogContext.buildIs(4711L);
            node.run(workingDirectory, "chatter.mjs");
        }

        assertThat(logged.list)
                .filteredOn(event -> event.getFormattedMessage().startsWith("[site generator]"))
                .isNotEmpty()
                .allSatisfy(event -> assertThat(event.getMDCPropertyMap())
                        .containsEntry(BuildLogContext.SITE, "default")
                        .containsEntry(BuildLogContext.PART, "system-orders")
                        .containsEntry(BuildLogContext.BUILD_ID, "4711"));
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

    /**
     * A stop has to end <b>every</b> build in flight. An instance builds up to
     * {@code jeap.doc.build.max-concurrent-parts} parts at once, and a child left running would outlive the
     * instance that was told to stop, holding a container's worth of memory.
     */
    @Test
    void abort_whenSeveralScriptsAreRunning_thenAllOfThemEnd() throws Exception {
        properties.setTimeout(Duration.ofMinutes(15));
        ListAppender<ILoggingEvent> logged = captureLog();
        Path one = hangingScriptIn("one");
        Path other = hangingScriptIn("two");

        ExecutorService running = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = running.submit(() -> node.run(one, "hang.mjs"));
            Future<?> second = running.submit(() -> node.run(other, "hang.mjs"));
            awaitUntil(Duration.ofSeconds(20), () -> Files.exists(one.resolve("started.txt"))
                                                     && Files.exists(other.resolve("started.txt")));

            node.abort();

            // With a timeout, because the failure this guards against is a process that was not destroyed -
            // and its thread would then wait for the whole build timeout rather than fail.
            for (Future<?> build : List.of(first, second)) {
                assertThatThrownBy(() -> build.get(10, TimeUnit.SECONDS))
                        .cause()
                        .isInstanceOf(SiteBuildException.class)
                        .hasMessageContaining("this instance is stopping");
            }
            assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage)
                    .describedAs("the line an operator reads says how many runs were given up on")
                    .contains("Giving up on 2 site generator run(s): this instance is stopping.");
        } finally {
            running.shutdownNow();
        }
    }

    /** A hanging script in a directory of its own, which says when it is up. */
    private Path hangingScriptIn(String directory) throws IOException {
        Path working = Files.createDirectories(workingDirectory.resolve(directory));
        Files.writeString(working.resolve("hang.mjs"), """
                import {writeFileSync} from 'node:fs';
                writeFileSync('started.txt', 'yes');
                setInterval(() => {}, 1000);
                """, StandardCharsets.UTF_8);
        return working;
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
