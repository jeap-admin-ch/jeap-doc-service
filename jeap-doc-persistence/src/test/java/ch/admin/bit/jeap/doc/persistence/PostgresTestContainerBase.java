package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.DocDomainConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Runs the persistence adapter against a real PostgreSQL, and against nothing else.
 * <p>
 * The module is tested on its own: what the mapping, the constraints and the claim statement do is decided by
 * the database, and a failure here names the adapter instead of the endpoint that happens to use it. The domain
 * services are left out - they need adapters this module does not have.
 */
@SpringBootTest(classes = PostgresTestContainerBase.PersistenceTestApplication.class)
public abstract class PostgresTestContainerBase {

    // Started once for the whole test JVM: the Spring context is shared between the test classes, so a container
    // managed per test class would be gone while the context still uses it.
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres:17-alpine")
                    .asCompatibleSubstituteFor("postgres:17-alpine"));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @SpringBootApplication(exclude = DocDomainConfiguration.class)
    static class PersistenceTestApplication {
    }
}
