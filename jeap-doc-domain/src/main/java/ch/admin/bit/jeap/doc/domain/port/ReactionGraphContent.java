package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.architecture.ObservedReactions;

/** Parsing belongs to the observer adapter; malformed content must never replace a valid publication. */
public interface ReactionGraphContent {
    ObservedReactions readFull(byte[] data);
}
