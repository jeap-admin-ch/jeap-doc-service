package ch.admin.bit.jeap.doc.domain.architecture.view;

import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.architecture.RelationKind;
import ch.admin.bit.jeap.doc.domain.architecture.SystemRelation;
import ch.admin.bit.jeap.doc.domain.template.DiagramLimits;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
        return of(model, system, maxNeighbours, ViewExclusions.NONE);
    }

    /**
     * The same, without the components left out of the views. A relation with one of them at an end is not
     * drawn: its box is not there.
     */
    public static WhiteboxView of(ArchitectureModel model, DocumentedSystem system, int maxNeighbours,
                                  ViewExclusions excluded) {
        // Every component of the system is drawn: the whitebox view is the one place the whole decomposition
        // belongs, and a component missing from it would have a page nothing on the diagram points at.
        List<DocumentedComponent> drawn = system.components().stream()
                .filter(component -> !excluded.excludesComponent(component.name()))
                .toList();
        Set<String> drawnNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        drawn.forEach(component -> drawnNames.add(component.name()));

        Map<String, Edge> internalEdges = new LinkedHashMap<>();
        Map<String, Set<String>> internalLabels = new LinkedHashMap<>();
        Map<String, Edge> externalEdges = new LinkedHashMap<>();
        Map<String, Set<String>> externalLabels = new LinkedHashMap<>();

        for (SystemRelation relation : model.relations()) {
            Ends ends = excluded.excludes(relation) ? null : endsOf(relation, system, drawnNames);
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
     * The external edges the diagram can draw: the ones whose neighbour has a box. An arrow to a neighbour the
     * bound left out would point at nothing, and the page's table lists it all the same.
     */
    public List<Edge> drawnExternal() {
        return external.stream().filter(edge -> isDrawnNeighbour(neighbourOf(edge))).toList();
    }

    /**
     * The components with something drawn crossing the boundary, in the order the package draws them.
     * <p>
     * The boxes of the boundary picture: a component that talks to nobody outside is not in it. The
     * components table below the picture still carries every component.
     */
    public List<DocumentedComponent> boundaryComponents() {
        Set<String> onTheBoundary = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Edge edge : drawnExternal()) {
            onTheBoundary.add(componentOf(edge));
        }
        return components.stream().filter(component -> onTheBoundary.contains(component.name())).toList();
    }

    /** Which end of an external edge is the component of this system - the one the neighbour is not. */
    private String componentOf(Edge edge) {
        return system.hasComponent(edge.from()) ? edge.from() : edge.to();
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

    /**
     * The edges of a picture collapsed to one line per pair of boxes, whatever travels between them.
     * <p>
     * What a folded picture draws. It keeps the shape - which boxes are hubs, what is joined to what - and
     * gives up the kinds, the directions and the names, which are what make a busy picture unreadable. The
     * relations table below the picture carries all of them.
     */
    public static List<FoldedEdge> folded(List<Edge> edges) {
        Map<String, FoldedEdge> byPair = new LinkedHashMap<>();
        for (Edge edge : edges) {
            String pair = pairKey(edge.from(), edge.to());
            FoldedEdge known = byPair.get(pair);
            if (known == null) {
                byPair.put(pair, new FoldedEdge(edge.from(), edge.to(), false));
            } else if (!known.bothWays() && !known.from().equalsIgnoreCase(edge.from())) {
                byPair.put(pair, new FoldedEdge(known.from(), known.to(), true));
            }
        }
        List<FoldedEdge> folded = new ArrayList<>(byPair.values());
        folded.sort(Comparator.comparing(FoldedEdge::from, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(FoldedEdge::to, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(folded);
    }

    /** A pair of boxes, whichever way round the edge runs. */
    private static String pairKey(String from, String to) {
        String one = from.toLowerCase(Locale.ROOT);
        String other = to.toLowerCase(Locale.ROOT);
        return one.compareTo(other) <= 0 ? one + "\u0000" + other : other + "\u0000" + one;
    }

    /**
     * Which pictures the page draws, and how each of them is drawn.
     * <p>
     * <b>The ladder</b>: the decomposition when it is within the bounds, then <b>one</b> picture of what the
     * system exchanges outside - the whole one where it fits, and the boundary alone where it does not. At
     * most two pictures, as the page has always had.
     *
     * @param limits the two bounds on a picture: {@code maxDetailedEdges} folds it, {@code maxDiagramEdges}
     *               drops it
     */
    public Pictures pictures(DiagramLimits limits) {
        Picture inside = internal.isEmpty() ? null : pictureOf(PictureKind.INSIDE, internal, limits);
        List<Edge> boundary = drawnExternal();
        if (boundary.isEmpty()) {
            // Nothing crosses the boundary that can be drawn, so the second picture would be the first one
            // again with a neighbour box or two around it.
            return new Pictures(inside, null);
        }
        List<Edge> whole = new ArrayList<>(internal);
        whole.addAll(boundary);
        if (whole.size() <= limits.maxDiagramEdges()) {
            return new Pictures(inside, pictureOf(PictureKind.WHOLE, whole, limits));
        }
        return new Pictures(inside, pictureOf(PictureKind.BOUNDARY, boundary, limits));
    }

    private static Picture pictureOf(PictureKind kind, List<Edge> edges, DiagramLimits limits) {
        if (edges.size() > limits.maxDiagramEdges()) {
            return new Picture(kind, Rendering.NOT_DRAWN, List.of(), List.of(), edges.size());
        }
        if (edges.size() > limits.maxDetailedEdges()) {
            return new Picture(kind, Rendering.FOLDED, edges, folded(edges), edges.size());
        }
        return new Picture(kind, Rendering.IN_FULL, edges, List.of(), edges.size());
    }

    /**
     * The pictures of one whitebox page.
     *
     * @param inside   the decomposition, or null where the components exchange nothing with each other
     * @param outside  what the system exchanges with other systems - the whole picture or the boundary one -
     *                 or null where nothing drawable crosses the boundary
     */
    public record Pictures(Picture inside, Picture outside) {
    }

    /**
     * One picture of the page.
     *
     * @param edges     what it draws, empty where it is not drawn
     * @param folded    the same edges as one line per pair, empty unless the picture is folded
     * @param relations how many relations it would have drawn, which is what the page's sentence names
     */
    public record Picture(PictureKind kind, Rendering rendering, List<Edge> edges, List<FoldedEdge> folded,
                          int relations) {

        public Picture {
            edges = List.copyOf(edges);
            folded = List.copyOf(folded);
        }

        public boolean isDrawn() {
            return rendering != Rendering.NOT_DRAWN;
        }

        /** Whether it draws the neighbouring systems, which decides where the note about them belongs. */
        public boolean carriesNeighbours() {
            return kind != PictureKind.INSIDE;
        }
    }

    /** Which of the three pictures this is. */
    public enum PictureKind {
        /** The components of the system and what flows between them. */
        INSIDE,
        /** The same components, with every other system they exchange something with as a single box. */
        WHOLE,
        /** Only what crosses the boundary: the components that exchange something outside, and with whom. */
        BOUNDARY
    }

    /** How much of a picture is drawn. */
    public enum Rendering {
        /** An arrow per kind and direction, in its colour, carrying the names. */
        IN_FULL,
        /** One grey line per pair of boxes, with no kind, no colour and no name. */
        FOLDED,
        /** Nothing at all: the page says so in a sentence and sends the reader to the tables. */
        NOT_DRAWN
    }

    /**
     * One line of a folded picture: a pair of boxes, and whether they exchange something both ways.
     *
     * @param bothWays whether the pair is joined in both directions, which is drawn without an arrowhead
     */
    public record FoldedEdge(String from, String to, boolean bothWays) {
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
