package ch.admin.bit.jeap.doc.domain;

/**
 * What became of an ask for a build.
 * <p>
 * The two fields answer the two questions a caller of the administration API has: whether its ask is what put the
 * site in the queue, and when the site will be built. They are not the same question - an ask that joined a
 * request already standing did not create anything, and the build it will get is the one that was already owed.
 *
 * @param created whether this ask created the request, rather than joining one that was already pending
 * @param request the request as it stands, or null when the runner claimed it in the meantime and the build has
 *                therefore already started
 */
public record BuildRequestOutcome(boolean created, BuildRequest request) {
}
