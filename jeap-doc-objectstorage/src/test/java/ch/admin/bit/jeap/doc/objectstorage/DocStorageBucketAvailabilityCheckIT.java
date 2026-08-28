package ch.admin.bit.jeap.doc.objectstorage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocStorageBucketAvailabilityCheckIT extends RustFsTestContainerBase {

    @Test
    void afterPropertiesSet_whenBucketExists_thenSucceeds() {
        assertThatCode(() -> checkFor(TEST_BUCKET_NAME).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void afterPropertiesSet_whenBucketDoesNotExist_thenFails() {
        assertThatThrownBy(() -> checkFor("no-such-bucket").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no-such-bucket");
    }

    @Test
    void afterPropertiesSet_whenBucketNotConfigured_thenFails() {
        assertThatThrownBy(() -> checkFor(null).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jeap.doc.storage.bucket");
    }

    /**
     * A thread pool of zero throws at the end of a build, once per poll, with a message naming neither the
     * property nor the service. Configuration errors fail the startup here.
     */
    @Test
    void afterPropertiesSet_whenNoFileMayBeWrittenAtATime_thenTheStartupFails() {
        DocObjectStorageProperties properties = new DocObjectStorageProperties();
        properties.setBucket(TEST_BUCKET_NAME);
        properties.setPublicationConcurrency(0);

        assertThatThrownBy(() -> new DocStorageBucketAvailabilityCheck(S3_CLIENT, properties).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jeap.doc.storage.publication-concurrency");
    }

    private static DocStorageBucketAvailabilityCheck checkFor(String bucket) {
        DocObjectStorageProperties properties = new DocObjectStorageProperties();
        properties.setBucket(bucket);
        return new DocStorageBucketAvailabilityCheck(S3_CLIENT, properties);
    }

}
