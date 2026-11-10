package ch.admin.bit.jeap.doc.test;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.AllowOverridePactUrl;
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactBroker;
import ch.admin.bit.jeap.doc.web.DocServiceApplication;
import ch.admin.bit.jeap.security.resource.properties.ResourceServerProperties;
import ch.admin.bit.jeap.security.resource.semanticAuthentication.SemanticApplicationRole;
import ch.admin.bit.jeap.security.resource.token.JeapAuthenticationContext;
import ch.admin.bit.jeap.security.test.jws.JwsBuilderFactory;
import ch.admin.bit.jeap.security.test.resource.configuration.JeapOAuth2IntegrationTestResourceConfiguration;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.temporal.ChronoUnit;

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.DEFINED_PORT;

/**
 * Verifies the contracts other services hold with the doc service as their provider.
 * <p>
 * An instance extends this with an empty class, sets its Pact name with {@link
 * Pacticipants#setDocServicePacticipant} before the class runs, and names the class in its {@code
 * pact-verify.yml}. <b>Running the verification needs a Docker daemon</b>: the application is the real one and
 * does not start without a database and an object storage.
 * <p>
 * The application runs with the profile {@code pact-provider-test}, which this module carries. It switches off
 * the search and every build, so that nothing starts a site generator behind a verification's back.
 */
@SuppressWarnings("unused")
// A defined port rather than a random one: the calls are bearer-authenticated, and the tokens the verification
// signs are validated against the JWKS this application serves itself under the test profile - so the port has
// to be known before the context starts. Both it and the JWKS URI are in application-pact-provider-test.yml.
@SpringBootTest(classes = DocServiceApplication.class, webEnvironment = DEFINED_PORT)
@ActiveProfiles({"pact-provider-test"})
// Serves that JWKS, and switches off the permit-all chain of jeap-spring-boot-security-starter-test - without
// which production security would be shadowed and every interaction would be verified against an unprotected
// application.
@Import(JeapOAuth2IntegrationTestResourceConfiguration.class)
@Provider(Pacticipants.DOC_SERVICE)
@PactBroker
@IgnoreNoPactsToVerify
@AllowOverridePactUrl
public class PactProviderTestBase {

    /**
     * The images of the database and the object storage, overridable for a build that pulls its images from a
     * mirror rather than from Docker Hub.
     */
    public static final String POSTGRES_IMAGE_PROPERTY = "doc.test.postgres-image";
    public static final String OBJECT_STORAGE_IMAGE_PROPERTY = "doc.test.object-storage-image";

    private static final String BUCKET = "jeap-doc-pact-test";
    private static final int OBJECT_STORAGE_PORT = 9000;
    private static final String OBJECT_STORAGE_ACCESS_KEY = "dev";
    private static final String OBJECT_STORAGE_SECRET_KEY = "devsecret";
    private static final String OBJECT_STORAGE_REGION = "aws-global";

    /**
     * A real PostgreSQL, in the version the service runs on in production. Started once for the whole test JVM
     * and never stopped: the Spring context outlives any per-class lifecycle.
     */
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse(
            System.getProperty(POSTGRES_IMAGE_PROPERTY, "postgres:18-alpine")));

    @SuppressWarnings("resource")
    private static final GenericContainer<?> OBJECT_STORAGE = new GenericContainer<>(
            DockerImageName.parse(System.getProperty(OBJECT_STORAGE_IMAGE_PROPERTY, "rustfs/rustfs:1.0.0-beta.10"))
                    .asCompatibleSubstituteFor("rustfs/rustfs"))
            .withExposedPorts(OBJECT_STORAGE_PORT)
            .withEnv("RUSTFS_ACCESS_KEY", OBJECT_STORAGE_ACCESS_KEY)
            .withEnv("RUSTFS_SECRET_KEY", OBJECT_STORAGE_SECRET_KEY)
            .withCommand("/data");

    private static Path installedTemplate;

    static {
        POSTGRES.start();
        OBJECT_STORAGE.start();
        createBucket();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("jeap.s3.client.region", () -> OBJECT_STORAGE_REGION);
        registry.add("jeap.s3.client.endpoint-url", PactProviderTestBase::objectStorageEndpoint);
        registry.add("jeap.s3.client.access-key", () -> OBJECT_STORAGE_ACCESS_KEY);
        registry.add("jeap.s3.client.secret-key", () -> OBJECT_STORAGE_SECRET_KEY);
        registry.add("jeap.s3.client.tls", () -> false);
        registry.add("jeap.doc.storage.bucket", () -> BUCKET);
        registry.add("jeap.doc.build.node-modules-directory",
                () -> installedTemplate().resolve("node_modules"));
        registry.add("jeap.doc.build.workspace-directory", () -> installedTemplate().resolve("workspaces"));
    }

    /** The role the doc service's webhook call needs. It carries no tenant: it reports a whole scoring run. */
    private static final String GOVERNANCE_NOTIFICATIONS = "governance-notifications";

    private static final String BEARER = "Bearer ";

    @LocalServerPort
    private int localServerPort;

    /**
     * Signs the token every request is verified with. The key it signs with is the one the JWKS endpoint of
     * {@link JeapOAuth2IntegrationTestResourceConfiguration} publishes, which is what makes the token valid for
     * this application and for no other.
     */
    @Autowired(required = false)
    private JwsBuilderFactory jwsBuilderFactory;

    /**
     * The system the semantic roles of this instance are named after - the first part of every role, and
     * checked, so a token carrying another landscape's role is rejected. Read from the configuration rather
     * than spelled here, so that the token stays valid for whatever an instance's profile sets.
     */
    @Value("${jeap.security.oauth2.resourceserver.system-name}")
    private String securitySystemName;

    /**
     * Where the token is addressed to: the resource id of this application. Taken from the properties of the jEAP
     * security starter rather than from the configuration, because the starter falls back to
     * {@code spring.application.name} when an instance sets none - so the token carries exactly the audience this
     * application validates, and is accepted whatever its strict audience validation is set to, {@code on}
     * included.
     */
    @Autowired
    private ResourceServerProperties resourceServerProperties;

    @BeforeEach
    void setUp(PactVerificationContext context) {
        // If there are no pacts there will be no context.
        if (context != null) {
            context.setTarget(new HttpTestTarget("localhost", localServerPort, "/"));
        }
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void testPacts(PactVerificationContext context, HttpRequest request) {
        // If there are no pacts there will be no context, and no request either.
        if (context != null) {
            if (request != null) {
                authenticate(request);
            }
            context.verifyInteraction();
        }
    }

    /**
     * Replaces whatever bearer token a pact recorded with one signed here and now.
     * <p>
     * <b>A token cannot be part of a contract.</b> One written into a pact file when the consumer's test ran
     * would be replayed by this verification days or weeks later, long expired, and every interaction would
     * fail on the clock rather than on the contract. So a consumer records a placeholder, this puts a fresh
     * token in its place, and <b>no interaction may assert anything about the {@code Authorization}
     * header</b>.
     * <p>
     * The token is addressed to this application and carries the semantic role of the webhook, so <b>audience
     * validation and authorization are exercised rather than mocked</b>: a call without the role is refused by
     * this application just as it is in production.
     * <p>
     * <b>Only a bearer header is replaced.</b> A request carrying none belongs to an interaction about being
     * unauthenticated, and substituting a token into it would verify something the consumer never sends.
     */
    private void authenticate(HttpRequest request) {
        if (jwsBuilderFactory == null || !isBearer(request)) {
            return;
        }
        String token = jwsBuilderFactory
                .createValidFromNowBuilder("governance-service", JeapAuthenticationContext.SYS, 5, ChronoUnit.MINUTES)
                .withAudiences(resourceServerProperties.getResourceId())
                .withUserRoles(SemanticApplicationRole.builder()
                        .system(securitySystemName)
                        .resource(GOVERNANCE_NOTIFICATIONS)
                        .operation("write")
                        .build())
                .build()
                .serialize();
        request.setHeader(HttpHeaders.AUTHORIZATION, BEARER + token);
    }

    private static boolean isBearer(HttpRequest request) {
        Header authorization = request.getFirstHeader(HttpHeaders.AUTHORIZATION);
        return authorization != null && authorization.getValue() != null
               && authorization.getValue().startsWith(BEARER);
    }

    /**
     * The Governance Service reports a completed scoring run. Nothing has to be prepared: the call is answered
     * before anything is imported, and an instance with no Governance Service configured - which is what this
     * application is - imports nothing, so the answer is the same either way.
     */
    @State("The governance webhook accepts notifications")
    void theGovernanceWebhookAcceptsNotifications() {
        // nothing to set up
    }

    /**
     * What the image build of an instance produces, in miniature: an empty {@code node_modules} and the
     * lockfile of the site template on the classpath, which is exactly what the startup check compares.
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

    private static String objectStorageEndpoint() {
        return "http://" + OBJECT_STORAGE.getHost() + ":" + OBJECT_STORAGE.getMappedPort(OBJECT_STORAGE_PORT);
    }

    private static void createBucket() {
        try (S3Client s3 = S3Client.builder()
                .region(Region.of(OBJECT_STORAGE_REGION))
                .endpointOverride(URI.create(objectStorageEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(OBJECT_STORAGE_ACCESS_KEY, OBJECT_STORAGE_SECRET_KEY)))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()) {
            s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        } catch (BucketAlreadyOwnedByYouException e) {
            // the bucket survives between the classes sharing the container
        }
    }
}
