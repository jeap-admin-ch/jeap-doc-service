package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.db.tx.ReadReplicaAwareTransactionManager;
import ch.admin.bit.jeap.doc.domain.PartKey;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.port.DisplayReads;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every display read runs in a transaction routed to the read replica, and a read of a write flow does not.
 * <p>
 * The replica is switched on but there is only one database, so what is checked is the routing decision each
 * connection is taken under - the one the replica's data source acts on.
 */
@TestPropertySource(properties = "jeap.datasource.replica.enabled=true")
@Import(DisplayReadsAdapterIT.RecordingDataSourceConfiguration.class)
class DisplayReadsAdapterIT extends PostgresTestContainerBase {

    /** Whether each connection taken was routed to the replica, in order. */
    static final List<Boolean> ROUTED = new CopyOnWriteArrayList<>();

    private static final Map<Class<?>, Object> ARGUMENTS = Map.of(
            String.class, "display-reads",
            int.class, 5,
            long.class, 1L,
            PartKey.class, PartKey.shellOf("display-reads"),
            ArchitectureImportKind.class, ArchitectureImportKind.MODEL);

    @Autowired
    private DisplayReads reads;

    @Autowired
    private DocumentationBuildRepository builds;

    @BeforeEach
    void setUp() {
        ROUTED.clear();
    }

    static List<Method> everyDisplayRead() {
        return Arrays.stream(DisplayReads.class.getMethods()).toList();
    }

    @ParameterizedTest
    @MethodSource("everyDisplayRead")
    void aDisplayRead_isRoutedToTheReplica(Method read) throws Exception {
        Object[] arguments = Arrays.stream(read.getParameterTypes()).map(ARGUMENTS::get).toArray();

        read.invoke(reads, arguments);

        assertThat(ROUTED).describedAs(read.getName()).isNotEmpty().containsOnly(true);
    }

    @Test
    void aReadOfTheRepository_staysOnThePrimary() {
        builds.publishedPartsOf("display-reads");

        assertThat(ROUTED).isNotEmpty().containsOnly(false);
    }

    @TestConfiguration
    static class RecordingDataSourceConfiguration {

        @Bean
        static BeanPostProcessor recordingDataSource() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof DataSource dataSource && !(bean instanceof Recording)
                            ? new Recording(dataSource) : bean;
                }
            };
        }
    }

    static final class Recording extends DelegatingDataSource {

        Recording(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            ROUTED.add(ReadReplicaAwareTransactionManager.routeTopLevelTransactionToReadReplica());
            return super.getConnection();
        }
    }
}
