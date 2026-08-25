package ch.admin.bit.jeap.doc.objectstorage;

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
     * The directory the bundles are spooled to - the configured one, or the temporary directory of the JVM.
     */
    public Path spoolDirectoryOrDefault() {
        return spoolDirectory != null ? spoolDirectory : Path.of(System.getProperty("java.io.tmpdir"));
    }
}
