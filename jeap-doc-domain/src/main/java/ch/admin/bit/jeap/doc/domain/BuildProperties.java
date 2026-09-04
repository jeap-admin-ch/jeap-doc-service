package ch.admin.bit.jeap.doc.domain;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.Duration;

/**
 * How a documentation build runs, whichever site it is for.
 * <p>
 * Everything here is a property of the service rather than of a site: one Node, one workspace root, one timeout
 * per container. What differs between sites is {@link SiteProperties}.
 */
@Data
@ConfigurationProperties("jeap.doc.build")
public class BuildProperties {

    /**
     * The Node runtime the documentation is generated with. An absolute path in a container: the child process
     * gets an environment built from nothing, and its {@code PATH} is derived from this value.
     */
    private String nodeCommand = "node";

    /**
     * Where the dependencies of the site template are installed - written by the image build, read-only at run
     * time, and symlinked into every build workspace rather than copied.
     */
    private Path nodeModulesDirectory;

    /**
     * The directory a build works in. It should be on storage that belongs to this container alone and it does
     * not have to survive a restart; the default is the temporary directory of the JVM, which is right on a
     * developer machine and wrong in a container.
     */
    private Path workspaceDirectory;

    /**
     * Whether to keep the workspace of a build instead of deleting it. For reproducing a failure on a developer
     * machine, and for nothing else: it is a disk leak with a purpose.
     */
    private boolean keepWorkspace = false;

    /**
     * How often an instance looks whether a build has been asked for.
     */
    private Duration pollInterval = Duration.ofSeconds(30);

    /**
     * How long a build may take before it is given up on.
     */
    private Duration timeout = Duration.ofMinutes(15);

    /**
     * How long the lock of a site is leased for, and therefore <b>how long after an instance dies its lock
     * survives it</b>. It is deliberately far shorter than a build may take: the lock is extended while the
     * build runs, so this bounds the recovery rather than the build.
     * <p>
     * Nothing else has to change when a build gets slower. The one thing it costs is that an extension failing
     * while the build carries on lets another instance start a second build of the same site - wasteful, and
     * harmless, because each build publishes under its own identifier and the newest successful one wins.
     */
    private Duration lockLease = Duration.ofMinutes(2);

    /**
     * How long a stopping instance may spend giving up its build: destroying the site generator, recording the
     * build as aborted, releasing the lock and asking for the build again.
     * <p>
     * It is a hard bound rather than a target. Overrunning {@code spring.lifecycle.timeout-per-shutdown-phase}
     * would let the context destroy its beans - the connection pool among them - while this is still writing,
     * which is the one thing the shutdown handling exists to avoid.
     */
    private Duration shutdownTimeout = Duration.ofSeconds(15);

    /**
     * The heap the Node process may use.
     * <p>
     * It bounds the JavaScript side of a build and nothing else. The bundler is native code with an allocator
     * of its own, and on a large site that side is the larger one by far - see {@link #purgeNativeMemory}, and
     * the peak the container reached, which every published build reports.
     */
    private DataSize maxNodeMemory = DataSize.ofMegabytes(1024);

    /**
     * Whether the site generator's native allocator hands memory back to the operating system as soon as it is
     * freed, instead of keeping it mapped for the next allocation.
     * <p>
     * It is about the bundler. Rspack is native code built with mimalloc, and mimalloc keeps freed pages for a
     * while - fast, and exactly wrong here: the bundle phase of a large site is the peak of a build, and
     * everything after it - the static generation, the plugins that run at the end - then runs with the whole
     * bundler's memory still resident behind it. The container is sized for the sum, and it is the sum that a
     * task is killed at.
     * <p>
     * On by default, and it costs the syscalls of returning pages rather than reusing them. It bounds what a
     * build <b>holds</b>, never what it allocates: a build that genuinely needs the memory at once is not made
     * to fit by this. Turn it off if the {@code [PERF]} lines show the phases after the bundle getting slower
     * than the memory is worth.
     */
    private boolean purgeNativeMemory = true;

    /**
     * Whether the site generator reports how long each phase of a build took and what it did to the Node heap.
     * The lines are logged at {@code INFO} with the prefix {@code [PERF]}, and each reading is taken after a
     * full garbage collection, so that it says what a phase retains rather than what it happened to allocate.
     * On by default: it is what an operator needs when a build gets slow or large, and the collections cost a
     * big site seconds, not minutes.
     */
    private boolean perfLog = true;

    /**
     * Whether the site generator writes the pages of a site from a pool of worker threads.
     * <p>
     * It makes the static generation about twice as fast and is <b>off by default</b> all the same, because of
     * what it does to the memory of a build: each worker thread is a V8 isolate with a heap of its own, and
     * {@code --max-old-space-size} bounds an isolate rather than the process - so a pool of them may hold a
     * multiple of {@link #maxNodeMemory} in one process, in a container that also holds this JVM. An instance
     * whose container has the room buys the speed by switching it on; the number to decide it by is the peak
     * the container reached, which every published build reports.
     */
    private boolean ssgWorkerThreads = false;

    /**
     * How many published sites to keep per site. Keeping a few makes a failed generation something to compare
     * against; keeping many costs storage for builds nobody will serve again. At least two, which
     * {@code DocumentationBuildScheduling} refuses fewer of while the service starts.
     */
    private int retention = 3;

    /**
     * How long the record of a build is kept. It is the evidence of what was generated and when, so it outlives
     * the uploads.
     */
    private Duration historyRetention = Duration.ofDays(90);

    /**
     * When the record of old builds is removed, in the time zone of the service - at night, like the clean-up of
     * the uploads next door, and a few minutes apart from it.
     */
    private String historyCron = "0 45 2 * * *";
}
