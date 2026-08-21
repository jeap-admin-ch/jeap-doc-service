package ch.admin.bit.jeap.doc.persistence;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * The persistence adapter: it implements the repository ports of the domain with Spring Data JPA on PostgreSQL.
 * <p>
 * The entities, the repositories and the Flyway migrations of the doc service belong into this module - the
 * domain sees the repository ports only.
 */
@AutoConfiguration
@EnableTransactionManagement
@ComponentScan
@EntityScan
@EnableJpaRepositories
public class DocPersistenceConfiguration {
}
