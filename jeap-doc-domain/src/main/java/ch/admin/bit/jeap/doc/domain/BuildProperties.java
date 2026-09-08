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
     * How often an <b>idle</b> instance looks whether a build has been asked for.
     * <p>
     * It is not what decides how fast a queue of parts drains: a pass builds what is owed and reads again after
     * every build, so the poll interval only bounds how long an instance that has nothing to do takes to notice
     * that it has. Shortening it buys latency on the instance that did not receive the trigger, and queries.
     */
    private Duration pollInterval = Duration.ofSeconds(30);

    /**
     * Whether a trigger starts a build pass at once, instead of leaving it to the next poll.
     * <p>
     * On by default: the instance that took an upload knows there is work, and waiting a poll interval to look
     * is latency for nothing. It is <b>advisory either way</b> - a wake-up that is lost costs nothing, because
     * the request stands and the next poll serves it - so switching it off only makes a build wait, and is what
     * the tests of this service run with so that a case asserts what it set off itself.
     */
    private boolean pickUpOnTrigger = true;

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
     * the {@code jeap.doc.container.memory.used} gauge, which is where the peak of a container is read.
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
     * How many parts of the documentation one instance builds at a time.
     * <p>
     * A site is published as one build per part, and a full round of a large landscape is a great many of them:
     * built one after another they cost the fixed ten seconds of a Docusaurus start each, and the cores of the
     * container sit idle in between. Built at once they do not - at the price of holding several builds' memory
     * together, which is the number that decides whether a build survives at all.
     * <p>
     * This is how many run <b>at once</b>, and not how many a pass builds: a pass fills a slot again as soon as
     * the build in it is done, and goes on until nothing is owed. Even at one, the parts are built one after
     * another within the same pass.
     * <p>
     * So it is a memory decision, not a parallelism one. Three is what a container sized for one large build
     * has room for when the parts are systems; raise it only against the memory a build is measured to hold -
     * {@code jeap_doc_container_memory_used_bytes} - and never past the cores.
     */
    private int maxConcurrentParts = 3;

    /**
     * Whether the site generator writes the pages of a site from a pool of worker threads.
     * <p>
     * It makes the static generation about twice as fast and is <b>off by default</b> all the same, because of
     * what it does to the memory of a build: each worker thread is a V8 isolate with a heap of its own, and
     * {@code --max-old-space-size} bounds an isolate rather than the process - so a pool of them may hold a
     * multiple of {@link #maxNodeMemory} in one process, in a container that also holds this JVM. An instance
     * whose container has the room buys the speed by switching it on; the number to decide it by is the
     * {@code jeap.doc.container.memory.used} gauge over a range of builds.
     */
    private boolean ssgWorkerThreads = false;

    /**
     * How many pages the site generator hands one static-generation worker at a time.
     * <p>
     * Read only when {@link #ssgWorkerThreads} is on: it is the pooled executor that chunks the pages, and
     * nothing else looks at the number.
     * <p>
     * <b>Deliberately not the generator's own default of ten.</b> At ten the pool spends most of its time
     * waiting on the one thread that hands the chunks out and collects their results - measured at four
     * threads, a worker is busy 66% of the time, and at eight threads 40% - and a task of ten pages also caps
     * how many pages one worker can have in flight. At a hundred, the static generation of a large part is
     * less than half of what it was, and the whole publication about a fifth faster.
     * <p>
     * Above a hundred the tasks start to run out before the threads do: a part of a few hundred pages then has
     * fewer chunks than there are workers, and the pool degenerates towards serial for the small parts of a
     * site. <b>At least 1</b> - {@code DocumentationBuildScheduling} refuses less while the service starts,
     * because the generator does not check the value and renders no page at all for a zero.
     */
    private int ssgTaskSize = 100;

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

    /**
     * How long a part the site no longer has keeps what it published, before the nightly clean-up removes it.
     * <p>
     * A system leaves the architecture model and its part stops being produced - so nothing asks for it, nothing
     * rebuilds it, and its objects and its pages would otherwise stay for ever. Long, and deliberately: a
     * decommissioned system's documentation is worth a quarter of a year, and a landscape that goes briefly
     * wrong must not take a live system's site with it.
     */
    private Duration departedPartRetention = Duration.ofDays(90);

    /**
     * When to ask for the sites nothing else publishes, in the time zone of the service. Every four hours
     * during the working day by default; {@code "-"} switches it off.
     * <p>
     * A site whose environments have no architecture repository is asked for by no import, so the only other
     * triggers it has are an upload and an operator. The content digest covers the service version, so such a
     * site would keep serving what the release before last generated. Asking for every part of it is nearly
     * free: a part whose content has not moved is not generated.
     */
    private String reconcileCron = "0 15 6-18/4 * * *";
}
