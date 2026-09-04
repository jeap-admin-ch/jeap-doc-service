package ch.admin.bit.jeap.doc.persistence;

import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.support.KeepAliveLockProvider;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

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
     * <p>
     * It is <b>kept alive</b>: the wrapper extends a held lock in the background, at half its lease. That is
     * what lets a documentation build - which may run for a quarter of an hour - hold a lock leased for two
     * minutes, so that the site of an instance that is killed is buildable again two minutes later instead of
     * after the longest a build could possibly have taken.
     */
    @Bean
    LockProvider lockProvider(DataSource dataSource, LockKeepAliveThread keepAlive) {
        return new KeepAliveLockProvider(new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build()), keepAlive.executor());
    }

    /**
     * How a job takes the lock that keeps it to one instance. It is what the domain's {@code ExclusiveWork} port
     * is implemented with, and the only place ShedLock is named.
     */
    @Bean
    @ConditionalOnMissingBean
    LockingTaskExecutor lockingTaskExecutor(LockProvider lockProvider) {
        return new DefaultLockingTaskExecutor(lockProvider);
    }

    /**
     * The one thread that extends the held locks, and nothing else.
     * <p>
     * It is a bean of its own type and <b>not</b> a {@code ScheduledExecutorService} bean, on purpose. Spring
     * Boot declares its task scheduler only while the context holds no {@code ScheduledExecutorService}, so
     * exposing this one as such silently replaced the scheduler: every scheduled task of the doc service - the
     * imports, the build poll, the builds - ran on this single thread, {@code spring.task.scheduling.pool.size}
     * had no effect, and a lock could not be extended while the work it protected occupied the thread that
     * would extend it.
     */
    @Bean
    LockKeepAliveThread lockKeepAliveThread() {
        return new LockKeepAliveThread();
    }

    /**
     * Holds the keep-alive thread and shuts it down with the context - with {@code shutdownNow} rather than
     * closed: by the time the beans are destroyed there is nothing left worth extending, and waiting for a
     * periodic task to come round would only make the shutdown longer.
     */
    static final class LockKeepAliveThread implements DisposableBean {

        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "shedlock-keep-alive");
            thread.setDaemon(true);
            return thread;
        });

        ScheduledExecutorService executor() {
            return executor;
        }

        @Override
        public void destroy() {
            executor.shutdownNow();
        }
    }
}
