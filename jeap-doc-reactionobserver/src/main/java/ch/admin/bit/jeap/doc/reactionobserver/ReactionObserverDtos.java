package ch.admin.bit.jeap.doc.reactionobserver;

import java.util.List;

/**
 * The payloads of the reaction observer's replication indexes.
 * <p>
 * Field names are copied from the records the reaction observer ships. <b>The graphs themselves have no record
 * here</b>: a graph is stored as it arrived and is read again when a page is drawn, so this adapter reads two
 * fields out of the envelope around it and never the graph.
 */
final class ReactionObserverDtos {

    private ReactionObserverDtos() {
    }

    /** The index of the system graphs, and the index of the component graphs. */
    record GraphIndexDto(List<GraphIndexEntryDto> entries) {
    }

    /**
     * @param name   the system or the component the graph is of
     * @param system the system a component's reactions were published under. Null in the index of systems,
     *               where it would say the same thing twice
     */
    record GraphIndexEntryDto(String name, String system, String etag, String path) {
    }

    /** The index of the message graphs. */
    record MessageGraphIndexDto(List<MessageGraphIndexEntryDto> entries) {
    }

    /**
     * One message type, with every variant of it that has a graph.
     *
     * @param variants the <b>keys</b> the graph resource answers with - the message type alone when it has no
     *                 variant, and {@code messageType/variant} otherwise
     * @param etag     the tag of the whole answer, so every variant of one type shares it
     */
    record MessageGraphIndexEntryDto(String messageType, List<String> variants, String etag, String path) {
    }
}
