package ch.admin.bit.jeap.doc.shutdown;

import ch.admin.bit.jeap.doc.domain.architecture.imports.MessageVersionRef;
import ch.admin.bit.jeap.doc.domain.port.MessageSchemaUpstream;
import ch.admin.bit.jeap.doc.domain.port.SchemaFetch;

import java.util.List;
import ch.admin.bit.jeap.doc.persistence.DocPostgresTestContainer;

import ch.admin.bit.jeap.doc.domain.DocumentationBuildRunner;
import ch.admin.bit.jeap.doc.domain.port.BuiltSite;
import ch.admin.bit.jeap.doc.domain.port.PublishedSite;
import ch.admin.bit.jeap.doc.domain.port.SiteBuildException;
import ch.admin.bit.jeap.doc.domain.port.SiteBuilder;
import ch.admin.bit.jeap.doc.domain.port.SitePublicationStorage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a stopping instance leaves in the database, asserted against the real one after the context has closed.
 * <p>
 * This is the test that would have caught the behaviour the shutdown handling replaced: a build interrupted
 * while the singletons are destroyed writes its terminal state against a connection pool that may already be
 * closing, and what is left behind is a row that reads as running and a lock nobody can take. Stopping in the
 * lifecycle phase - before the pool is destroyed - is the claim, and the only way to check it is to close a real
 * context over a real database and then look, through a connection of this test's own.
 */
class BuildShutdownIT {

    private static final String SITE = "default";

    @Test
    void closingTheContextMidBuild_thenNothingIsLeftRunningAndTheBuildIsAskedForAgain() throws Exception {
        ExecutorService ticking = Executors.newSingleThreadExecutor();
        ConfigurableApplicationContext context = start();
        try {
            BlockingSiteBuilder siteBuilder = context.getBean(BlockingSiteBuilder.class);
            DocumentationBuildRunner runner = context.getBean(DocumentationBuildRunner.class);
            requestABuild(context);

            // A tick of this test's own, beside the one the scheduler runs when the context starts. Which of the
            // two picks the build up is a race and does not matter - what matters is that one of them is in the
            // middle of a build when the context closes, and the latch below is what says so.
            Future<Boolean> tick = ticking.submit(runner::runOnce);
            assertThat(siteBuilder.started.await(20, TimeUnit.SECONDS))
                    .describedAs("a build should have started").isTrue();

            context.close();

            // Whichever thread built it, this one is finished: the runner serialises its ticks.
            tick.get(20, TimeUnit.SECONDS);
        } finally {
            ticking.shutdownNow();
            if (context.isActive()) {
                context.close();
            }
        }

        try (Connection connection = ownConnection()) {
            assertThat(statesOf(connection, SITE))
                    .describedAs("the build should be recorded as aborted, not left running")
                    .containsExactly("ABORTED");
            assertThat(countOf(connection, "select count(*) from documentation_build_request where site = ?"))
                    .describedAs("the build should have been asked for again").isEqualTo(1);
            assertThat(lockIsHeld(connection))
                    .describedAs("the site's lock should have been given back, not left to expire").isFalse();
        }
    }

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(ShutdownTestApplication.class)
                .properties(
                        "spring.datasource.url=" + DocPostgresTestContainer.container().getJdbcUrl(),
                        "spring.datasource.username=" + DocPostgresTestContainer.container().getUsername(),
                        "spring.datasource.password=" + DocPostgresTestContainer.container().getPassword(),
                        // Long enough that the scheduler never fires by itself: the tick under test is the one
                        // this test starts, so that what is asserted is the shutdown and not a race with it.
                        "jeap.doc.publication.url=https://doc.example.ch",
                        "jeap.doc.build.poll-interval=1h",
                        "jeap.doc.build.shutdown-timeout=15s",
                        "spring.lifecycle.timeout-per-shutdown-phase=20s",
                        "spring.main.web-application-type=none")
                .run();
    }

    private void requestABuild(ConfigurableApplicationContext context) {
        context.getBean(ch.admin.bit.jeap.doc.domain.DocumentationBuildTrigger.class)
                .requestBecauseOfSchedule(SITE);
    }

    private Connection ownConnection() throws Exception {
        return DriverManager.getConnection(DocPostgresTestContainer.container().getJdbcUrl(), DocPostgresTestContainer.container().getUsername(),
                DocPostgresTestContainer.container().getPassword());
    }

    private static java.util.List<String> statesOf(Connection connection, String site) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "select state from documentation_build where site = ? order by id")) {
            statement.setString(1, site);
            try (ResultSet rows = statement.executeQuery()) {
                java.util.List<String> states = new java.util.ArrayList<>();
                while (rows.next()) {
                    states.add(rows.getString(1));
                }
                return states;
            }
        }
    }

    private static int countOf(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, SITE);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }

    private static boolean lockIsHeld(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "select lock_until from shedlock where name = ?")) {
            statement.setString(1, "documentationBuild-" + SITE);
            try (ResultSet rows = statement.executeQuery()) {
                // No row at all means it was never taken; a lock_until in the past means it was given back.
                return rows.next() && rows.getTimestamp(1).toInstant().isAfter(Instant.now());
            }
        }
    }

    @SpringBootApplication
    static class ShutdownTestApplication {

        /** Uploads play no part here; the upload service wants its storage port satisfied all the same. */
        @Bean
        ch.admin.bit.jeap.doc.domain.port.DocumentationBundleStorage bundleStorage() {
            return (uploadId, attempt, bundle, sizeInBytes) -> {
                throw new UnsupportedOperationException("No upload happens in this test.");
            };
        }

        /**
         * The meters are not what is under test, and the metrics adapter is not on this module's classpath -
         * the domain asks for its ports, so a set that says nothing is exactly enough. The container's memory
         * is read by that adapter too, and it is no more under test here than the meters are.
         */
        @Bean
        ch.admin.bit.jeap.doc.domain.port.BuildMetrics buildMetrics() {
            return ch.admin.bit.jeap.doc.domain.port.BuildMetrics.NONE;
        }

        @Bean
        ch.admin.bit.jeap.doc.domain.port.ContainerMemory containerMemory() {
            return ch.admin.bit.jeap.doc.domain.port.ContainerMemory.NONE;
        }

        @Bean
        ch.admin.bit.jeap.doc.domain.port.UploadMetrics uploadMetrics() {
            return new ch.admin.bit.jeap.doc.domain.port.UploadMetrics() {
                @Override
                public void stored(ch.admin.bit.jeap.doc.domain.upload.DocumentationType type, long sizeInBytes,
                                   java.time.Duration duration) {
                    // Nothing is measured here.
                }

                @Override
                public void repeated(ch.admin.bit.jeap.doc.domain.upload.DocumentationType type,
                                     java.time.Duration duration) {
                    // Nothing is measured here.
                }

                @Override
                public void failed(ch.admin.bit.jeap.doc.domain.upload.DocumentationType type,
                                   ch.admin.bit.jeap.doc.domain.upload.InvalidUploadException.Code reason,
                                   java.time.Duration duration) {
                    // Nothing is measured here.
                }
            };
        }

        @Bean
        BlockingSiteBuilder blockingSiteBuilder() {
            return new BlockingSiteBuilder();
        }

        /**
         * No architecture repository: the client of it is not on this module's classpath, and what a build
         * does with an imported model is not what is under test. An instance with no environment configured
         * behaves exactly like this, so it is also a shape that really occurs.
         */
        @Bean
        ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUpstream architectureModelUpstream() {
            return new ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUpstream() {

                @Override
                public java.util.Set<String> environments() {
                    return java.util.Set.of();
                }

                @Override
                public java.util.Optional<String> urlOf(String environment) {
                    return java.util.Optional.empty();
                }

                @Override
                public java.util.List<String> systemNames(String environment) {
                    return java.util.List.of();
                }

                @Override
                public java.util.Optional<ch.admin.bit.jeap.doc.domain.architecture.SystemTopology> topology(
                        String environment, String system) {
                    return java.util.Optional.empty();
                }

                @Override
                public java.util.Optional<java.util.List<
                        ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessage>> messages(
                        String environment, String system) {
                    return java.util.Optional.empty();
                }
            };
        }

        @Bean
        ch.admin.bit.jeap.doc.domain.port.ArchitectureArtifactUpstream architectureArtifactUpstream() {
            return new ch.admin.bit.jeap.doc.domain.port.ArchitectureArtifactUpstream() {

                @Override
                public java.util.Optional<ch.admin.bit.jeap.doc.domain.port.Fetched<java.util.List<
                        ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureArtifactRef>>> index(
                        String environment,
                        ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind kind,
                        String knownIndexEtag) {
                    return java.util.Optional.empty();
                }

                @Override
                public ch.admin.bit.jeap.doc.domain.port.ArtifactFetch content(
                        String environment,
                        ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureArtifactRef entry,
                        String knownEtag) {
                    return ch.admin.bit.jeap.doc.domain.port.ArtifactFetch.skipped("nothing is served here");
                }
            };
        }

        /** The fourth import kind needs an upstream too; this context serves nothing from any of them. */
        @Bean
        MessageSchemaUpstream messageSchemaUpstream() {
            return new MessageSchemaUpstream() {

                @Override
                public List<MessageVersionRef> index(String environment) {
                    return List.of();
                }

                @Override
                public SchemaFetch version(String environment, MessageVersionRef ref, String knownEtag) {
                    return SchemaFetch.skipped("nothing is served here");
                }
            };
        }

        @Bean
        ch.admin.bit.jeap.doc.domain.port.ArchitectureImportMetrics architectureImportMetrics() {
            return ch.admin.bit.jeap.doc.domain.port.ArchitectureImportMetrics.NONE;
        }

        /** The publication is not what is under test here, and S3 is not part of this module's tests. */
        @Bean
        SitePublicationStorage publicationStorage() {
            return new SitePublicationStorage() {
                @Override
                public PublishedSite publish(String prefix, Path directory) {
                    return new PublishedSite(prefix, 1, 1);
                }

                @Override
                public void delete(String prefix) {
                    // Nothing is stored, so nothing is removed.
                }

                @Override
                public java.util.Optional<ch.admin.bit.jeap.doc.domain.port.StoredObject> open(String prefix,
                                                                                              String path) {
                    return java.util.Optional.empty();
                }

                @Override
                public boolean exists(String prefix, String path) {
                    return false;
                }
            };
        }
    }

    /** Blocks the way a real Docusaurus build blocks, and ends when it is given up on. */
    static class BlockingSiteBuilder implements SiteBuilder {

        @Override
        public void describeRun(ch.admin.bit.jeap.doc.domain.port.BuiltSite generated,
                                ch.admin.bit.jeap.doc.domain.port.DocumentationStatus status) {
            // What the run cost is not what this test is about.
        }

        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch aborted = new CountDownLatch(1);

        @Override
        public BuiltSite generate(long buildId, ch.admin.bit.jeap.doc.domain.Site site,
                                  java.time.Instant generatedAt) {
            started.countDown();
            try {
                if (!aborted.await(60, TimeUnit.SECONDS)) {
                    return new BuiltSite(Path.of("build"), 1, 1, 1, java.util.Map.of());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new SiteBuildException("The site generator was given up on: this instance is stopping.");
        }

        @Override
        public void abortCurrentBuild() {
            aborted.countDown();
        }

        @Override
        public void discard(long buildId) {
            // No workspace was made.
        }

        @Override
        public int sweepWorkspaces(Set<Long> runningBuildIds) {
            return 0;
        }
    }
}
