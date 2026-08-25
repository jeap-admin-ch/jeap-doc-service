package ch.admin.bit.jeap.doc.web;

import ch.admin.bit.jeap.security.resource.semanticAuthentication.SemanticApplicationRole;
import ch.admin.bit.jeap.security.resource.token.JeapAuthenticationToken;
import ch.admin.bit.jeap.security.test.resource.JeapAuthenticationTestTokenBuilder;
import ch.admin.bit.jeap.security.test.resource.configuration.DisableJeapPermitAllSecurityConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.net.URI;

/**
 * Runs the doc service against a real PostgreSQL and a real S3-compatible object storage.
 */
@SpringBootTest(classes = DocServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
// The doc service's own security applies, so the tests see the authentication and authorization of production
// instead of the permit-all chain the jEAP security test starter installs by default.
@Import(DisableJeapPermitAllSecurityConfiguration.class)
public abstract class DocServiceIntegrationTestBase {

    protected static final String SYSTEM_NAME = "jeapdoc";
    protected static final String TEST_BUCKET_NAME = "jeap-doc-test";

    private static final String RUSTFS_IMAGE = "rustfs/rustfs:1.0.0-beta.10";
    private static final int RUSTFS_PORT = 9000;
    private static final String RUSTFS_ACCESS_KEY = "dev";
    private static final String RUSTFS_SECRET_KEY = "devsecret";
    private static final String RUSTFS_REGION = "aws-global";

    // Started once for the whole test JVM: the Spring context is shared between the test classes, so a container
    // managed per test class would be gone while the context still uses it.
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres:17-alpine").asCompatibleSubstituteFor("postgres:17-alpine"));

    @SuppressWarnings("resource")
    private static final GenericContainer<?> RUST_FS = new GenericContainer<>(
            DockerImageName.parse(RUSTFS_IMAGE).asCompatibleSubstituteFor("rustfs/rustfs"))
            .withExposedPorts(RUSTFS_PORT)
            .withEnv("RUSTFS_ACCESS_KEY", RUSTFS_ACCESS_KEY)
            .withEnv("RUSTFS_SECRET_KEY", RUSTFS_SECRET_KEY)
            .withCommand("/data");

    /**
     * The object storage as the tests see it, to check what an upload actually stored.
     */
    protected static final S3Client S3_CLIENT;

    static {
        POSTGRES.start();
        RUST_FS.start();
        S3_CLIENT = createS3Client();
        createTestBucket();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("jeap.s3.client.region", () -> RUSTFS_REGION);
        registry.add("jeap.s3.client.endpoint-url", DocServiceIntegrationTestBase::rustFsEndpoint);
        registry.add("jeap.s3.client.access-key", () -> RUSTFS_ACCESS_KEY);
        registry.add("jeap.s3.client.secret-key", () -> RUSTFS_SECRET_KEY);
        registry.add("jeap.s3.client.tls", () -> false);
    }

    protected static JeapAuthenticationToken tokenWithRoles(SemanticApplicationRole... roles) {
        return JeapAuthenticationTestTokenBuilder.create().withUserRoles(roles).build();
    }

    /**
     * A role granting an operation on the uploads of one system - the system is carried in the tenant part of
     * the role.
     */
    protected static SemanticApplicationRole uploadsRole(String tenantSystem, String operation) {
        return role(tenantSystem, "uploads", operation);
    }

    /**
     * A role granting an operation on the documentation, independent of a single system.
     */
    protected static SemanticApplicationRole docsRole(String operation) {
        return role(null, "docs", operation);
    }

    private static SemanticApplicationRole role(String tenantSystem, String resource, String operation) {
        return SemanticApplicationRole.builder()
                .system(SYSTEM_NAME)
                .tenant(tenantSystem)
                .resource(resource)
                .operation(operation)
                .build();
    }

    private static String rustFsEndpoint() {
        return "http://" + RUST_FS.getHost() + ":" + RUST_FS.getMappedPort(RUSTFS_PORT);
    }

    private static S3Client createS3Client() {
        return S3Client.builder()
                .region(Region.of(RUSTFS_REGION))
                .endpointOverride(URI.create(rustFsEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(RUSTFS_ACCESS_KEY, RUSTFS_SECRET_KEY)))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    private static void createTestBucket() {
        try {
            S3_CLIENT.createBucket(CreateBucketRequest.builder().bucket(TEST_BUCKET_NAME).build());
        } catch (BucketAlreadyOwnedByYouException e) {
            // the bucket survives between test classes sharing the container
        }
    }
}
