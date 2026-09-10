/**
 * The client of the reaction observer's replication API.
 * <p>
 * The adapter behind {@link ch.admin.bit.jeap.doc.domain.port.ReactionGraphUpstream}: three indexes of what an
 * environment's observer holds, and a conditional fetch of every graph whose entity tag moved.
 * <p>
 * <b>The transport is not here.</b> The client, the bounded read, the redirect rule, the entity tag and the
 * retry policy are {@code jeap-doc-upstream}'s, shared with the architecture repository's client, and nothing
 * of it may be copied into this package. What is here is what is the observer's own: its routes, its index
 * records, the way a message type's variants are addressed, and its configuration properties.
 */
package ch.admin.bit.jeap.doc.reactionobserver;
