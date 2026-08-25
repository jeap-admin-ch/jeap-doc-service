package ch.admin.bit.jeap.doc.persistence;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

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

    /**
     * Where the scheduled jobs of the doc service keep their lock, so that of several instances only one runs a
     * job. The lock is timed by the database rather than by the instances, which do not share a clock.
     */
    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }
}
