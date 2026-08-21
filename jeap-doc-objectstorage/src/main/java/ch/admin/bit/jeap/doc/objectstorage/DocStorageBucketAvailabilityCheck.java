package ch.admin.bit.jeap.doc.objectstorage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

/**
 * Checks on startup that the configured bucket exists and can be accessed with the configured credentials.
 * <p>
 * A doc service that cannot reach its object storage cannot do its job, and a missing bucket or a wrong
 * credential is a configuration error of the instance. Failing at startup surfaces it in the deployment instead
 * of in the first upload.
 */
@Slf4j
@RequiredArgsConstructor
public class DocStorageBucketAvailabilityCheck implements InitializingBean {

    private final S3Client s3Client;
    private final DocObjectStorageProperties properties;

    @Override
    public void afterPropertiesSet() {
        String bucket = properties.getBucket();
        if (!StringUtils.hasText(bucket)) {
            throw new IllegalStateException("No object storage bucket configured, set 'jeap.doc.storage.bucket'.");
        }
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (SdkException e) {
            throw new IllegalStateException("The configured object storage bucket '%s' is not available: %s"
                    .formatted(bucket, e.getMessage()), e);
        }
        log.info("Object storage bucket '{}' is available.", bucket);
    }
}
