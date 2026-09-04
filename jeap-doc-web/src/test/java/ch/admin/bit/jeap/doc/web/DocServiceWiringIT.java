package ch.admin.bit.jeap.doc.web;

import ch.admin.bit.jeap.doc.domain.port.MessageSchemaUpstream;
import ch.admin.bit.jeap.doc.domain.port.MessageSchemaRepository;
import ch.admin.bit.jeap.doc.domain.port.ContainerMemory;
import ch.admin.bit.jeap.doc.domain.DocumentationBuildTrigger;
import ch.admin.bit.jeap.doc.domain.upload.DocumentationType;
import ch.admin.bit.jeap.doc.domain.upload.DocumentationUploadService;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureArtifactRepository;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureArtifactUpstream;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportMetrics;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportRepository;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelRepository;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUpstream;
import ch.admin.bit.jeap.doc.domain.port.BuildMetrics;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBundleStorage;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRequestRepository;
import ch.admin.bit.jeap.doc.domain.port.DocumentationSubjectRepository;
import ch.admin.bit.jeap.doc.domain.port.DocumentationUploadRepository;
import ch.admin.bit.jeap.doc.domain.port.ExclusiveWork;
import ch.admin.bit.jeap.doc.domain.port.SiteBuilder;
import ch.admin.bit.jeap.doc.domain.port.SitePublicationStorage;
import ch.admin.bit.jeap.doc.domain.port.UploadMetrics;
import ch.admin.bit.jeap.doc.domain.template.StructureTemplate;
import ch.admin.bit.jeap.doc.domain.template.StructureTemplates;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the whole service is one working thing, and not a set of modules that each pass their own tests.
 * <p>
 * The domain talks to adapters through ports and names none of them. That is what keeps it clean, and it is also
 * what makes a missing adapter invisible: a module left out of an instance, an auto-configuration not registered,
 * a bean renamed - none of it is a compile error, and each of the other test classes would still be green. This
 * is where that is caught, against the real application context an instance starts.
 */
class DocServiceWiringIT extends DocServiceIntegrationTestBase {

    @Autowired
    private ApplicationContext context;

    /**
     * Every port has exactly one adapter behind it. More than one would mean an instance's behaviour depends on
     * a bean name; none means the service starts and fails at the first upload or the first build.
     */
    @ParameterizedTest
    @MethodSource("everyPort")
    void everyPortOfTheDomain_hasExactlyOneAdapter(Class<?> port) {
        assertThat(context.getBeanNamesForType(port))
                .describedAs("adapters bound to %s", port.getSimpleName())
                .hasSize(1);
    }

    /**
     * <b>Found by scanning the package, not listed by hand.</b> A hand-kept list is exactly as stale as the
     * last person to remember it, and this branch already shipped one that had forgotten three ports - which
     * is a service that starts and fails at the first build. Every public interface directly in
     * {@code ...doc.domain.port} is a driven port; the sealed ones there are the answers a port gives back.
     */
    static List<Class<?>> everyPort() throws IOException, ClassNotFoundException {
        String packageName = ArchitectureModelUpstream.class.getPackageName();
        List<Class<?>> ports = new ArrayList<>();
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources(
                "classpath*:" + packageName.replace('.', '/') + "/*.class")) {
            String name = resource.getFilename().substring(0,
                    resource.getFilename().length() - ".class".length());
            if (name.indexOf('$') >= 0) {
                // A nested type is part of what a port says, not a port: ContainerMemory.Measurement is the
                // reading one of them returns, and nothing binds an adapter to it.
                continue;
            }
            Class<?> candidate = Class.forName(packageName + "." + name);
            if (candidate.isInterface() && Modifier.isPublic(candidate.getModifiers())
                && !candidate.isSealed()) {
                ports.add(candidate);
            }
        }
        assertThat(ports).describedAs("the ports of the domain, found on the classpath").isNotEmpty();
        return ports;
    }

    /**
     * A structure template is a <b>plugin point, not a driven port</b>: there are as many implementations as
     * there are templates, so the rule above does not apply to it. What has to hold is that at least one is
     * found - an instance with none generates no documentation at all and still starts, which is legitimate
     * but is not what this service is deployed as.
     */
    @Test
    void structureTemplates_areFoundOnTheClasspathAndRegistered() {
        assertThat(context.getBeanNamesForType(StructureTemplate.class))
                .describedAs("structure templates, of which there may be many")
                .isNotEmpty();
        assertThat(context.getBean(StructureTemplates.class).ids()).contains("arc42");
    }

    /**
     * The meters are in a module of their own now, and nothing in the domain references it. If it were left out
     * of an instance the service would still start, still upload and still build - and report nothing at all.
     */
    @Test
    void anUpload_thenItIsMeasuredThroughTheMetricsAdapter() {
        MeterRegistry registry = context.getBean(MeterRegistry.class);
        UploadMetrics metrics = context.getBean(UploadMetrics.class);

        metrics.stored(DocumentationType.SYSTEM_DOCS, 4096, Duration.ofMillis(120));

        assertThat(registry.find("jeap.doc.upload").tag("result", "stored").timer())
                .describedAs("the upload timer should have been registered by the metrics adapter")
                .isNotNull();
        assertThat(registry.get("jeap.doc.upload").tag("result", "stored").timer().count()).isPositive();
    }

    /**
     * The same for the build meters, including the gauges the staleness alarms read - those are bound by the
     * adapter while the context starts, so they exist or they never will.
     */
    @Test
    void theBuildMeters_areBoundForEveryConfiguredSite() {
        MeterRegistry registry = context.getBean(MeterRegistry.class);

        assertThat(registry.find("jeap.doc.build.last.success.age").tag("site", Site.DEFAULT_SITE).gauge())
                .describedAs("the staleness gauge of the default site")
                .isNotNull();
        assertThat(registry.find("jeap.doc.build.request.age").tag("site", Site.DEFAULT_SITE).gauge()).isNotNull();
    }

    /**
     * The lock is the persistence adapter's, over the real table. That it lands there is what proves the port
     * reaches it - the domain would otherwise run every scheduled job on every instance, and nothing would say
     * so until two containers deleted the same rows.
     * <p>
     * That a second instance is refused is asserted where it is decided, in the adapter's own test: it needs a
     * second thread, because ShedLock lets the thread already holding a lock take it again.
     */
    @Test
    void exclusiveWork_reachesTheRealLockTable() {
        ExclusiveWork exclusiveWork = context.getBean(ExclusiveWork.class);
        JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);

        Optional<String> result = exclusiveWork.underLock("wiring-check", Duration.ofMinutes(5), () -> "once");

        assertThat(result).contains("once");
        assertThat(jdbcTemplate.queryForObject("select count(*) from shedlock where name = ?", Integer.class,
                "wiring-check"))
                .describedAs("the lock should have been written to the shedlock table of this database")
                .isOne();
    }

    /**
     * The whole chain from an upload to a build being asked for, across four modules: the web layer's service,
     * the persistence adapter that records the request, and the trigger between them.
     */
    @Test
    void anUpload_asksForABuildOfItsSite() {
        DocumentationBuildRequestRepository requests = context.getBean(DocumentationBuildRequestRepository.class);
        DocumentationBuildTrigger trigger = context.getBean(DocumentationBuildTrigger.class);

        trigger.requestBecauseOfUpload(Site.DEFAULT_SITE);

        assertThat(requests.pendingSince(Site.DEFAULT_SITE))
                .describedAs("the request should have reached the database")
                .isPresent();
    }

    /**
     * The service that the API talks to is there and wired to its own ports - the one bean whose absence would
     * make every upload a 500.
     */
    @Test
    void theUploadService_isWired() {
        assertThat(context.getBean(DocumentationUploadService.class)).isNotNull();
    }
}
