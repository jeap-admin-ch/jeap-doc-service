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
 * The inside of one system: its components, the edges between them, and every other system as a single box.
 * <p>
 * A component of another system is never drawn here. The edge goes to that system's box instead, and
 * everything travelling to it is collected on one arrow.
 * <p>
 * The components are all of them; the other systems around them are bounded, because a system exchanging
 * something with a hundred others draws a picture nobody can read. What the diagram leaves out is still in
 * {@link #external()} for the page to list.
 *
 * @param system            the system being opened up
 * @param components        the components drawn inside it - every one of them
 * @param internal          edges between two components of this system
 * @param external          edges between a component of this system and another system as a whole
 * @param drawnNeighbours   the other systems a diagram may show. The limit is on the picture, not on the
 *                          facts: {@link #external()} still carries every edge, and the page's table lists
 *                          the neighbours the diagram leaves out
 */
public record WhiteboxView(
        DocumentedSystem system,
        List<DocumentedComponent> components,
        List<Edge> internal,
        List<Edge> external,
        List<String> drawnNeighbours) {

    public WhiteboxView {
        components = List.copyOf(components);
        internal = List.copyOf(internal);
        external = List.copyOf(external);
        drawnNeighbours = List.copyOf(drawnNeighbours);
    }

    /**
     * One arrow of the view.
     *
     * @param from   a component of this system, or another system
     * @param to     a component of this system, or another system
     * @param kind   what connects them
     * @param labels what travels, sorted and without repetition
     */
    public record Edge(String from, String to, RelationKind kind, List<String> labels) {

        public Edge {
            labels = List.copyOf(labels);
        }
    }

    /**
     * The inside of one system, read across the whole landscape.
     *
     * @param maxNeighbours how many <b>other systems</b> the diagram may draw around the package. The
     *                      components inside it are never cut, however many there are: every one of them has
     *                      a page, and one missing from the level-1 view would be a page the diagram does not
     *                      point at
     */
    public static WhiteboxView of(ArchitectureModel model, DocumentedSystem system, int maxNeighbours) {
        // Every component of the system is drawn: the whitebox view is the one place the whole decomposition
        // belongs, and a component missing from it would have a page nothing on the diagram points at.
        List<DocumentedComponent> drawn = system.components();
        Set<String> drawnNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        drawn.forEach(component -> drawnNames.add(component.name()));

        Map<String, Edge> internalEdges = new LinkedHashMap<>();
        Map<String, Set<String>> internalLabels = new LinkedHashMap<>();
        Map<String, Edge> externalEdges = new LinkedHashMap<>();
        Map<String, Set<String>> externalLabels = new LinkedHashMap<>();

        for (SystemRelation relation : model.relations()) {
            Ends ends = endsOf(relation, system, drawnNames);
            if (ends == null) {
                continue;
            }
            Map<String, Edge> edges = ends.internal() ? internalEdges : externalEdges;
            Map<String, Set<String>> labels = ends.internal() ? internalLabels : externalLabels;
            String key = SystemContext.edgeKey(ends.from(), ends.to(), relation.kind());
            edges.putIfAbsent(key, new Edge(ends.from(), ends.to(), relation.kind(), List.of()));
            labels.computeIfAbsent(key, ignored -> new TreeSet<>()).add(relation.label());
        }

        List<Edge> internal = collect(internalEdges, internalLabels);
        List<Edge> external = collect(externalEdges, externalLabels);
        return new WhiteboxView(system, drawn, internal, external,
                drawnNeighboursOf(system, external, maxNeighbours));
    }

    /** The first of the neighbours the diagram has room for, in the order they are listed in. */
    private static List<String> drawnNeighboursOf(DocumentedSystem system, List<Edge> external,
                                                  int maxNeighbours) {
        List<String> neighbours = neighboursOf(system, external);
        return neighbours.subList(0, Math.min(Math.max(maxNeighbours, 0), neighbours.size()));
    }

    /** What one relation draws: the two ends as the diagram names them, and which half it belongs to. */
    private record Ends(String from, String to, boolean internal) {
    }

    /**
     * The ends of a relation, or null when it draws no arrow on this view.
     * <p>
     * A component that is not drawn takes its arrows with it: an arrow to a box the diagram does not have
     * would point at nothing.
     */
    private static Ends endsOf(SystemRelation relation, DocumentedSystem system, Set<String> drawnNames) {
        if (!relation.touches(system.name())) {
            return null;
        }
        // A REST call points from the caller to the provider; a message points the other way.
        boolean restApi = relation.kind() == RelationKind.REST_API;
        String fromSystem = restApi ? relation.consumerSystem() : relation.providerSystem();
        String toSystem = restApi ? relation.providerSystem() : relation.consumerSystem();
        String fromComponent = restApi ? relation.consumer() : relation.provider();
        String toComponent = restApi ? relation.provider() : relation.consumer();

        // Inside the system an end is a component; outside it the whole system is one box.
        boolean fromInside = system.name().equalsIgnoreCase(fromSystem);
        boolean toInside = system.name().equalsIgnoreCase(toSystem);
        String from = fromInside ? fromComponent : fromSystem;
        String to = toInside ? toComponent : toSystem;

        if (isBlank(from) || isBlank(to) || from.equalsIgnoreCase(to)) {
            return null;
        }
        if (!isDrawn(from, fromInside, drawnNames) || !isDrawn(to, toInside, drawnNames)) {
            return null;
        }
        return new Ends(from, to, fromInside && toInside);
    }

    /** Whether an end has a box. Everything outside the system has one; inside, only what was not cut. */
    private static boolean isDrawn(String end, boolean inside, Set<String> drawnNames) {
        return !inside || drawnNames.contains(end);
    }

    private static List<Edge> collect(Map<String, Edge> edges, Map<String, Set<String>> labels) {
        List<Edge> collected = new ArrayList<>();
        edges.forEach((key, edge) ->
                collected.add(new Edge(edge.from(), edge.to(), edge.kind(), List.copyOf(labels.get(key)))));
        collected.sort(Comparator.comparing(Edge::from, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Edge::to, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(edge -> edge.kind().name()));
        return collected;
    }

    /**
     * The other systems this one exchanges anything with, each of which would be a single box. <b>Every</b>
     * one of them, drawn or not - the page's table lists them all.
     */
    public List<String> neighbourSystems() {
        return neighboursOf(system, external);
    }

    private static List<String> neighboursOf(DocumentedSystem system, List<Edge> external) {
        Set<String> systems = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Edge edge : external) {
            systems.add(neighbourOf(system, edge));
        }
        return List.copyOf(systems);
    }

    /** Which end of an external edge is the other system: the one that is not a component of this one. */
    public String neighbourOf(Edge edge) {
        return neighbourOf(system, edge);
    }

    private static String neighbourOf(DocumentedSystem system, Edge edge) {
        return system.hasComponent(edge.from()) ? edge.to() : edge.from();
    }

    /** Whether the diagram has a box for that neighbour, matched the way the edges are de-duplicated. */
    public boolean isDrawnNeighbour(String neighbour) {
        return drawnNeighbours.stream().anyMatch(drawn -> drawn.equalsIgnoreCase(neighbour));
    }

    /** How many neighbouring systems the diagram leaves out. The page still lists them. */
    public int truncated() {
        return neighbourSystems().size() - drawnNeighbours.size();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
