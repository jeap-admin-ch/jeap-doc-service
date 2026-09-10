package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.architecture.ObservedReactions;
import ch.admin.bit.jeap.doc.domain.architecture.imports.StoredReactionGraph;

/**
 * Reads the bytes of a replicated reaction graph into what a page draws.
 * <p>
 * A port for the reason {@link ArchitectureArtifactContent} is one: parsing needs a JSON mapper, which neither
 * the domain nor a structure template may hold, and the format belongs to the reaction observer - so the
 * adapter sits in {@code jeap-doc-reactionobserver}, beside the client that fetched the bytes.
 * <p>
 * <b>It does not throw.</b> An unreadable graph answers {@link ObservedReactions#empty()} and is logged. There
 * is no {@code try} around the generation of a site, so one that threw would cost every system of the
 * environment its documentation instead of costing one page - and an empty answer is a page that is not
 * written, which is what a system with no observed reactions gets anyway.
 */
public interface ReactionGraphContent {

    ObservedReactions read(StoredReactionGraph graph);
}
