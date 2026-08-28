package ch.admin.bit.jeap.doc.domain;

/**
 * What asked for a build. Recorded on the request and on the build, because *why did this run* is the first
 * question about an unexpected one.
 */
public enum BuildTrigger {

    /**
     * Documentation was uploaded for the site.
     */
    UPLOAD,

    /**
     * The site's publication schedule came round.
     */
    SCHEDULE,

    /**
     * Somebody asked for the site over the administration API. Kept apart from {@link #SCHEDULE} because *why
     * did this run* is the first question about an unexpected build, and a run somebody asked for is the one
     * answer that needs no further investigation.
     */
    MANUAL,

    /**
     * A build of the site was found still marked as running while its lock was free, so the instance running it
     * is gone and the run it was performing never finished. **The row is the request**: whatever asked for that
     * build was claimed when it started and cannot be asked again, so the abandoned run is what says a build is
     * still owed.
     * <p>
     * A build that was itself triggered this way and is abandoned in turn is <b>not</b> retried again. One
     * automatic attempt is a crashed instance; two in a row is a build that kills whatever runs it, and
     * repeating it for ever would be a crash loop rather than a recovery.
     */
    RECOVERY
}
