package ch.admin.bit.jeap.doc.objectstorage;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.net.URI;

/**
 * Runs the object storage tests against RustFS, the S3-compatible storage jEAP uses for local development and
 * tests.
 */
public abstract class RustFsTestContainerBase {

    protected static final String TEST_BUCKET_NAME = "jeap-doc-test";

    private static final String RUSTFS_IMAGE = "rustfs/rustfs:1.0.0-beta.10";
    private static final int RUSTFS_PORT = 9000;
    private static final String RUSTFS_ACCESS_KEY = "dev";
    private static final String RUSTFS_SECRET_KEY = "devsecret";
    private static final String RUSTFS_REGION = "aws-global";

    protected static final GenericContainer<?> RUST_FS = createRustFsContainer();
    protected static final S3Client S3_CLIENT;

    static {
        RUST_FS.start();
        S3_CLIENT = createS3Client();
        S3_CLIENT.createBucket(CreateBucketRequest.builder().bucket(TEST_BUCKET_NAME).build());
    }

    @SuppressWarnings("resource")
    private static GenericContainer<?> createRustFsContainer() {
        return new GenericContainer<>(DockerImageName.parse(RUSTFS_IMAGE).asCompatibleSubstituteFor("rustfs/rustfs"))
                .withExposedPorts(RUSTFS_PORT)
                .withEnv("RUSTFS_ACCESS_KEY", RUSTFS_ACCESS_KEY)
                .withEnv("RUSTFS_SECRET_KEY", RUSTFS_SECRET_KEY)
                .withCommand("/data");
    }

    private static S3Client createS3Client() {
        return S3Client.builder()
                .region(Region.of(RUSTFS_REGION))
                .endpointOverride(URI.create("http://" + RUST_FS.getHost() + ":" + RUST_FS.getMappedPort(RUSTFS_PORT)))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(RUSTFS_ACCESS_KEY, RUSTFS_SECRET_KEY)))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }
}
