package ch.admin.bit.jeap.doc.domain.custom;

import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SiteProperties;
import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureSnapshot;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static ch.admin.bit.jeap.doc.domain.custom.SubjectDeployment.State.AWAITING_DEPLOYMENT;
import static ch.admin.bit.jeap.doc.domain.custom.SubjectDeployment.State.DEPLOYED;
import static ch.admin.bit.jeap.doc.domain.custom.SubjectDeployment.State.UNDEPLOYED;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What happens to the documentation of a system or component as it is deployed and leaves the architecture
 * model again.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CustomDocumentationDeploymentsTest {

    private static final Instant NOW = Instant.parse("2026-10-14T08:00:00Z");
    private static final String SITE = Site.DEFAULT_SITE;

    @Mock
    private CustomDocumentationRepository documentation;
    @Mock
    private CustomDocumentationRemoval removal;

    private final StubModel model = new StubModel();

    @BeforeEach
    void defaults() {
        when(removal.removeDepartedSystem(any(), any(), any())).thenReturn(1);
        when(removal.removeDepartedComponent(any(), any(), any(), any())).thenReturn(1);
        when(documentation.markDeployed(any(), any(), any(), any())).thenReturn(true);
        model.imported("dev", system("orders", "orders-intake"));
    }

    /** Documentation uploaded before its component is deployed waits for it; the import marks it deployed. */
    @Test
    void follow_whenAnAwaitedComponentIsInTheModel_thenItIsMarkedDeployed() {
        states(system("orders", AWAITING_DEPLOYMENT), component("orders", "orders-intake", AWAITING_DEPLOYMENT));

        deployments().followTheArchitectureOf("dev");

        verify(documentation).markDeployed(SITE, "orders", null, NOW);
        verify(documentation).markDeployed(SITE, "orders", "orders-intake", NOW);
        verifyNoInteractions(removal);
    }

    /** Never having been seen deployed, a component's absence from the model says nothing about it. */
    @Test
    void follow_whenAnAwaitedComponentIsNotInTheModel_thenItIsLeftAlone() {
        states(system("orders", DEPLOYED), component("orders", "orders-billing", AWAITING_DEPLOYMENT));

        deployments().followTheArchitectureOf("dev");

        verify(documentation, never()).markDeployed(any(), any(), anyString(), any());
        verifyNoInteractions(removal);
    }

    /** An undeployed component that is deployed again is deployed again. */
    @Test
    void follow_whenAnUndeployedComponentIsBackInTheModel_thenItIsMarkedDeployed() {
        states(system("orders", DEPLOYED), component("orders", "orders-intake", UNDEPLOYED));

        deployments().followTheArchitectureOf("dev");

        verify(documentation).markDeployed(SITE, "orders", "orders-intake", NOW);
        verifyNoInteractions(removal);
    }

    /** Undeployed, its documentation is gone already, and staying out of the model removes nothing more. */
    @Test
    void follow_whenAnUndeployedComponentStaysOutOfTheModel_thenNothingHappens() {
        states(system("orders", DEPLOYED), component("orders", "orders-billing", UNDEPLOYED));

        deployments().followTheArchitectureOf("dev");

        verify(documentation, never()).markDeployed(any(), any(), anyString(), any());
        verifyNoInteractions(removal);
    }

    @Test
    void follow_whenADeployedComponentHasLeftTheModel_thenItsDocumentationIsRemoved() {
        states(system("orders", DEPLOYED), component("orders", "orders-intake", DEPLOYED),
                component("orders", "orders-billing", DEPLOYED));

        deployments().followTheArchitectureOf("dev");

        verify(removal).removeDepartedComponent(SITE, "orders", "orders-billing", NOW);
        verify(removal, never()).removeDepartedComponent(SITE, "orders", "orders-intake", NOW);
        verify(removal, never()).removeDepartedSystem(any(), any(), any());
        verify(removal).askForABuildAfterDepartures(SITE, "orders");
    }

    /** However many components of a system leave in one pass, its part is built once. */
    @Test
    void follow_whenSeveralComponentsOfASystemHaveLeft_thenItIsBuiltOnce() {
        states(system("orders", DEPLOYED), component("orders", "orders-intake", DEPLOYED),
                component("orders", "orders-billing", DEPLOYED), component("orders", "orders-shipping", DEPLOYED));

        deployments().followTheArchitectureOf("dev");

        verify(removal).removeDepartedComponent(SITE, "orders", "orders-billing", NOW);
        verify(removal).removeDepartedComponent(SITE, "orders", "orders-shipping", NOW);
        verify(removal, times(1)).askForABuildAfterDepartures(SITE, "orders");
    }

    /** A removal the recheck refused removed nothing, and there is nothing to publish. */
    @Test
    void follow_whenTheRecheckRemovesNothing_thenNoBuildIsAskedFor() {
        when(removal.removeDepartedComponent(any(), any(), any(), any())).thenReturn(0);
        states(system("orders", DEPLOYED), component("orders", "orders-billing", DEPLOYED));

        deployments().followTheArchitectureOf("dev");

        verify(removal, never()).askForABuildAfterDepartures(any(), any());
    }

    /** A system with no component left has nothing deployed, and everything documented for it goes. */
    @Test
    void follow_whenADeployedSystemHasNoComponentLeft_thenEverythingOfItIsRemoved() {
        model.imported("dev", system("orders", "orders-intake"), system("catalog"));
        states(system("catalog", DEPLOYED), component("catalog", "catalog-search", DEPLOYED));

        deployments().followTheArchitectureOf("dev");

        verify(removal).removeDepartedSystem(SITE, "catalog", NOW);
        // Its components went with it, and are not removed a second time.
        verify(removal, never()).removeDepartedComponent(any(), any(), any(), any());
    }

    @Test
    void follow_whenADeployedSystemHasLeftTheModel_thenEverythingOfItIsRemoved() {
        states(system("catalog", DEPLOYED));

        deployments().followTheArchitectureOf("dev");

        verify(removal).removeDepartedSystem(SITE, "catalog", NOW);
    }

    /** A component on its way keeps its system; the components that left still lose their documentation. */
    @Test
    void follow_whenASystemWithNoComponentLeftHasAComponentAwaitingDeployment_thenOnlyTheDepartedComponentsGo() {
        states(system("catalog", DEPLOYED), component("catalog", "catalog-search", DEPLOYED),
                component("catalog", "catalog-billing", AWAITING_DEPLOYMENT));

        deployments().followTheArchitectureOf("dev");

        verify(removal, never()).removeDepartedSystem(any(), any(), any());
        verify(removal).removeDepartedComponent(SITE, "catalog", "catalog-search", NOW);
        verify(removal, never()).removeDepartedComponent(SITE, "catalog", "catalog-billing", NOW);
    }

    /** A system in the model without components has not been deployed, so it is not marked deployed. */
    @Test
    void follow_whenAnAwaitedSystemIsInTheModelWithoutComponents_thenItKeepsAwaitingDeployment() {
        model.imported("dev", system("orders", "orders-intake"), system("catalog"));
        states(system("catalog", AWAITING_DEPLOYMENT));

        deployments().followTheArchitectureOf("dev");

        verify(documentation, never()).markDeployed(any(), any(), any(), any());
    }

    /** A subject deployed on any environment of the site is in its model. */
    @Test
    void follow_whenTheComponentIsGoneFromOneEnvironmentButNotFromAnother_thenNothingIsRemoved() {
        model.imported("prod", system("orders", "orders-intake", "orders-billing"));
        states(system("orders", DEPLOYED), component("orders", "orders-billing", DEPLOYED));

        deployments().followTheArchitectureOf("dev");

        verifyNoInteractions(removal);
    }

    /** Without every environment's model, a subject missing from the ones there are may be in the one missing. */
    @Test
    void follow_whenAnEnvironmentOfTheSiteHasNeverBeenImported_thenNothingIsRemoved() {
        model.configured.add("prod");
        states(system("orders", DEPLOYED), component("orders", "orders-billing", DEPLOYED));

        deployments().followTheArchitectureOf("dev");

        verifyNoInteractions(removal);
    }

    /** An empty landscape is an upstream serving nothing, not every system having left at once. */
    @Test
    void follow_whenTheModelIsEmpty_thenNothingIsRemoved() {
        model.imported("dev");
        states(system("orders", DEPLOYED), component("orders", "orders-intake", DEPLOYED));

        deployments().followTheArchitectureOf("dev");

        verifyNoInteractions(removal);
    }

    /** Marking deployed does not need a whole model: it only ever keeps documentation. */
    @Test
    void follow_whenAnEnvironmentHasNeverBeenImported_thenAwaitedSubjectsInTheModelAreStillMarkedDeployed() {
        model.configured.add("prod");
        states(component("orders", "orders-intake", AWAITING_DEPLOYMENT));

        deployments().followTheArchitectureOf("dev");

        verify(documentation).markDeployed(SITE, "orders", "orders-intake", NOW);
    }

    @Test
    void follow_whenTheEnvironmentIsNotOneOfTheSite_thenNothingIsRead() {
        deployments().followTheArchitectureOf("elsewhere");

        verify(documentation, never()).deploymentsOf(any());
    }

    private CustomDocumentationDeployments deployments() {
        return new CustomDocumentationDeployments(new DocumentationSites(new SiteProperties()), documentation,
                model, removal, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void states(SubjectDeployment... deployments) {
        when(documentation.deploymentsOf(SITE)).thenReturn(List.of(deployments));
    }

    private static SubjectDeployment system(String system, SubjectDeployment.State state) {
        return new SubjectDeployment(SITE, system, null, state);
    }

    private static SubjectDeployment component(String system, String component, SubjectDeployment.State state) {
        return new SubjectDeployment(SITE, system, component, state);
    }

    private static DocumentedSystem system(String slug, String... componentSlugs) {
        List<DocumentedComponent> components = Arrays.stream(componentSlugs)
                .map(name -> new DocumentedComponent(name, name, null, null, null, null, null, List.of(), null,
                        null, null))
                .toList();
        return new DocumentedSystem(slug, slug, null, List.of(), null, components, List.of(), List.of());
    }

    private static class StubModel implements ArchitectureModelSource {

        private final Set<String> configured = new HashSet<>();
        private final Map<String, List<DocumentedSystem>> imported = new HashMap<>();

        void imported(String environment, DocumentedSystem... systems) {
            configured.add(environment);
            imported.put(environment, List.of(systems));
        }

        @Override
        public boolean isConfiguredFor(String environment) {
            return configured.contains(environment);
        }

        @Override
        public Optional<String> sourceUrlOf(String environment) {
            return Optional.empty();
        }

        @Override
        public Optional<Instant> lastSuccessfulImportAt(String environment) {
            return imported.containsKey(environment) ? Optional.of(NOW) : Optional.empty();
        }

        @Override
        public ArchitectureSnapshot read(String environment) {
            return imported.containsKey(environment)
                    ? new ArchitectureSnapshot(ArchitectureModel.of(imported.get(environment)), NOW)
                    : ArchitectureSnapshot.empty();
        }

        @Override
        public List<String> systemSlugsOf(String environment) {
            return imported.getOrDefault(environment, List.of()).stream().map(DocumentedSystem::slug).toList();
        }
    }
}
