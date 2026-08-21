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

    private static DocStorageBucketAvailabilityCheck checkFor(String bucket) {
        DocObjectStorageProperties properties = new DocObjectStorageProperties();
        properties.setBucket(bucket);
        return new DocStorageBucketAvailabilityCheck(S3_CLIENT, properties);
    }
}
