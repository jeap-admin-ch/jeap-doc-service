package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.DocDomainConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs the persistence adapter against a real PostgreSQL, and against nothing else.
 * <p>
 * The module is tested on its own: what the mapping, the constraints and the claim statement do is decided by
 * the database, and a failure here names the adapter instead of the endpoint that happens to use it. The domain
 * services are left out - they need adapters this module does not have.
 */
@SpringBootTest(classes = PostgresTestContainerBase.PersistenceTestApplication.class)
public abstract class PostgresTestContainerBase {

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DocPostgresTestContainer.container()::getJdbcUrl);
        registry.add("spring.datasource.username", DocPostgresTestContainer.container()::getUsername);
        registry.add("spring.datasource.password", DocPostgresTestContainer.container()::getPassword);
    }

    @SpringBootApplication(exclude = DocDomainConfiguration.class)
    static class PersistenceTestApplication {
    }
}
