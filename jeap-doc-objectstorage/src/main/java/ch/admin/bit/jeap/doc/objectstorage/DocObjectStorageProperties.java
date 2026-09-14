package ch.admin.bit.jeap.doc.objectstorage;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Configuration of the object storage the documentation is stored in. The connection to the object storage
 * itself is configured with the {@code jeap.s3.client.*} properties of the jEAP object storage starter.
 */
@Data
@ConfigurationProperties("jeap.doc.storage")
public class DocObjectStorageProperties {

    /**
     * Name of the S3 bucket holding the documentation. The service does not start when the bucket is not
     * configured or not available.
     */
    private String bucket;

    /**
     * Prefix the bundles of the uploads are stored under, which is what keeps the incoming documentation
     * separate from the documentation the generator writes. The kind of upload, its identifier and the attempt
     * that wrote it follow, e.g. {@code uploads/docs/42/1/bundle.zip}.
     */
    private String uploadPrefix = "uploads";

    /**
     * Prefix the generated sites are published under, which is what keeps them apart from the documentation that
     * was uploaded. The site, the build that produced it and the file follow, e.g.
     * {@code sites/default/42/index.html}.
     */
    private String sitePrefix = "sites";

    /**
     * Prefix the documentation sets currently being published are kept under, e.g.
     * {@code current/docs/default/SYSTEM/orders/…/7/bundle.zip}.
     * <p>
     * <b>Nothing under it may be expired by age.</b> An upload's bundle is a staging copy and the bucket
     * expires it; a set is the only copy there is, and a component that publishes once and stays stable for a
     * year is the normal case. See {@code docs/operating-the-bucket.md}.
     */
    private String currentPrefix = "current";

    /**
     * Directory the uploaded bundles are spooled to while they are transferred to the object storage. Without it
     * the temporary directory of the JVM is used.
     * <p>
     * The spooling is what keeps a bundle out of the memory of the service, so the directory should be on a disk:
     * a {@code /tmp} that is a memory-backed tmpfs - as containers with a read-only root filesystem often have -
     * would defeat it. It needs room for as many bundles of {@code jeap.doc.upload.max-size} as are uploaded at
     * the same time.
     */
    private Path spoolDirectory;

    /**
     * How many files of a generated site are written into the bucket at a time.
     * <p>
     * A site is thousands of small files, so publishing it is bound by round trips: one at a time takes about
     * as long as generating it did. Above the connection pool of the S3 client this buys nothing - it only
     * moves the queue from the network to the pool.
     */
    private int publicationConcurrency = 16;

    /**
     * How many files of an uploaded microsite are written into the bucket at a time.
     * <p>
     * The same reasoning as {@link #publicationConcurrency}, and a knob of its own because this one runs
     * while a pipeline waits for its upload to be answered rather than in a build of its own.
     */
    private int micrositeConcurrency = 16;

    /**
     * The directory the bundles are spooled to - the configured one, or the temporary directory of the JVM.
     */
    public Path spoolDirectoryOrDefault() {
        return spoolDirectory != null ? spoolDirectory : Path.of(System.getProperty("java.io.tmpdir"));
    }

    /** A configuration error stops the deployment rather than the first upload of a microsite. */
    @PostConstruct
    void check() {
        if (micrositeConcurrency < 1) {
            throw new IllegalStateException("jeap.doc.storage.microsite-concurrency is "
                                            + micrositeConcurrency + ". A microsite is written by at least "
                                            + "one thread, or it is not written at all.");
        }
        if (publicationConcurrency < 1) {
            throw new IllegalStateException("jeap.doc.storage.publication-concurrency is "
                                            + publicationConcurrency + ". A site is published by at least "
                                            + "one thread, or it is not published at all.");
        }
    }
}
