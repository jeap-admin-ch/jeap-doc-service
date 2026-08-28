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
     */
    private DataSize maxNodeMemory = DataSize.ofMegabytes(1024);

    /**
     * How many published sites to keep per site. Keeping a few makes a failed generation something to compare
     * against; keeping many costs storage for builds nobody will serve again.
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
