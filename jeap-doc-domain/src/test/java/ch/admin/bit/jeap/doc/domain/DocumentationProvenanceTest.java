package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportState;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureSnapshot;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ImportOutcome;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportRepository;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource;
import ch.admin.bit.jeap.doc.domain.template.GenerationContext;
import ch.admin.bit.jeap.doc.domain.template.StructureChapter;
import ch.admin.bit.jeap.doc.domain.template.StructureTemplate;
import ch.admin.bit.jeap.doc.domain.template.StructureTemplates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the doc service says about itself in public.
 * <p>
 * The assertion that matters most here is the one about what is <b>not</b> in the answer. The rows this is
 * assembled from carry the instance that ran a build, the prefix its output lies under and the reason a run
 * failed - the last of which is built from what an upstream answered and quotes its host - and the site these
 * facts are printed on is served to anyone who can reach the service.
 */
class DocumentationProvenanceTest {

    private static final Instant NOW = Instant.parse("2026-09-03T07:30:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("Europe/Zurich"));

    /**
     * What an import state row carries that a public page may never repeat: the reason a run failed, which is
     * built from what the upstream answered and quotes its host and its path.
     */
    private static final String FAILURE_REASON =
            "The architecture repository https://archrepo.internal.admin.ch/docs-api could not be reached";

    private InMemoryImports imports;
    private StubModel architectureModel;
    private DocumentationProvenance provenance;

    @BeforeEach
    void setUp() {
        imports = new InMemoryImports();
        architectureModel = new StubModel(Set.of("prod"));
        provenance = provenance(new SiteProperties());
    }

    private DocumentationProvenance provenance(SiteProperties siteProperties) {
        return new DocumentationProvenance(new DocumentationSites(siteProperties), imports,
                architectureModel, new StructureTemplates(List.of(new SilentTemplate())),
                new BuildProperties(), new ArchitectureImportProperties(), CLOCK);
    }

    @Test
    void of_whenNoSuchSiteIsConfigured_thenNothing() {
        assertThat(provenance.of("governance", "1.2.3", NOW)).isEmpty();
    }

    @Test
    void of_thenTheSiteAndItsEnvironmentsAsConfigured() {
        DocumentationFacts facts = provenance.of(Site.DEFAULT_SITE, "1.2.3", NOW).orElseThrow();

        assertThat(facts.service().version()).isEqualTo("1.2.3");
        assertThat(facts.service().generatedAt()).isEqualTo(NOW);
        assertThat(facts.site().title()).isEqualTo("Documentation");
        assertThat(facts.site().templates()).containsExactly("System Architecture");
        assertThat(facts.site().retainedPublications()).isEqualTo(3);
        assertThat(facts.environments()).extracting(DocumentationFacts.EnvironmentFacts::id)
                .containsExactly("dev", "ref", "abn", "prod");
        assertThat(facts.environments()).filteredOn(DocumentationFacts.EnvironmentFacts::main)
                .extracting(DocumentationFacts.EnvironmentFacts::id).containsExactly("prod");
    }

    /**
     * An environment with no architecture repository is a legitimate one, and the page has to say so rather
     * than leave a reader wondering why that tree has no systems.
     */
    @Test
    void of_thenOnlyTheEnvironmentsWithAnArchitectureRepositorySayTheyHaveOne() {
        DocumentationFacts facts = provenance.of(Site.DEFAULT_SITE, null, NOW).orElseThrow();

        assertThat(facts.environments())
                .filteredOn(DocumentationFacts.EnvironmentFacts::modelConfigured)
                .extracting(DocumentationFacts.EnvironmentFacts::id).containsExactly("prod");
    }

    @Test
    void of_thenTheImportStateOfEachEnvironment() {
        imports.save(new ArchitectureImportState("prod", ArchitectureImportKind.MODEL, "hash", null, true, 12,
                NOW.minus(Duration.ofMinutes(45)), NOW.minus(Duration.ofMinutes(45)), ImportOutcome.UNCHANGED,
                null));

        DocumentationFacts facts = provenance.of(Site.DEFAULT_SITE, null, NOW).orElseThrow();

        DocumentationFacts.EnvironmentFacts prod = facts.environments().stream()
                .filter(environment -> environment.id().equals("prod")).findFirst().orElseThrow();
        assertThat(prod.lastImportOutcome()).isEqualTo(ImportOutcome.UNCHANGED);
        assertThat(prod.lastImportAt()).isEqualTo(NOW.minus(Duration.ofMinutes(45)));
        assertThat(prod.importIsBehind(NOW)).isFalse();
    }


    /** The same measure the build's own warning uses: two hours by default, so one missed run is tolerated. */
    @Test
    void importIsBehind_whenTheRepositoryHasNotBeenReadForLongerThanStaleAfter_thenTrue() {
        imports.save(new ArchitectureImportState("prod", ArchitectureImportKind.MODEL, "hash", null, true, 12,
                NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofHours(3)), ImportOutcome.REPLACED, null));

        DocumentationFacts facts = provenance.of(Site.DEFAULT_SITE, null, NOW).orElseThrow();

        assertThat(facts.environments()).filteredOn(environment -> environment.id().equals("prod"))
                .singleElement().satisfies(prod -> assertThat(prod.importIsBehind(NOW)).isTrue());
        assertThat(facts.environments()).filteredOn(environment -> environment.id().equals("dev"))
                .singleElement().describedAs("an environment that reads no model is never behind")
                .satisfies(dev -> assertThat(dev.importIsBehind(NOW)).isFalse());
    }

    @Test
    void of_thenTheSchedulesAndWhenTheyFireNext() {
        DocumentationFacts facts = provenance.of(Site.DEFAULT_SITE, null, NOW).orElseThrow();

        assertThat(facts.schedules().publication()).isEqualTo("0 5 6-20 * * *");
        assertThat(facts.schedules().publicationAt()).isEqualTo(Instant.parse("2026-09-03T08:05:00Z"));
        assertThat(facts.schedules().import_()).isEqualTo("0 45 5-19 * * *");
        assertThat(facts.schedules().importAt()).isEqualTo(Instant.parse("2026-09-03T07:45:00Z"));
    }

    /**
     * A site none of whose environments reads an architecture model is not fed by the import at all, and a
     * schedule on its page that has nothing to do with it would only be read as one that does.
     */
    @Test
    void of_whenNoEnvironmentReadsAModel_thenTheImportScheduleIsNotOnThePage() {
        architectureModel = new StubModel(Set.of());
        provenance = provenance(new SiteProperties());

        DocumentationFacts facts = provenance.of(Site.DEFAULT_SITE, null, NOW).orElseThrow();

        assertThat(facts.schedules().import_()).isNull();
        assertThat(facts.schedules().importAt()).isNull();
        assertThat(facts.schedules().publication()).isNotNull();
    }

    /**
     * <b>The disclosure contract.</b> The page these facts are printed on is served without authentication,
     * and the rows they are assembled from carry things a reader may not have - most sharply the reason an
     * import run failed, which is built from what the upstream answered and quotes its host and its path.
     * <p>
     * Asserted as <b>the whole rendering against an expected one</b> rather than as an absence of particular
     * strings. An absence test only ever catches the leaks somebody thought of: it passes for a field nobody
     * predicted, and it passes vacuously for a value this service could not reach in the first place. A field
     * added to any of these records shows up here as a difference, and whoever added it then has to decide
     * whether it may be published - which is the decision this test exists to force.
     */
    @Test
    void of_thenTheRenderingIsExactlyTheseFieldsAndNoOthers() {
        imports.save(new ArchitectureImportState("prod", ArchitectureImportKind.MODEL, "a-content-hash", null,
                false, 12, NOW, NOW.minus(Duration.ofHours(1)), ImportOutcome.FAILED, FAILURE_REASON));

        DocumentationFacts facts = provenance.of(Site.DEFAULT_SITE, "1.2.3", NOW).orElseThrow();

        assertThat(facts.toString()).isEqualTo("DocumentationFacts["
                + "service=Service[version=1.2.3, generatedAt=2026-09-03T07:30:00Z], "
                + "site=SiteFacts[id=default, title=Documentation, templates=[System Architecture], "
                + "architectureModelRequired=true, publishOnUpload=true, retainedPublications=3], "
                + "environments=["
                + "EnvironmentFacts[id=dev, label=Development, main=false, latest=true, modelConfigured=false, "
                + "lastImportAt=null, lastImportOutcome=null, staleAfter=PT2H], "
                + "EnvironmentFacts[id=ref, label=Reference, main=false, latest=false, modelConfigured=false, "
                + "lastImportAt=null, lastImportOutcome=null, staleAfter=PT2H], "
                + "EnvironmentFacts[id=abn, label=Acceptance, main=false, latest=false, modelConfigured=false, "
                + "lastImportAt=null, lastImportOutcome=null, staleAfter=PT2H], "
                + "EnvironmentFacts[id=prod, label=Production, main=true, latest=false, modelConfigured=true, "
                + "lastImportAt=2026-09-03T06:30:00Z, lastImportOutcome=FAILED, staleAfter=PT2H]"
                + "], "
                + "schedules=Schedules[publication=0 5 6-20 * * *, publicationAt=2026-09-03T08:05:00Z, "
                + "import_=0 45 5-19 * * *, importAt=2026-09-03T07:45:00Z]]");
    }

    /**
     * And the one value on that row which is the reason the contract exists, called out by name so that the
     * test above reads as a shape check and this one as the rule.
     */
    @Test
    void of_thenWhyAnImportFailedIsNotCarried() {
        imports.save(new ArchitectureImportState("prod", ArchitectureImportKind.MODEL, "hash", null, false, 0,
                NOW, null, ImportOutcome.FAILED, FAILURE_REASON));

        String rendered = provenance.of(Site.DEFAULT_SITE, "1.2.3", NOW).orElseThrow().toString();

        assertThat(rendered).doesNotContain(FAILURE_REASON, "archrepo.internal.admin.ch",
                "could not be reached");
        assertThat(rendered).describedAs("that it failed is publishable; why it failed is not")
                .contains("lastImportOutcome=FAILED");
    }

    /** An architecture repository configured for some environments and not for others. */
    private record StubModel(Set<String> configured) implements ArchitectureModelSource {

        @Override
        public boolean isConfiguredFor(String environment) {
            return configured.contains(environment);
        }

        @Override
        public Optional<String> sourceUrlOf(String environment) {
            return Optional.of("https://archrepo.internal.admin.ch/docs-api");
        }

        @Override
        public Optional<Instant> lastSuccessfulImportAt(String environment) {
            return Optional.empty();
        }

        @Override
        public ArchitectureSnapshot read(String environment) {
            return ArchitectureSnapshot.empty();
        }
    }

    private static final class InMemoryImports implements ArchitectureImportRepository {

        private final Map<String, ArchitectureImportState> states = new LinkedHashMap<>();

        @Override
        public ArchitectureImportState state(String environment, ArchitectureImportKind kind) {
            return states.getOrDefault(environment + "-" + kind,
                    ArchitectureImportState.none(environment, kind));
        }

        @Override
        public List<ArchitectureImportState> states() {
            return new ArrayList<>(states.values());
        }

        @Override
        public void save(ArchitectureImportState state) {
            states.put(state.environment() + "-" + state.kind(), state);
        }
    }

    /** A template with nothing to write, which is a legitimate template. */
    private static final class SilentTemplate implements StructureTemplate {

        @Override
        public String id() {
            return "arc42";
        }

        @Override
        public String systemPathSegment() {
            return "system-architecture";
        }

        @Override
        public String systemLabel() {
            return "System Architecture";
        }

        @Override
        public String componentPathSegment() {
            return "component-architecture";
        }

        @Override
        public String componentLabel() {
            return "Component Architecture";
        }

        @Override
        public List<StructureChapter> chapters() {
            return List.of(StructureChapter.numbered(1, "1-intro", "Introduction and Goals"));
        }

        @Override
        public void writeSystem(DocumentedSystem system, GenerationContext context, Path systemDirectory) {
            // nothing: what is under test is what the service says about itself
        }
    }
}
