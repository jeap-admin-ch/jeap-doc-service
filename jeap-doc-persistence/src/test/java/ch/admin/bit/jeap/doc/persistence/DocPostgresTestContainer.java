package ch.admin.bit.jeap.doc.persistence;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The one PostgreSQL of this module's tests.
 * <p>
 * Started once for the whole test JVM and never stopped: the Spring context is shared between the test classes,
 * so a container managed per class would be gone while the context still used it.
 */
public final class DocPostgresTestContainer {

    private static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18-alpine")
                    .asCompatibleSubstituteFor("postgres:18-alpine"));

    static {
        CONTAINER.start();
    }

    private DocPostgresTestContainer() {
    }

    public static PostgreSQLContainer container() {
        return CONTAINER;
    }
}
