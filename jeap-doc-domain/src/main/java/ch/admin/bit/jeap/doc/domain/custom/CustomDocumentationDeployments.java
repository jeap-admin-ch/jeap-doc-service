package ch.admin.bit.jeap.doc.domain.custom;

import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SiteEnvironment;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Follows the deployment of what documentation was uploaded for, and removes the documentation of what was
 * deployed and has left.
 * <p>
 * Run after every successful import of an environment's architecture model - also one that found the model
 * unchanged, because documentation uploaded since the last import has to be picked up all the same. For every
 * site that environment belongs to, it compares the {@link SubjectDeployment}s against the site's model:
 * <ul>
 * <li>A system or component <b>awaiting deployment</b> or <b>undeployed</b> that is in the model becomes
 * deployed. Documentation is uploaded before what it documents is deployed often enough, and absence from the
 * model says nothing about a subject awaiting deployment until it has been seen there once.</li>
 * <li>A <b>deployed component</b> that is no longer in the model becomes undeployed, and loses its
 * documentation.</li>
 * <li>A <b>deployed system</b> that has no component left in the model becomes undeployed with all its
 * components, and loses its own documentation and that of its components. A system counts as deployed only
 * while it has a component in the model, and it stays while one of its components is awaiting deployment -
 * then only its departed components lose their documentation.</li>
 * <li>The documentation of a <b>library</b> is never removed: a library is deployed nowhere, so no deployment
 * says anything about it.</li>
 * </ul>
 * <p>
 * <b>The model of a site is the union of its environments.</b> A site may document several environments, each
 * with an architecture repository of its own: a subject is in the model when any of them has it, and gone when
 * none of them has it any more.
 * <p>
 * <b>Nothing is removed without a whole model to compare against.</b> Every environment of the site that has
 * an architecture repository has to have been imported successfully, and their models together must not be
 * empty - the way an import that failed and stored nothing must never read as every system having left at
 * once. A page can be built again once the landscape is right; documentation a team uploaded cannot.
 * <p>
 * <b>Once judged, a departure is acted on immediately, with no grace period.</b> That is safe because the
 * architecture repository's own undeployed-component signal is a deliberate, timestamped event from the
 * deployment log - not inferred from a source going quiet - so it does not flicker the way liveness-based
 * presence can. A real decommission should not leave stale documentation lingering.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomDocumentationDeployments {

    private final DocumentationSites sites;
    private final CustomDocumentationRepository documentation;
    private final ArchitectureModelSource architectureModel;
    private final CustomDocumentationRemoval removal;
    private final Clock clock;

    /**
     * Compares the deployment state of every site documenting this environment against its model, now that
     * the environment's model was imported successfully.
     */
    public void followTheArchitectureOf(String environment) {
        for (Site site : sites.all()) {
            if (site.environments().stream().map(SiteEnvironment::id).anyMatch(environment::equals)) {
                try {
                    follow(site);
                } catch (RuntimeException e) {
                    log.warn("The deployment of the documented subjects of the site {} could not be followed. The "
                             + "next import of its architecture model tries again.", site.id(), e);
                }
            }
        }
    }

    private void follow(Site site) {
        List<String> environments = site.environments().stream()
                .map(SiteEnvironment::id)
                .filter(architectureModel::isConfiguredFor)
                .toList();
        if (environments.isEmpty()) {
            return;
        }
        Map<String, Set<String>> deployed = deployedComponentsOf(environments);
        // Ever succeeded, not recently: an import stuck failing for good is not this method's job to catch,
        // and already pages someone - see jeap_doc_architecture_import_last_success_age_seconds.
        boolean judgeDepartures = !deployed.isEmpty() && environments.stream()
                .allMatch(environment -> architectureModel.lastSuccessfulImportAt(environment).isPresent());
        Instant now = clock.instant();
        List<SubjectDeployment> subjects = documentation.deploymentsOf(site.id());

        // A component awaiting its first deployment keeps its system: the system is not gone, it is on its way.
        Set<String> awaitedSystems = subjects.stream()
                .filter(subject -> !subject.isSystem())
                .filter(subject -> subject.state() == SubjectDeployment.State.AWAITING_DEPLOYMENT)
                .map(SubjectDeployment::system)
                .collect(Collectors.toSet());
        // The systems that lost documentation in this pass: each is built once at the end, however many of
        // its components left.
        Set<String> changedSystems = new LinkedHashSet<>();
        Set<String> departedSystems = new HashSet<>();
        for (SubjectDeployment system : subjects) {
            if (!system.isSystem()) {
                continue;
            }
            boolean present = deployed.containsKey(system.system());
            if (!present && awaitedSystems.contains(system.system())) {
                continue;
            }
            if (follow(site, system, present, judgeDepartures, now) > 0) {
                departedSystems.add(system.system());
                changedSystems.add(system.system());
            }
        }
        for (SubjectDeployment component : subjects) {
            // The components of a system that was removed went with it.
            if (component.isSystem() || departedSystems.contains(component.system())) {
                continue;
            }
            boolean present = deployed.getOrDefault(component.system(), Set.of()).contains(component.component());
            if (follow(site, component, present, judgeDepartures, now) > 0) {
                changedSystems.add(component.system());
            }
        }
        changedSystems.forEach(system -> removal.askForABuildAfterDepartures(site.id(), system));
    }

    /**
     * Moves one subject on, and answers how many of its documentation sets were removed. The repository logs
     * each removal it makes; one its recheck refused removes nothing and is not logged.
     */
    private int follow(Site site, SubjectDeployment subject, boolean present, boolean judgeDepartures,
                       Instant now) {
        if (subject.state() != SubjectDeployment.State.DEPLOYED && present) {
            if (documentation.markDeployed(site.id(), subject.system(), subject.component(), now)) {
                log.info("{} is deployed; its documentation on the site {} is removed once it leaves the "
                         + "architecture model.", describe(subject), site.id());
            }
            return 0;
        }
        if (subject.state() == SubjectDeployment.State.DEPLOYED && !present && judgeDepartures) {
            return subject.isSystem()
                    ? removal.removeDepartedSystem(site.id(), subject.system(), now)
                    : removal.removeDepartedComponent(site.id(), subject.system(), subject.component(), now);
        }
        return 0;
    }

    /**
     * The slugs of the components of every system with at least one component, in any of these environments.
     * A system without a component is left out: it has nothing deployed.
     */
    private Map<String, Set<String>> deployedComponentsOf(List<String> environments) {
        Map<String, Set<String>> deployed = new HashMap<>();
        for (String environment : environments) {
            for (DocumentedSystem system : architectureModel.read(environment).model().systems()) {
                if (system.components().isEmpty()) {
                    continue;
                }
                deployed.computeIfAbsent(system.slug(), slug -> new HashSet<>())
                        .addAll(system.components().stream().map(DocumentedComponent::slug)
                                .collect(Collectors.toSet()));
            }
        }
        return deployed;
    }

    private static String describe(SubjectDeployment subject) {
        return subject.isSystem()
                ? "The system " + subject.system()
                : "The component " + subject.component() + " of the system " + subject.system();
    }
}
