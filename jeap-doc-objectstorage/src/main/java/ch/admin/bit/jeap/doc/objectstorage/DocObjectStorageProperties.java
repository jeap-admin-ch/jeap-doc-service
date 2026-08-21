package ch.admin.bit.jeap.doc.objectstorage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
}
