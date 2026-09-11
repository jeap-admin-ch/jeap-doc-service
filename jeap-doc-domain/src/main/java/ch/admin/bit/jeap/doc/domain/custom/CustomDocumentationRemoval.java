package ch.admin.bit.jeap.doc.domain.custom;

import ch.admin.bit.jeap.doc.domain.DocumentationBuildTrigger;
import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationRepository;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationStorage;
import ch.admin.bit.jeap.doc.domain.upload.InvalidUploadException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Removes documentation a team uploaded: one set, or a whole subject.
 * <p>
 * <b>Nothing is inferred from silence.</b> A set is not removed because it is old, because its subject left
 * the architecture model, or because nothing has been uploaded for a while - a component that publishes once
 * and stays stable for a year is the normal case. It is removed when somebody says so.
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
