package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.BuildProperties;
import ch.admin.bit.jeap.doc.domain.port.SiteBuildException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs Node as a child process of the service, under close control.
 * <p>
 * Three things about that control are deliberate. The child's <b>environment is built from nothing</b> rather
 * than inherited: this service's own environment holds database and object storage credentials, and a
 * documentation build has no business seeing them. Its <b>output is read continuously</b>, because a child whose
 * pipe fills up blocks for ever. And it has a <b>hard timeout</b>, after which the process tree is destroyed - a
 * build that hangs would otherwise hold its site's lock until the lease expires and then do it again.
 * <p>
 * There is a second way to end it: {@link #abort()} destroys whatever is running now, so that an instance being
 * stopped gives its build up in a second rather than at the end of the timeout. Aborting this way rather than
 * interrupting the waiting thread is deliberate - a destroyed child makes {@code waitFor} return normally, and
 * the thread that was waiting walks out through its ordinary failure path, uninterrupted, and can still write
 * what it has to write to the database.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NodeProcess {

    /**
     * How many lines of the generator's output are kept for the failure reason of a failed build. Generous,
     * because the line that explains a Docusaurus failure is often a long way above the one that reports it.
     */
    static final int KEPT_LOG_LINES = 1024;

    private final BuildProperties properties;

    /**
     * What is running right now, so that it can be destroyed from another thread. One reference is enough: an
     * instance runs one build at a time - the runner's task is a fixed delay and is never re-entered - and the
     * startup check has finished before any build starts.
     */
    private final AtomicReference<Process> current = new AtomicReference<>();

    /** Set by {@link #abort()}, so that the destroyed process is reported as given up on rather than as failed. */
    private final AtomicBoolean aborted = new AtomicBoolean();

    /**
     * Runs the given script with the configured Node, in the given directory, and returns when it has finished
     * successfully.
     *
     * @throws SiteBuildException when it fails, times out, or cannot be started - with what an operator needs
     */
    public void run(Path workingDirectory, String script, String... arguments) {
        runAndCapture(workingDirectory, script, arguments);
    }

    /**
     * The same, reporting what the script wrote - up to {@link #KEPT_LOG_LINES} lines of it.
     */
    public String runAndCapture(Path workingDirectory, String script, String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(properties.getNodeCommand());
        command.add(script);
        command.addAll(List.of(arguments));

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true);
        scrubEnvironment(builder.environment());

        Process process = start(builder, command);
        current.set(process);
        if (aborted.get()) {
            // The abort landed between starting the process and publishing it here, so it destroyed nothing.
            // Without this the child would outlive the instance that was told to stop.
            destroy(process);
        }
        OutputTail tail = new OutputTail();
        // The output is drained on a thread of its own, and that is not a nicety: a child that hangs never
        // closes its output, so reading it to the end on this thread would block for ever and the timeout below
        // would never be reached. Draining it is not optional either - a child whose pipe fills up stops.
        // A platform thread, not a virtual one. The work here is a blocking read on a pipe, which is exactly
        // the case virtual threads do not help with: the JDK cannot unmount a carrier thread for file and pipe
        // I/O, so a virtual thread reading a process pipe pins its carrier for the whole build. One thread per
        // build, one build per instance - a platform thread is the cheaper and the honest choice.
        // A daemon thread: a helper the generator spawned can inherit the pipe and keep it open after the
        // process tree is destroyed, and this thread is only ever waited on for a couple of seconds. A
        // non-daemon one parked in that read would stop the JVM exiting at all.
        Thread reader = Thread.ofPlatform().daemon(true).name("site-generator-output")
                .start(() -> readOutput(process, tail));
        try {
            if (!process.waitFor(properties.getTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                destroy(process);
                throw new SiteBuildException(("The site generator did not finish within %s. Its last output was:%n%s")
                        .formatted(properties.getTimeout(), lastOutput(tail, reader)));
            }
            if (aborted.get()) {
                // Checked before the exit code, because an aborted generator exits non-zero for the ordinary
                // reason that it was killed - and what an operator reads on the build should say who killed it.
                throw new SiteBuildException("The site generator was given up on: this instance is stopping.");
            }
            if (process.exitValue() != 0) {
                throw new SiteBuildException(("The site generator exited with %d. Its last output was:%n%s")
                        .formatted(process.exitValue(), lastOutput(tail, reader)));
            }
            return lastOutput(tail, reader);
        } catch (InterruptedException e) {
            destroy(process);
            Thread.currentThread().interrupt();
            throw new SiteBuildException("The site generator was interrupted.", e);
        } finally {
            current.compareAndSet(process, null);
        }
    }

    /**
     * Destroys whatever the site generator is running, so that a build in flight ends now rather than at its
     * timeout. Does nothing on an instance that is not building.
     * <p>
     * It is one-way: once aborted, this instance runs no further generator. That is what it is for - it is
     * called while the service is stopping, and a stopping service starting a build would be worse than the
     * build it just gave up on.
     */
    public void abort() {
        aborted.set(true);
        Process running = current.getAndSet(null);
        if (running != null) {
            log.info("Giving up on the site generator: this instance is stopping.");
            destroy(running);
        }
    }

    /** Whether {@link #abort()} was called, so that nothing else starts a generator afterwards. */
    boolean isAborted() {
        return aborted.get();
    }

    /**
     * The last lines the generator wrote, once the reader has caught up with the process ending. It is given a
     * moment rather than waited on for ever: the reason of a failed build is worth a second, not a hang.
     */
    private static String lastOutput(OutputTail tail, Thread reader) {
        try {
            reader.join(java.time.Duration.ofSeconds(2));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return tail.text();
    }

    /**
     * What the child is allowed to see. Everything else - the AWS credentials, the datasource password, whatever
     * the platform put into the environment - stays with the service.
     */
    private void scrubEnvironment(Map<String, String> environment) {
        String nodeCommand = properties.getNodeCommand();
        environment.clear();
        // Node itself is started by the configured command, but it spawns helpers of its own, and they are
        // looked up on the PATH - which is why the directory that command lives in is on it. Only when the
        // command names a directory at all: resolving a bare `node` would put the working directory of this
        // service at the front of the child's PATH, ahead of /usr/bin, which is not a thing to do by default.
        String nodeDirectory = directoryOf(nodeCommand);
        environment.put("PATH", (nodeDirectory == null ? "" : nodeDirectory + ":") + "/usr/bin:/bin");
        environment.put("HOME", System.getProperty("java.io.tmpdir"));
        environment.put("CI", "true");
        environment.put("NODE_OPTIONS", "--max-old-space-size=" + properties.getMaxNodeMemory().toMegabytes());
    }

    /**
     * The directory the configured Node command lives in, or null when it is a bare name to be looked up on the
     * PATH the container already has.
     */
    private static String directoryOf(String nodeCommand) {
        if (!nodeCommand.contains("/")) {
            return null;
        }
        Path parent = Path.of(nodeCommand).toAbsolutePath().getParent();
        return parent == null ? null : parent.toString();
    }

    private static Process start(ProcessBuilder builder, List<String> command) {
        try {
            return builder.start();
        } catch (IOException e) {
            throw new SiteBuildException(("The site generator could not be started (%s). Check "
                                          + "jeap.doc.build.node-command.").formatted(String.join(" ", command)), e);
        }
    }

    private static void readOutput(Process process, OutputTail tail) {
        try (BufferedReader output = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = output.readLine()) != null) {
                log.debug("[site generator] {}", line);
                tail.add(line);
            }
        } catch (IOException e) {
            // The stream ends when the process is destroyed, which is a normal way for a build to be given up
            // on; what went wrong is already being reported by the thread that destroyed it.
            log.debug("The output of the site generator ended.", e);
        }
    }

    /**
     * Kills the process and everything it started: the site generator runs helpers of its own, and a build that
     * is given up on must not leave them behind holding memory.
     */
    private static void destroy(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /**
     * The last lines the generator wrote, kept for the failure reason of a failed build.
     * <p>
     * It is written by the thread draining the child's output and read by the thread waiting for the child, so
     * it carries its own lock rather than being guarded from outside - which is what keeps the two uses of it
     * from having to agree on a monitor.
     */
    private static final class OutputTail {

        private final Deque<String> lines = new ArrayDeque<>(KEPT_LOG_LINES);

        synchronized void add(String line) {
            if (lines.size() == KEPT_LOG_LINES) {
                lines.removeFirst();
            }
            lines.addLast(line);
        }

        synchronized String text() {
            return String.join("\n", lines);
        }
    }
}
