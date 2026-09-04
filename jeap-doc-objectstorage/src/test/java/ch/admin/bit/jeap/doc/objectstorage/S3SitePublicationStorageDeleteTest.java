package ch.admin.bit.jeap.doc.objectstorage;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a delete does with the objects the storage refuses to delete.
 * <p>
 * {@code DeleteObjects} is the one call in this adapter that reports a failure <b>in its answer</b>: it returns
 * {@code 200} with an error per key it would not delete, and throws only where the whole request failed. That is
 * not reproducible against RustFS, so it is stubbed here rather than in {@link S3SitePublicationStorageIT}.
 */
class S3SitePublicationStorageDeleteTest {

    private static final String PREFIX = "default/46";

    /**
     * Without reading the answer, a delete that removed almost nothing returns normally and logs the full count
     * as removed - and {@code DocumentationBuildRunner} then records the prefix as forgotten, so those objects
     * are never offered for deletion again and stay in the bucket until a lifecycle rule reaches them.
     */
    @Test
    void delete_whenTheStorageRefusesAKey_thenItIsNotReportedAsRemoved() {
        RecordingS3 s3 = new RecordingS3(List.of(S3Error.builder().key("sites/default/46/index.html")
                .code("AccessDenied").message("Access Denied").build()));

        assertThatThrownBy(() -> storageOf(s3).delete(PREFIX))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sites/default/46/index.html")
                .hasMessageContaining("AccessDenied");
    }

    @Test
    void delete_whenEveryKeyGoes_thenNothingIsThrown() {
        RecordingS3 s3 = new RecordingS3(List.of());

        storageOf(s3).delete(PREFIX);

        assertThat(s3.deleted).containsExactly("sites/default/46/index.html", "sites/default/46/assets/x.js");
    }

    private static S3SitePublicationStorage storageOf(S3Client s3) {
        DocObjectStorageProperties properties = new DocObjectStorageProperties();
        properties.setBucket("a-bucket");
        properties.setSitePrefix("sites");
        return new S3SitePublicationStorage(s3, properties);
    }

    /** An S3 that lists two objects of the site and answers the delete with whatever it was given. */
    private static final class RecordingS3 implements S3Client {

        private final List<S3Error> errors;
        private final List<String> deleted = new java.util.ArrayList<>();

        private RecordingS3(List<S3Error> errors) {
            this.errors = errors;
        }

        @Override
        public ListObjectsV2Iterable listObjectsV2Paginator(ListObjectsV2Request request) {
            return new ListObjectsV2Iterable(this, request);
        }

        @Override
        public ListObjectsV2Response listObjectsV2(ListObjectsV2Request request) {
            return ListObjectsV2Response.builder()
                    .contents(S3Object.builder().key("sites/default/46/index.html").build(),
                            S3Object.builder().key("sites/default/46/assets/x.js").build())
                    .isTruncated(false)
                    .build();
        }

        @Override
        public DeleteObjectsResponse deleteObjects(DeleteObjectsRequest request) {
            request.delete().objects().forEach(object -> deleted.add(object.key()));
            return DeleteObjectsResponse.builder().errors(errors).build();
        }

        @Override
        public String serviceName() {
            return "s3";
        }

        @Override
        public void close() {
            // Nothing is opened.
        }
    }
}
