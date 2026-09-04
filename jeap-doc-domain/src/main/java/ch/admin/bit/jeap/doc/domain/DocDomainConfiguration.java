package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.upload.UploadProperties;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureArtifactImportStep;
import ch.admin.bit.jeap.doc.domain.architecture.imports.MessageSchemaImportStep;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportExecutor;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureArtifactRepository;
import ch.admin.bit.jeap.doc.domain.port.MessageSchemaRepository;
import ch.admin.bit.jeap.doc.domain.port.MessageSchemaUpstream;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureArtifactUpstream;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportMetrics;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/**
 * Registers the domain services of the doc service.
 * <p>
 * The domain module holds the business logic and the ports it needs; the adapters are wired to those ports by
 * the auto-configuration of the adapter modules.
 */
@AutoConfiguration
@ComponentScan
@EnableScheduling
@EnableConfigurationProperties({UploadProperties.class, SiteProperties.class, BuildProperties.class,
        PublicationProperties.class, ArchitectureImportProperties.class})
public class DocDomainConfiguration {

    /**
     * The name of the executor every architecture import runs on - the scheduled ones and the catch-up at
     * startup.
     */
    public static final String ARCHITECTURE_IMPORT_TASK_EXECUTOR = "architectureImportTaskExecutor";

    /**
     * The clock the domain reads the time from - a test can replace it to control what "now" is.
     */
    @Bean
    @ConditionalOnMissingBean
    Clock docClock() {
        return Clock.systemDefaultZone();
    }

    /**
     * The executor every architecture import runs on - <b>the doc service's own, and not the application's,
     * and not the scheduler's</b>.
     * <p>
     * Not the scheduler's, because an import takes minutes and the cron fires for every environment at once:
     * run inline, four imports held every scheduler thread and the build poll waited for the last of them. Not
     * the application's, because an import is a long, occasional job and must not compete with the requests
     * that executor serves - and because an instance is free to add starters that bring executors of their
     * own, the database schema and the OpenAPI publishers each do, so <i>the</i> {@code TaskExecutor} of a
     * context has no answer and asking for one would fail an instance that had already started.
     * <p>
     * One thread and a bounded queue - see {@link ArchitectureImportExecutor}.
     */
    @Bean(name = ARCHITECTURE_IMPORT_TASK_EXECUTOR)
    ThreadPoolTaskExecutor architectureImportTaskExecutor() {
        return ArchitectureImportExecutor.create();
    }

    /**
     * The two artifact steps. They are the same code twice over - the two kinds are the same shape end to end,
     * which is why the architecture repository serves both indexes as one payload type.
     */
    @Bean
    ArchitectureArtifactImportStep openApiSpecImportStep(ArchitectureArtifactUpstream upstream,
                                                         ArchitectureArtifactRepository artifacts,
                                                         ArchitectureImportRepository imports,
                                                         ArchitectureImportMetrics metrics, Clock clock) {
        return new ArchitectureArtifactImportStep(ArchitectureImportKind.OPENAPI_SPEC, upstream, artifacts,
                imports, metrics, clock);
    }

    @Bean
    ArchitectureArtifactImportStep databaseSchemaImportStep(ArchitectureArtifactUpstream upstream,
                                                            ArchitectureArtifactRepository artifacts,
                                                            ArchitectureImportRepository imports,
                                                            ArchitectureImportMetrics metrics, Clock clock) {
        return new ArchitectureArtifactImportStep(ArchitectureImportKind.DATABASE_SCHEMA, upstream, artifacts,
                imports, metrics, clock);
    }

    /**
     * The message type schemas. A step like the two above it, except that its index carries no tag to diff, so
     * every listed version is asked about with the tag stored beside it - see {@link MessageSchemaImportStep}.
     */
    @Bean
    MessageSchemaImportStep messageSchemaImportStep(MessageSchemaUpstream upstream,
                                                    MessageSchemaRepository schemas,
                                                    ArchitectureImportRepository imports,
                                                    ArchitectureImportMetrics metrics, Clock clock) {
        return new MessageSchemaImportStep(upstream, schemas, imports, metrics, clock);
    }
}
