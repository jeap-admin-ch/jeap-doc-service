package ch.admin.bit.jeap.doc.shutdown;

import ch.admin.bit.jeap.doc.domain.DocumentationBuildTrigger;
import ch.admin.bit.jeap.doc.domain.PartKey;
import ch.admin.bit.jeap.doc.persistence.DocPostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a build starts because something asked for one, and not because a poll came round.
 * <p>
 * The unit tests say the trigger asks the pickup and the pickup runs a pass. What only a real context can say
 * is that the two are wired to each other and to the runner - and that nothing in between needs the poll: the
 * poll interval here is an hour, the one poll at startup happens before the request exists, and no tick of
 * this test's own is run. So the build that starts was started by the trigger.
 * <p>
 * It shares the harness of {@link BuildShutdownIT}, whose site generator blocks the way a real one does.
 */
class BuildPickupIT {

    /**
     * <b>Its own site, and not the default one.</b> The test classes of this module share one database, and a
     * build given up on leaves an {@code ABORTED} row behind - which {@link BuildShutdownIT} counts for the
     * site it uses. Two classes on one site id are then a failure that depends on the order the classes happen
     * to run in.
     */
    private static final String SITE = "pick-up-it";

    @Test
    void requestingABuild_thenItStartsWithoutWaitingForAPoll() throws Exception {
        ConfigurableApplicationContext context = start();
        try {
            BuildShutdownIT.BlockingSiteBuilder siteBuilder =
                    context.getBean(BuildShutdownIT.BlockingSiteBuilder.class);

            context.getBean(DocumentationBuildTrigger.class)
                    .requestBecauseAnOperatorAsked(PartKey.shellOf(SITE));

            assertThat(siteBuilder.started.await(20, TimeUnit.SECONDS))
                    .describedAs("the trigger should have started a build pass on this instance")
                    .isTrue();
        } finally {
            if (context.isActive()) {
                context.close();
            }
        }
    }

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(BuildShutdownIT.ShutdownTestApplication.class)
                .properties(
                        "spring.datasource.url=" + DocPostgresTestContainer.container().getJdbcUrl(),
                        "spring.datasource.username=" + DocPostgresTestContainer.container().getUsername(),
                        "spring.datasource.password=" + DocPostgresTestContainer.container().getPassword(),
                        "jeap.doc.publication.url=https://doc.example.ch",
                        // The site of this class alone - see SITE.
                        "jeap.doc.sites." + SITE + ".title=Pick-up",
                        // An hour, so that a poll cannot be what picks the build up.
                        "jeap.doc.build.poll-interval=1h",
                        "jeap.doc.build.shutdown-timeout=15s",
                        "spring.lifecycle.timeout-per-shutdown-phase=20s",
                        "spring.main.web-application-type=none")
                .run();
    }
}
