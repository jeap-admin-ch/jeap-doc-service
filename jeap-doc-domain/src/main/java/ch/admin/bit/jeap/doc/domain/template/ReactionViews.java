package ch.admin.bit.jeap.doc.domain.template;

import ch.admin.bit.jeap.doc.domain.architecture.view.ReactionView;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The reaction graphs of the system being generated, drawn-ready, and where they came from.
 * <p>
 * <b>One system's worth at a time.</b> A build joins these onto the run just before a system's pages are
 * written and lets them go afterwards, the way it already does with a component's replicated artifacts - the
 * whole landscape's graphs held at once would be a multiple of what a build is given.
 * <p>
 * <b>A missing view is a chapter that is not written</b>, and so is an empty one. Nothing here answers null:
 * {@link ReactionView#isEmpty()} is the one question a template asks.
 *
 * @param importedAt when the graphs of this system were imported - the newest of them. It is a different age
 *                   from the model's, and a page that draws reactions says so.
 *                   <b>Where they were read from is deliberately not here</b>: the observer's URL is an
 *                   internal host a reader cannot follow, and reaching it would mean a build asking an
 *                   upstream port, which is the one thing a build never does
 * @param system      the system's own graph
 * @param components  the graph of each component, by name, folded
 * @param messages    the graphs of each message type, by name folded, one per variant in the order they are
 *                    drawn
 * @param componentsWithAGraph
 *                    the components of the <b>whole environment</b> that have a graph, by name folded - the
 *                    same set for every system of it. It is what says whether a component's runtime view is
 *                    written somewhere on this site, and a reaction on a system's graph is regularly a
 *                    component of another system: the graphs above are one system's, so they cannot answer
 *                    that
 */
public record ReactionViews(
        Instant importedAt,
        ReactionView system,
        Map<String, ReactionView> components,
        Map<String, List<VariantView>> messages,
        Set<String> componentsWithAGraph) {

    public ReactionViews {
        system = system == null ? ReactionView.empty() : system;
        components = components == null ? Map.of() : Map.copyOf(components);
        messages = messages == null ? Map.of() : Map.copyOf(messages);
        componentsWithAGraph = componentsWithAGraph == null ? Set.of() : Set.copyOf(componentsWithAGraph);
    }

    /** What a run with no reaction observer, or no graphs for this system, carries. */
    public static ReactionViews none() {
        return new ReactionViews(null, ReactionView.empty(), Map.of(), Map.of(), Set.of());
    }

    /** The graph of one component, empty when there is none. */
    public ReactionView ofComponent(String component) {
        return components.getOrDefault(fold(component), ReactionView.empty());
    }

    /**
     * Whether a component's runtime view is written anywhere on this site - which is what decides whether a
     * reaction node may be linked, on this system's pages as much as on another's.
     */
    public boolean hasGraphOfComponent(String component) {
        return componentsWithAGraph.contains(fold(component));
    }

    /** The graphs of one message type, one per variant, empty when there are none. */
    public List<VariantView> ofMessage(String messageType) {
        return messages.getOrDefault(fold(messageType), List.of());
    }

    public boolean hasReactionsOfTheSystem() {
        return !system.isEmpty();
    }

    /** Whether anything on this system's pages is drawn from reactions at all. */
    public boolean isEmpty() {
        return system.isEmpty()
               && components.values().stream().allMatch(ReactionView::isEmpty)
               && messages.values().stream().flatMap(List::stream)
                       .allMatch(variant -> variant.view().isEmpty());
    }

    /** Keyed as every name join in this service is: folded, because two exports spell one name two ways. */
    public static String fold(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    /**
     * The views of a system, ready to be handed to a template.
     *
     * @param componentsWithAGraph every component of the environment that has a graph, in any spelling
     */
    public static ReactionViews of(Instant importedAt, ReactionView system,
                                   Map<String, ReactionView> components,
                                   Map<String, List<VariantView>> messages,
                                   Set<String> componentsWithAGraph) {
        Map<String, ReactionView> byComponent = new LinkedHashMap<>();
        components.forEach((name, view) -> byComponent.put(fold(name), view));
        Map<String, List<VariantView>> byMessage = new LinkedHashMap<>();
        messages.forEach((name, views) -> byMessage.put(fold(name), List.copyOf(views)));
        Set<String> linkable = new TreeSet<>();
        componentsWithAGraph.forEach(name -> linkable.add(fold(name)));
        return new ReactionViews(importedAt, system, byComponent, byMessage, linkable);
    }

    /**
     * One variant of a message type's graph.
     *
     * @param variant the variant, or {@code ""} for a message type that has none - which is most of them, and
     *                the case where the page draws one diagram without naming a variant at all
     */
    public record VariantView(String variant, ReactionView view) {

        public boolean hasVariant() {
            return variant != null && !variant.isEmpty();
        }
    }
}
