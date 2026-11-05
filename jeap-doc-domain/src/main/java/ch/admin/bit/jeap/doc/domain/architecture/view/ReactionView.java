package ch.admin.bit.jeap.doc.domain.architecture.view;

import ch.admin.bit.jeap.doc.domain.architecture.MessageKind;
import ch.admin.bit.jeap.doc.domain.architecture.ObservedReactions.Identity;
import java.math.BigDecimal;
import java.util.List;

/**
 * Text and documentation destinations for one entry into the complete reaction snapshot.
 * Rows describe the initially expanded selection and exclude superseded reactions. They remain available
 * as a static fallback when the browser cannot render its live table. Links name only unambiguous
 * documented destinations.
 */
public record ReactionView(List<MessageLink> messages, List<ComponentLink> components, List<RestLink> restProviders, List<Row> rows,
                           boolean hasObservations) {
    public ReactionView {
        messages = List.copyOf(messages);
        components = List.copyOf(components);
        restProviders = List.copyOf(restProviders);
        rows = List.copyOf(rows);
    }

    public static ReactionView empty() {
        return new ReactionView(List.of(), List.of(), List.of(), List.of(), false);
    }

    /** Superseded-only observations still need a viewer to disclose their hidden content. */
    public boolean isEmpty() { return !hasObservations; }

    public record MessageLink(String name, String systemSlug, String messageSlug, MessageKind kind) {}
    public record ComponentLink(String system, String component, String componentSlug, String systemSlug) {}
    public record RestLink(String provider, String componentSlug, String systemSlug) {}

    /** Identities stay opaque; HTTP and unknown interfaces remain readable in the table too. */
    public record Row(Identity reaction, String trigger, String component, List<String> answers, BigDecimal median) {
        public Row { answers = List.copyOf(answers); }
    }
}
