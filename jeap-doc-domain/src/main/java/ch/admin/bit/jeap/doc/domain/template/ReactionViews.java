package ch.admin.bit.jeap.doc.domain.template;

import ch.admin.bit.jeap.doc.domain.architecture.view.ReactionView;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Entry projections of one consistent environment snapshot for one system's documentation.
 * Missing observations omit a chapter; lookups return empty views or lists, never null.
 *
 * @param importedAt when the reaction snapshot was stored, independently of the architecture model's age
 * @param components views keyed by component name, folded without changing the displayed spelling
 * @param messages views keyed by folded message name, with variants in display order
 */
public record ReactionViews(Instant importedAt, ReactionView system,
                            Map<String, ReactionView> components, Map<String, List<VariantView>> messages) {
    public ReactionViews {
        components = Map.copyOf(components);
        messages = Map.copyOf(messages);
    }

    public static ReactionViews none() {
        return new ReactionViews(null, ReactionView.empty(), Map.of(), Map.of());
    }

    public ReactionView ofComponent(String component) { return components.getOrDefault(fold(component), ReactionView.empty()); }
    public List<VariantView> ofMessage(String message) { return messages.getOrDefault(fold(message), List.of()); }
    public boolean hasReactionsOfTheSystem() { return !system.isEmpty(); }
    public boolean isEmpty() {
        return system.isEmpty() && components.values().stream().allMatch(ReactionView::isEmpty)
                && messages.values().stream().flatMap(List::stream).allMatch(v -> v.view().isEmpty());
    }

    /** Names join case-insensitively because the two upstreams may spell them differently. */
    public static String fold(String name) { return name == null ? "" : name.toLowerCase(Locale.ROOT); }

    public static ReactionViews of(Instant importedAt, ReactionView system, Map<String, ReactionView> components,
                                    Map<String, List<VariantView>> messages) {
        Map<String, ReactionView> byComponent = new LinkedHashMap<>();
        components.forEach((name, view) -> byComponent.put(fold(name), view));
        Map<String, List<VariantView>> byMessage = new LinkedHashMap<>();
        messages.forEach((name, views) -> byMessage.put(fold(name), List.copyOf(views)));
        return new ReactionViews(importedAt, system, byComponent, byMessage);
    }

    /** The variant stays verbatim; null or an empty string denotes the unqualified message. */
    public record VariantView(String variant, ReactionView view) {
        public boolean hasVariant() { return variant != null && !variant.isEmpty(); }
    }
}
