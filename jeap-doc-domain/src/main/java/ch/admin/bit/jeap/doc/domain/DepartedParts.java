package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import ch.admin.bit.jeap.doc.domain.port.ExclusiveWork;
import ch.admin.bit.jeap.doc.domain.port.PublishedPart;
import ch.admin.bit.jeap.doc.domain.port.SitePublicationStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Removes what a part that has left the site is still publishing.
 * <p>
 * A part exists because the partition says so, and the system axis reads that from the architecture model. So a
 * system that is decommissioned takes its part with it - and nothing else follows: no build is asked for, none
 * runs, the retention only ever offers what a <i>successful</i> build published, and the nightly clean-up spares
 * the newest succeeded row of every part whatever its age. The objects, the rows and the pages would therefore
 * stay for ever, and the site would go on serving the documentation of a system that is gone while the systems
 * index no longer lists it. The bucket is no fallback: nothing under the published sites is expired by age, and
 * for good reason - see {@code docs/operating-the-bucket.md}.
 * <p>
 * <b>Slow on purpose, and guarded twice.</b> A part is removed only once it has published nothing for
 * {@code jeap.doc.build.departed-part-retention}, and a site whose partition produces nothing but the shell is
 * left alone entirely: an import that failed and stored an empty landscape would otherwise read as every system
 * of that site having been decommissioned at once. Together, a landscape that goes wrong has to stay wrong for a
 * quarter of a year before anything is deleted - and an operator who knows a system is gone need not wait, which
 * is what {@link #removeNow} is for.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DepartedParts {

    /** The lock of the nightly sweep. One instance walks the sites; the per-part work takes the part's lock. */
    static final String LOCK = "documentationDepartedParts";

    /** How long the lock of the sweep survives an instance that dies holding it. */
    private static final Duration LEASE = Duration.ofMinutes(30);

    private final DocumentationSites sites;
    private final SitePartition partition;
    private final DocumentationBuildRepository builds;
    private final SitePublicationStorage publication;
    private final BuildProperties properties;
    private final ExclusiveWork exclusiveWork;
    private final Clock clock;

    /** What became of an ask to remove one part. */
    public enum Removal {
        /** Its objects and its build records are gone. */
        REMOVED,
        /** The partition still produces that part, so it is not gone and this service will not remove it. */
        STILL_A_PART,
        /** Nothing was published for it and no record of it was left. */
        NOTHING_TO_REMOVE
    }

    /**
     * The nightly sweep over every configured site. When it runs is decided by
     * {@code DocumentationBuildScheduling}.
     */
    public void removeWhatIsGone() {
        exclusiveWork.underLock(LOCK, LEASE, this::removeWhatIsGoneNow);
    }

    private void removeWhatIsGoneNow() {
        int removed = 0;
        for (Site site : sites.all()) {
            removed += removeWhatIsGoneOf(site);
        }
        if (removed > 0) {
            log.info("Removed what {} part(s) their site no longer has were still publishing.", removed);
        }
    }

    /**
     * Removes the publications of one site's departed parts, and reports how many.
     * <p>
     * A part the partition does not produce and that has published nothing for the retention is gone; anything
     * younger is left alone, because a part is also missing from the partition for the moment in which an
     * import replaces the model.
     */
    private int removeWhatIsGoneOf(Site site) {
        Set<String> current = currentPartsOf(site);
        if (current.size() <= 1) {
            // The shell and nothing else. Either the site really documents no system, in which case nothing is
            // published to remove, or its architecture model is empty because an import went wrong - and that
            // must never read as every system having left at once.
            log.debug("The {} partition of the site {} produces no part beside the shell, so nothing is taken "
                      + "to be gone.", partition.axis(), site.id());
            return 0;
        }
        Instant goneBefore = clock.instant().minus(properties.getDepartedPartRetention());
        int removed = 0;
        for (PublishedPart published : builds.publishedPartsOf(site.id())) {
            if (current.contains(published.part()) || published.publishedAt() == null
                || published.publishedAt().isAfter(goneBefore)) {
                continue;
            }
            if (remove(PartKey.of(site.id(), published.part()), published)) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * Removes one part now, on an operator's ask, without waiting out the retention.
     * <p>
     * <b>Only a part the site no longer has.</b> That is what makes it safe to expose: it is for a system that
     * has been decommissioned and whose documentation should stop being served today, and an operator must not
     * be able to take a live system's documentation off the site by mistyping a part.
     */
    public Removal removeNow(Site site, String partId) {
        if (currentPartsOf(site).contains(partId)) {
            return Removal.STILL_A_PART;
        }
        PartKey key = PartKey.of(site.id(), partId);
        Optional<PublishedPart> published = publishedPartOf(key);
        if (published.isPresent() && remove(key, published.get())) {
            return Removal.REMOVED;
        }
        // Nothing published, or a build took the lock in between. Either way the records are what is left, and
        // removing them is what stops the part reading as one this site once had.
        return builds.forgetPart(key) > 0 ? Removal.REMOVED : Removal.NOTHING_TO_REMOVE;
    }

    private Set<String> currentPartsOf(Site site) {
        Set<String> current = new HashSet<>();
        partition.partsOf(site).forEach(part -> current.add(part.id()));
        return current;
    }

    private Optional<PublishedPart> publishedPartOf(PartKey key) {
        return builds.publishedPartsOf(key.site()).stream()
                .filter(part -> part.part().equals(key.part()))
                .findFirst();
    }

    /**
     * The objects first, then the records - under the part's own lock, so this cannot run beside a build of it.
     * <p>
     * That order is what makes a failure in the middle harmless: records pointing at objects that are gone
     * serve <i>not generated yet</i> rather than a page, and the next sweep finds the part again and finishes
     * the job. The other order would leave objects nothing names.
     */
    private boolean remove(PartKey key, PublishedPart published) {
        return exclusiveWork.underLock(DocumentationBuildRunner.LOCK_PREFIX + key, properties.getLockLease(),
                () -> removeUnderLock(key, published)).orElse(false);
    }

    private boolean removeUnderLock(PartKey key, PublishedPart published) {
        // Read again now that nothing else can be building this part: a publication that moved between the
        // read above and this lock belongs to a build, and a build means the part is not gone after all.
        Optional<PublishedPart> now = publishedPartOf(key);
        if (now.isPresent() && !Objects.equals(now.get().publishedAt(), published.publishedAt())) {
            log.info("{} was published again while it was being removed as gone, so it is left alone.", key);
            return false;
        }
        try {
            removeEveryPublicationOf(key, published);
        } catch (RuntimeException e) {
            log.warn("What {} published could not be removed, although its site no longer has that part. The "
                     + "next sweep tries again.", key, e);
            return false;
        }
        int records = builds.forgetPart(key);
        log.info("{} is no longer a part of its site; its objects and its {} build record(s) are removed.",
                key, records);
        return true;
    }

    /**
     * Every publication of the part and not only the current one: the retention keeps a few behind it, and the
     * records that name them are about to go.
     */
    private void removeEveryPublicationOf(PartKey key, PublishedPart published) {
        Set<String> prefixes = new HashSet<>();
        if (published.objectPrefix() != null) {
            prefixes.add(published.objectPrefix());
        }
        // Nothing kept, so this answers every prefix the part still has objects under.
        prefixes.addAll(builds.prefixesBeyondRetention(key, 0));
        for (String prefix : prefixes) {
            publication.delete(prefix);
        }
    }
}
