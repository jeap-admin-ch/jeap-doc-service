package ch.admin.bit.jeap.doc.reactionobserver;

import ch.admin.bit.jeap.doc.domain.architecture.ObservedReactions;
import ch.admin.bit.jeap.doc.domain.port.ReactionGraphContent;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/** Stored content retains its own source format independently of the currently configured API. */
@RequiredArgsConstructor
class ReactionObserverGraphContent implements ReactionGraphContent {
    private final JsonMapper json;

    @Override
    public ObservedReactions readFull(byte[] data) {
        return FullReactionGraphReader.read(json.readTree(data));
    }
}
