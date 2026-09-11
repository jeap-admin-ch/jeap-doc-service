package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureSnapshot;
import ch.admin.bit.jeap.doc.domain.custom.CustomSubject;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource;
import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which parts a site is cut into, and what each of them owns.
 */
class SystemSitePartitionTest {

    private static final SiteEnvironment DEV = new SiteEnvironment("dev", "DEV", "Development", 1, false, true);
    private static final SiteEnvironment PROD = new SiteEnvironment("prod", "PROD", "Production", 2, true, false);

    private static Site siteWith(SiteEnvironment... environments) {
        return new Site(Site.DEFAULT_SITE, "Documentation", null, null, null, "jeap", List.of(environments), true, true);
    }

    private static SystemSitePartition partitionOf(Map<String, List<String>> slugsByEnvironment) {
        return new SystemSitePartition(new StubModel(slugsByEnvironment), new NoCustomDocumentation());
    }

    /** A partition of a site where the given systems have documentation and no architecture model at all. */
    private static SystemSitePartition partitionOfDocumented(String... systems) {
        return new SystemSitePartition(new StubModel(Map.of()), new NoCustomDocumentation() {
            @Override
            public List<CustomSubject> subjectsOf(String site) {
                return Arrays.stream(systems)
                        .map(system -> new CustomSubject(site, SubjectKind.SYSTEM, system, null))
                        .toList();
            }
        });
    }

    /** The shell first, then one part per system, whatever order the environments answered in. */
    @Test
    void partsOf_thenTheShellComesFirstAndThenOnePartPerSystem() {
        SystemSitePartition partition = partitionOf(Map.of("dev", List.of("tariffs", "orders"),
                "prod", List.of("orders")));

        List<SitePart> parts = partition.partsOf(siteWith(DEV, PROD));

        assertThat(parts).extracting(SitePart::id)
                .containsExactly(SitePart.SHELL, "system-orders", "system-tariffs");
        assertThat(parts.getFirst().isShell()).isTrue();
    }

    /**
     * A system deployed on one stage only is one part all the same, carrying every environment of the site -
     * the tree of the stage it is not on is simply empty.
     */
    @Test
    void partsOf_thenAPartOwnsItsSystemInEveryEnvironment() {
        SystemSitePartition partition = partitionOf(Map.of("dev", List.of("orders"), "prod", List.of()));

        List<SitePart> parts = partition.partsOf(siteWith(DEV, PROD));

        assertThat(parts).filteredOn(part -> part.id().equals("system-orders")).singleElement()
                .satisfies(part -> assertThat(part.routePrefixes())
                        .describedAs("the main environment owns the site root, so it carries no prefix")
                        .containsExactly("/dev/systems/orders/", "/systems/orders/"));
    }

    /** The same system on two stages is one part, not two. */
    @Test
    void partsOf_thenASystemOnTwoStagesIsOnePart() {
        SystemSitePartition partition = partitionOf(Map.of("dev", List.of("orders"), "prod", List.of("orders")));

        assertThat(partition.partsOf(siteWith(DEV, PROD))).hasSize(2);
    }

    /** An environment with no architecture repository contributes no part, and no build fails over it. */
    @Test
    void partsOf_whenNothingIsConfigured_thenOnlyTheShellIsAPart() {
        SystemSitePartition partition = new SystemSitePartition(new StubModel(Map.of()),
                new NoCustomDocumentation());

        assertThat(partition.partsOf(siteWith(DEV, PROD))).extracting(SitePart::id)
                .containsExactly(SitePart.SHELL);
    }

    /**
     * A system that has been documented and is in no architecture model is a part all the same. Without it
     * nothing would ask for that part to be built, an operator could not force it, and the sweep of departed
     * parts would take what one upload managed to publish.
     */
    @Test
    void partsOf_thenASystemThatIsOnlyDocumentedIsAPartToo() {
        SystemSitePartition partition = partitionOfDocumented("nobody-has-deployed-this");

        assertThat(partition.partsOf(siteWith(DEV, PROD))).extracting(SitePart::id)
                .containsExactly(SitePart.SHELL, "system-nobody-has-deployed-this");
    }

    @Test
    void partsOf_thenASystemThatIsBothDeployedAndDocumentedIsOnePart() {
        SystemSitePartition partition = new SystemSitePartition(
                new StubModel(Map.of("dev", List.of("orders"))), new NoCustomDocumentation() {
            @Override
            public List<CustomSubject> subjectsOf(String site) {
                return List.of(new CustomSubject(site, SubjectKind.SYSTEM, "orders", null),
                        new CustomSubject(site, SubjectKind.COMPONENT, "orders", "orders-intake"));
            }
        });

        assertThat(partition.partsOf(siteWith(DEV, PROD))).extracting(SitePart::id)
                .describedAs("a component's documentation belongs to its system's part")
                .containsExactly(SitePart.SHELL, "system-orders");
    }

    /**
     * What a part owns follows from its identifier alone, so a request can be resolved without reading the
     * landscape - which is what serving does on every request that is not answered from the cache.
     */
    @Test
    void partOf_thenAPartIsDerivedFromItsIdentifierWithoutTheModel() {
        SystemSitePartition partition = new SystemSitePartition(new StubModel(Map.of()) {
            @Override
            public List<String> systemSlugsOf(String environment) {
                throw new AssertionError("the model must not be read to resolve a request");
            }
        }, new NoCustomDocumentation() {
            @Override
            public List<CustomSubject> subjectsOf(String site) {
                throw new AssertionError("nor the documentation");
            }
        });

        Optional<SitePart> part = partition.partOf(siteWith(DEV, PROD), "system-orders");

        assertThat(part).isPresent();
        assertThat(part.get().routePrefixes()).containsExactly("/dev/systems/orders/", "/systems/orders/");
        assertThat(partition.partOf(siteWith(DEV, PROD), SitePart.SHELL)).contains(partition.shellOf(siteWith(DEV, PROD)));
    }

    /** An identifier this partition would never produce is not a part, so nothing is served for it. */
    @Test
    void partOf_whenTheIdentifierIsNotOneOfItsOwn_thenThereIsNoPart() {
        SystemSitePartition partition = partitionOf(Map.of());
        Site site = siteWith(DEV, PROD);

        assertThat(partition.partOf(site, "orders")).isEmpty();
        assertThat(partition.partOf(site, "system-")).isEmpty();
        assertThat(partition.partOf(site, null)).isEmpty();
    }

    /** A model that answers slugs per environment, and nothing else. */
    private static class StubModel implements ArchitectureModelSource {

        private final Map<String, List<String>> slugsByEnvironment;

        StubModel(Map<String, List<String>> slugsByEnvironment) {
            this.slugsByEnvironment = slugsByEnvironment;
        }

        @Override
        public boolean isConfiguredFor(String environment) {
            return slugsByEnvironment.containsKey(environment);
        }

        @Override
        public Optional<String> sourceUrlOf(String environment) {
            return Optional.empty();
        }

        @Override
        public Optional<Instant> lastSuccessfulImportAt(String environment) {
            return Optional.empty();
        }

        @Override
        public ArchitectureSnapshot read(String environment) {
            return ArchitectureSnapshot.empty();
        }

        @Override
        public List<String> systemSlugsOf(String environment) {
            return slugsByEnvironment.getOrDefault(environment, List.of());
        }
    }
}
