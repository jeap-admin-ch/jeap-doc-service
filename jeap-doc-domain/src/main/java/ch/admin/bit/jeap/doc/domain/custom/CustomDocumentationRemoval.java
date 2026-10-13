package ch.admin.bit.jeap.doc.domain.custom;

import ch.admin.bit.jeap.doc.domain.DocumentationBuildTrigger;
import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationRepository;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationStorage;
import ch.admin.bit.jeap.doc.domain.upload.InvalidUploadException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Removes documentation a team uploaded: one set, or a whole subject.
 * <p>
 * <b>Nothing is inferred here.</b> This class does not decide that a subject left the architecture model - a
 * component that publishes once and stays stable for a year is the normal case. It removes exactly what its
 * caller names.
 * <p>
 * That inference is {@link CustomDocumentationDeployments}', the one caller allowed to make it, and an
 * operator's through the admin API is the other. Keeping the decision out of here is what lets both exist
 * without either having to replicate what a removal is.
 * <p>
 * Removing a subject and removing its last set are <b>not the same thing</b>. A subject that has been
 * documented stays in the catalogue with no set until it is removed itself, which is what keeps the record of
 * what was once published.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomDocumentationRemoval {

    private final CustomDocumentationRepository documentation;
    private final CustomDocumentationStorage storage;
    private final DocumentationSites sites;
    private final DocumentationBuildTrigger buildTrigger;

    /**
     * Removes one set, and asks for the part that published it to be built.
     *
     * @return what was removed, or empty when there is no such set
     */
    public Optional<Removal> removeSet(CustomSetKey key) {
        requireConfiguredSite(key.site());
        Optional<CustomSet> set = documentation.find(key);
        if (set.isEmpty()) {
            return Optional.empty();
        }
        documentation.remove(key);
        forget(set.get().objectKey());
        return Optional.of(new Removal(1, askForABuild(key.site(), key.system())));
    }

    /**
     * Removes every set of one subject, and asks for the part that published them to be built.
     *
     * @return how many sets there were - which may be none for a subject that has been documented and is not
     *         any more - and whether a build was asked for
     */
    public Removal removeSubject(CustomSubject subject) {
        requireConfiguredSite(subject.site());
        List<String> objectKeys = documentation.of(subject.site(), subject.system()).sets().stream()
                .filter(set -> set.subject().equals(subject))
                .map(CustomSet::objectKey)
                .toList();
        int removed = documentation.removeSubject(subject);
        objectKeys.forEach(this::forget);
        return new Removal(removed, askForABuild(subject.site(), subject.system()));
    }

    /**
     * Removes the documentation of a component that was deployed and has left the architecture model.
     * <p>
     * <b>No build is asked for here.</b> One import can find several components of a system gone, and the
     * caller asks once per system when it is done - see {@link #askForABuildAfterDepartures}.
     * <p>
     * <b>Not the same decision twice.</b> The caller - {@link CustomDocumentationDeployments} - decided the
     * component is gone from a model it read a moment ago; the repository rechecks that the component is still
     * marked deployed, under its row's lock, before it removes anything and marks it undeployed.
     *
     * @return how many sets were removed - none where the recheck found the component no longer deployed
     */
    public int removeDepartedComponent(String site, String system, String component, Instant at) {
        requireConfiguredSite(site);
        return forgetAll(documentation.removeDepartedComponent(site, system, component, at));
    }

    /**
     * Removes the documentation of a system that was deployed and has no component left in the architecture
     * model - its own and that of all its components, never that of its libraries. Rechecked the way
     * {@link #removeDepartedComponent} is, and no build is asked for here either.
     *
     * @return how many sets were removed - none where the recheck found the system no longer departed
     */
    public int removeDepartedSystem(String site, String system, Instant at) {
        requireConfiguredSite(site);
        return forgetAll(documentation.removeDepartedSystem(site, system, at));
    }

    /**
     * Asks for the part of a system to be built once, after an import removed the documentation of the
     * system or of any number of its components.
     *
     * @return whether the build was asked for
     */
    public boolean askForABuildAfterDepartures(String site, String system) {
        return askForABuild(site, system);
    }

    /** Deletes the objects of what a departure removed, and answers how many there were. */
    private int forgetAll(List<String> objectKeys) {
        objectKeys.forEach(this::forget);
        return objectKeys.size();
    }

    /**
     * Removes everything documented for one system - its own documentation and that of all its components and
     * libraries - and asks for the part that published it to be built.
     * <p>
     * <b>One call, because it is one decision.</b> A system being taken off a site is not the same thing as
     * its subjects being removed one after another: done subject by subject, an upload landing halfway
     * through leaves the system half documented and nobody the wiser.
     *
     * @return how many sets there were, and whether a build was asked for
     */
    public Removal removeSystem(String site, String system) {
        requireConfiguredSite(site);
        List<String> objectKeys = documentation.removeSystem(site, system);
        objectKeys.forEach(this::forget);
        return new Removal(objectKeys.size(), askForABuild(site, system));
    }

    /**
     * What a removal took away, and whether the publication that takes the pages off the site was asked for.
     * <p>
     * <b>Both halves, because asking can fail and the caller has to hear it.</b> Nothing takes a page off a
     * part that is already published, so a removal whose trigger was lost leaves the documentation up until
     * something else asks - and a caller told the build was asked for would wait for a publication nobody
     * requested.
     *
     * @param setsRemoved how many sets there were
     * @param buildAsked  whether a build of the part that published them was asked for
     */
    public record Removal(int setsRemoved, boolean buildAsked) {
    }

    /**
     * Which sites exist is configuration, so a removal naming another one is refused rather than answered with
     * <i>there is no such set</i> - the same rule an upload follows.
     */
    private void requireConfiguredSite(String site) {
        if (sites.find(site).isEmpty()) {
            throw InvalidUploadException.unknownSite(site, sites.ids());
        }
    }

    /**
     * Removes the object of a set that is already gone from the database.
     * <p>
     * Guarded: the rows are what name an object, so once they are gone the object is unreachable either way,
     * and the nightly sweep of what nothing references takes what this could not.
     */
    private void forget(String objectKey) {
        try {
            storage.delete(objectKey);
        } catch (RuntimeException e) {
            log.warn("The documentation set is removed, but its bundle {} could not be deleted. The sweep of "
                     + "unreferenced objects takes it.", objectKey, e);
        }
    }

    /**
     * The pages are in a part that is already published, and nothing takes them off it. Losing the set changes
     * what the part hashes to, so the next build publishes without them - and one has to be asked for, exactly
     * as an upload asks.
     */
    private boolean askForABuild(String site, String system) {
        try {
            buildTrigger.requestBecauseOfUpload(site, system);
            return true;
        } catch (RuntimeException e) {
            log.error("The documentation is removed, but a build of {} on the site {} could not be asked for. "
                      + "The pages stay published until something else asks.", system, site, e);
            return false;
        }
    }
}
