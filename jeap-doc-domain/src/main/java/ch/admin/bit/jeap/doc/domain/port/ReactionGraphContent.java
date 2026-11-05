package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.architecture.ObservedReactions;

/**
 * Reads stored observer bytes into the domain model. Parsing belongs to the observer adapter, so neither
 * the domain nor a structure template depends on a JSON mapper. Malformed content fails rather than
 * returning an empty graph that could be mistaken for an absence of observations.
 */
public interface ReactionGraphContent {
    /** Uses the stored format envelope, accepting unwrapped legacy content as V1. */
    ObservedReactions readFull(byte[] data);
}
