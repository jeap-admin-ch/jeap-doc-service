package ch.admin.bit.jeap.doc.objectstorage;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBundleStorage;
import ch.admin.bit.jeap.doc.domain.port.SitePublicationStorage;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The object storage adapter: it stores the uploaded and the generated documentation in an S3 bucket, using the
 * {@link S3Client} of the jEAP object storage starter.
 */
@AutoConfiguration
@EnableConfigurationProperties(DocObjectStorageProperties.class)
public class DocObjectStorageConfiguration {

    @Bean
    DocumentationBundleStorage documentationBundleStorage(S3Client s3Client, DocObjectStorageProperties properties) {
        return new S3DocumentationBundleStorage(s3Client, properties);
    }

    @Bean
    SitePublicationStorage sitePublicationStorage(S3Client s3Client, DocObjectStorageProperties properties) {
        return new S3SitePublicationStorage(s3Client, properties);
    }

    @Bean
    DocSpoolDirectoryAvailabilityCheck docSpoolDirectoryAvailabilityCheck(DocObjectStorageProperties properties) {
        return new DocSpoolDirectoryAvailabilityCheck(properties);
    }

    @Bean
    DocStorageBucketAvailabilityCheck docStorageBucketAvailabilityCheck(S3Client s3Client,
                                                                        DocObjectStorageProperties properties) {
        return new DocStorageBucketAvailabilityCheck(s3Client, properties);
    }
}
