package ch.admin.bit.jeap.doc.web;

import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.custom.CustomSubject;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationRepository;
import ch.admin.bit.jeap.security.resource.semanticAuthentication.SemanticApplicationRole;
import ch.admin.bit.jeap.security.resource.token.JeapAuthenticationToken;
import ch.admin.bit.jeap.security.test.resource.JeapAuthenticationTestTokenBuilder;
import ch.admin.bit.jeap.security.test.resource.configuration.DisableJeapPermitAllSecurityConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
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

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

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
            DockerImageName.parse("postgres:18-alpine").asCompatibleSubstituteFor("postgres:18-alpine"));

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
        registry.add("jeap.doc.build.node-modules-directory", DocServiceIntegrationTestBase::nodeModules);
        registry.add("jeap.doc.build.workspace-directory", () -> installedTemplate().resolve("workspaces"));
    }

    /**
     * The dependencies of the site template, as an instance's image installs them.
     * <p>
     * Most tests here never generate a site - the build poller is set to an interval longer than the suite - and
     * for them the miniature fixture below is enough: the startup check compares the lockfile, which is what
     * tells an instance that its image and its jar disagree. {@code DocumentationGenerationIT} does run the
     * generator, so when the real install is there - {@code target/site-install}, produced before the
     * integration tests by the same {@code npm ci} an image uses - that is what is used.
     */
    private static Path nodeModules() {
        Path installed = Path.of("target/site-install/node_modules").toAbsolutePath();
        return Files.isDirectory(installed) ? installed : installedTemplate().resolve("node_modules");
    }

    /**
     * What the image build of an instance produces, in miniature: an empty {@code node_modules} and the
     * lockfile of the template on the classpath, which is exactly what the startup check compares.
     */
    private static synchronized Path installedTemplate() {
        if (installedTemplate == null) {
            try {
                installedTemplate = Files.createTempDirectory("jeap-doc-template");
                Files.createDirectories(installedTemplate.resolve("node_modules"));
                Files.createDirectories(installedTemplate.resolve("workspaces"));
                try (InputStream lockfile = new ClassPathResource("site/package-lock.json").getInputStream()) {
                    Files.copy(lockfile, installedTemplate.resolve("package-lock.json"));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return installedTemplate;
    }

    private static Path installedTemplate;

    /**
     * Clears the documentation of every site, so the next class does not inherit what an earlier one wrote.
     * <p>
     * <b>Everything, not only this class's own rows.</b> The classes of this module run one after another and
     * share one database, so after the last test of a class what is in it is what that class left - and
     * removing all of it is the same thing, said more simply. It would not be, were this module ever to run
     * its classes in parallel.
     * <p>
     * <b>A documented system is a part of its site</b>, and the Spring context - with its database - is shared
     * by every class here. So a set one class leaves behind is a further part for every later class, and a
     * class that builds a whole site then really generates that part too: in CI, where the classes run in
     * alphabetical order, four of the seven parts {@code DocumentationGenerationIT} rebuilt sixty times over
     * were sets three earlier classes had left standing - an hour of site generation for documentation no
     * test was asserting anything about.
     * <p>
     * <b>After the class and not after each test</b>, so that what a class sets up survives its own methods,
     * and through the repository rather than the removal service, which would ask for a build of what is
     * being torn down.
     */
    @AfterAll
    static void forgetEverythingDocumentedSoFar() {
        if (sharedContext == null) {
            return;
        }
        CustomDocumentationRepository documentation =
                sharedContext.getBean(CustomDocumentationRepository.class);
        DocumentationSites sites = sharedContext.getBean(DocumentationSites.class);
        for (String site : sites.ids()) {
            for (CustomSubject subject : documentation.subjectsOf(site)) {
                documentation.removeSubject(subject);
            }
        }
    }

    /**
     * The context, kept for the tear-down above, which has to be static and therefore cannot be injected.
     * It is the same context for every class here - that is what makes the tear-down necessary at all.
     */
    private static ApplicationContext sharedContext;

    @Autowired
    void rememberTheSharedContext(ApplicationContext context) {
        sharedContext = context;
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

    /**
     * A role granting an operation on the documentation sites, independent of a single system - a site carries
     * the documentation of every system on it, so it is not any one system's to administer.
     */
    protected static SemanticApplicationRole sitesRole(String operation) {
        return role(null, "sites", operation);
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
