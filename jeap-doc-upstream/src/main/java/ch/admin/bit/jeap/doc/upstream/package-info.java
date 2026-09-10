/**
 * How this service reads another jEAP service and replicates what it answers.
 * <p>
 * <b>Not an adapter.</b> It contributes no bean, no auto-configuration and no configuration properties, and it
 * names nothing of the domain - it is transport, and the adapters that do the replicating depend on it:
 * {@code jeap-doc-archrepo} and {@code jeap-doc-reactionobserver}.
 * <p>
 * <b>What is here is what neither of them may keep a copy of.</b> Building the client and its
 * client-credentials token, the conditional request and the entity tag that goes back verbatim, the read that
 * cannot be talked into consuming a gigabyte, the redirect that is not followed, resolving a content URL
 * against the upstream's own origin, one exception class with a retry policy over it, and the client settings.
 * An adapter keeps only what is its upstream's own: its routes, its payloads, the wording of its messages and
 * its own properties.
 * <p>
 * The reason is drift rather than tidiness. Two copies of <i>what a {@code 304} means</i> is how one upstream
 * comes to refetch everything on every run while the other does not - and neither copy fails a test, because
 * each is tested against itself.
 */
package ch.admin.bit.jeap.doc.upstream;
