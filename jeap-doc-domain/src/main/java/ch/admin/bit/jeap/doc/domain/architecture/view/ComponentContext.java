package ch.admin.bit.jeap.doc.domain.architecture.view;

import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.architecture.RelationKind;
import ch.admin.bit.jeap.doc.domain.architecture.SystemRelation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What one component talks to: its siblings in the same system, and every other system as a single box.
 * <p>
 * Only the siblings it actually exchanges something with. Showing all of them would repeat the system's
 * whitebox view. A component of another system is never drawn either - the edge goes to that system's box.
 * <p>
 * Computed across the whole landscape, for the reason {@link SystemContext} is: a relation belongs to the
 * system that <i>defines</i> it, so a component that only consumes another system's events appears in none of
 * its own system's relations.
 *
 * @param system        the system the component belongs to
 * @param component     the component in the middle
 * @param edges         one edge per counterpart, kind and direction, with everything travelling along it.
 *                      <b>Every</b> one, including the counterparts the diagram leaves out
 * @param drawnSiblings the sibling components a diagram may show
 * @param drawnSystems  the other systems a diagram may show
 */
public record ComponentContext(
        DocumentedSystem system,
        DocumentedComponent component,
        List<Edge> edges,
        List<String> drawnSiblings,
        List<String> drawnSystems) {

    public ComponentContext {
        edges = List.copyOf(edges);
        drawnSiblings = List.copyOf(drawnSiblings);
        drawnSystems = List.copyOf(drawnSystems);
    }

    /**
     * One arrow of the view.
     *
     * @param from     this component, a sibling of it, or another system
     * @param to       this component, a sibling of it, or another system
     * @param kind     what connects them
     * @param labels   what travels, sorted and without repetition
     * @param sibling  whether the counterpart is a component of this system rather than another system
     */
    public record Edge(String from, String to, RelationKind kind, List<String> labels, boolean sibling) {

        public Edge {
            labels = List.copyOf(labels);
        }
    }

    /**
     * @param maxSiblings how many sibling components the diagram may draw
     * @param maxSystems  how many other systems it may draw, each as a single box
     */
    public static ComponentContext of(ArchitectureModel model, DocumentedSystem system,
                                      DocumentedComponent component, int maxSiblings, int maxSystems) {
        Map<String, Edge> byKey = new LinkedHashMap<>();
        Map<String, Set<String>> labelsByKey = new LinkedHashMap<>();

        for (SystemRelation relation : model.relations()) {
            Ends ends = endsOf(relation, system, component);
            if (ends == null) {
                continue;
            }
            String key = SystemContext.edgeKey(ends.from(), ends.to(), relation.kind());
            byKey.putIfAbsent(key, new Edge(ends.from(), ends.to(), relation.kind(), List.of(), ends.sibling()));
            labelsByKey.computeIfAbsent(key, ignored -> new TreeSet<>()).add(relation.label());
        }

        List<Edge> edges = new ArrayList<>();
        byKey.forEach((key, edge) -> edges.add(new Edge(edge.from(), edge.to(), edge.kind(),
                List.copyOf(labelsByKey.get(key)), edge.sibling())));
        edges.sort(Comparator.comparing(Edge::from, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Edge::to, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(edge -> edge.kind().name()));

        List<String> siblings = counterparts(edges, component.name(), true);
        List<String> systems = counterparts(edges, component.name(), false);
        return new ComponentContext(system, component, edges,
                firstOf(siblings, maxSiblings), firstOf(systems, maxSystems));
    }

    /** The two ends of one relation as the diagram names them, and which side of the package they are on. */
    private record Ends(String from, String to, boolean sibling) {
    }

    /**
     * The ends of a relation, or null when it draws no arrow on this view.
     * <p>
     * The end has to name this system as well as this component: two systems may each have a component called
     * {@code gateway}, and the other one's relations are not this one's.
     */
    private static Ends endsOf(SystemRelation relation, DocumentedSystem system, DocumentedComponent component) {
        // A REST call points from the caller to the provider; a message points the other way.
        boolean restApi = relation.kind() == RelationKind.REST_API;
        String fromSystem = restApi ? relation.consumerSystem() : relation.providerSystem();
        String toSystem = restApi ? relation.providerSystem() : relation.consumerSystem();
        String fromComponent = restApi ? relation.consumer() : relation.provider();
        String toComponent = restApi ? relation.provider() : relation.consumer();

        boolean startsHere = isThisComponent(fromComponent, fromSystem, system, component);
        boolean endsHere = isThisComponent(toComponent, toSystem, system, component);
        if (!startsHere && !endsHere) {
            return null;
        }
        // Inside the system a counterpart is a component; outside it the whole system is one box.
        String counterpartSystem = startsHere ? toSystem : fromSystem;
        String counterpartComponent = startsHere ? toComponent : fromComponent;
        boolean counterpartInside = system.name().equalsIgnoreCase(counterpartSystem);
        String counterpart = counterpartInside ? counterpartComponent : counterpartSystem;
        // An arrow to nothing is worse than no arrow, and a self-loop is not worth drawing.
        if (isBlank(counterpart) || counterpart.equalsIgnoreCase(component.name())) {
            return null;
        }
        return startsHere
                ? new Ends(component.name(), counterpart, counterpartInside)
                : new Ends(counterpart, component.name(), counterpartInside);
    }

    private static boolean isThisComponent(String name, String owningSystem, DocumentedSystem system,
                                           DocumentedComponent component) {
        return name != null && name.equalsIgnoreCase(component.name())
               && system.name().equalsIgnoreCase(owningSystem);
    }

    /** The counterparts on one side of the package, sorted and without repetition. */
    private static List<String> counterparts(List<Edge> edges, String component, boolean siblings) {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Edge edge : edges) {
            if (edge.sibling() == siblings) {
                names.add(edge.from().equalsIgnoreCase(component) ? edge.to() : edge.from());
            }
        }
        return List.copyOf(names);
    }

    private static List<String> firstOf(List<String> names, int max) {
        return names.subList(0, Math.min(Math.max(max, 0), names.size()));
    }

    /** Every sibling component this one exchanges anything with, drawn or not. */
    public List<String> siblings() {
        return counterparts(edges, component.name(), true);
    }

    /** Every other system it exchanges anything with, drawn or not. */
    public List<String> externalSystems() {
        return counterparts(edges, component.name(), false);
    }

    /** Whether the diagram has a box for that counterpart. Matched ignoring case, like the edge keys. */
    public boolean isDrawn(String counterpart) {
        return counterpart.equalsIgnoreCase(component.name())
               || drawnSiblings.stream().anyMatch(drawn -> drawn.equalsIgnoreCase(counterpart))
               || drawnSystems.stream().anyMatch(drawn -> drawn.equalsIgnoreCase(counterpart));
    }

    /** How many counterparts the diagram leaves out. The page's table lists all of them anyway. */
    public int truncated() {
        return siblings().size() - drawnSiblings.size()
               + externalSystems().size() - drawnSystems.size();
    }

    public boolean isEmpty() {
        return edges.isEmpty();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
