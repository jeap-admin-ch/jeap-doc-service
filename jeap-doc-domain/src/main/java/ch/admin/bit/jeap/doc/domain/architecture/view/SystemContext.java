package ch.admin.bit.jeap.doc.domain.architecture.view;

import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.architecture.RelationKind;
import ch.admin.bit.jeap.doc.domain.architecture.SystemRelation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What a system talks to, and about what.
 * <p>
 * It is computed across the whole landscape rather than from the system's own export. In the architecture
 * repository a relation belongs to the system that <i>defines</i> it: an event relation to the system owning
 * the event, a REST relation to the system providing the API. A system that only consumes events and calls
 * other systems' APIs therefore defines no relations at all, and reading only its own would draw it as an
 * island.
 * <p>
 * Nothing here knows about PlantUML. Which neighbours and edges exist is a fact about the architecture, so it
 * is decided here and is a unit test. How a diagram expresses it belongs to whoever draws one.
 *
 * @param system    the system in the middle
 * @param edges     one edge per neighbour, kind and direction, with everything travelling along it -
 *                  <b>every</b> one, including the neighbours the diagram leaves out
 * @param drawn     the neighbours a diagram may show. The limit is on the picture, not on the facts: a page
 *                  that says <i>the table below lists every one of them</i> has to be able to
 */
public record SystemContext(DocumentedSystem system, List<ContextEdge> edges, List<String> drawn) {

    /** Separates the parts of an edge key. A system or component name cannot contain it. */
    static final String EDGE_KEY_SEPARATOR = "|";

    public SystemContext {
        edges = List.copyOf(edges);
        drawn = List.copyOf(drawn);
    }

    /**
     * Everything that flows between two systems in one direction, of one kind.
     *
     * @param from   the system the arrow starts at
     * @param to     the system the arrow points at
     * @param kind   what connects them
     * @param labels what travels, sorted and without repetition
     */
    public record ContextEdge(String from, String to, RelationKind kind, List<String> labels) {

        public ContextEdge {
            labels = List.copyOf(labels);
        }
    }

    /**
     * The context of one system, read across the whole landscape.
     *
     * @param maxNeighbours how many neighbours a diagram may show. A system with sixty of them renders a
     *                      picture nobody can read. Only the diagram is cut; every edge is still returned
     */
    public static SystemContext of(ArchitectureModel model, DocumentedSystem system, int maxNeighbours) {
        Map<String, ContextEdge> byKey = new LinkedHashMap<>();
        Map<String, Set<String>> labelsByKey = new LinkedHashMap<>();

        for (SystemRelation relation : model.relations()) {
            if (!relation.touches(system.name()) || relation.isInternalTo(system.name())) {
                continue;
            }
            String from = arrowStartOf(relation);
            String to = arrowEndOf(relation);
            // A blank end is a component of no known system, and an edge to nothing at all is worse than no
            // edge - so it is only drawn when both ends are known.
            if (!isBlank(from) && !isBlank(to)) {
                String key = edgeKey(from, to, relation.kind());
                byKey.putIfAbsent(key, new ContextEdge(from, to, relation.kind(), List.of()));
                labelsByKey.computeIfAbsent(key, ignored -> new TreeSet<>()).add(relation.label());
            }
        }

        List<String> neighbours = byKey.values().stream()
                .map(edge -> counterpartOf(edge, system.name()))
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        List<String> drawn = neighbours.subList(0, Math.min(Math.max(maxNeighbours, 0), neighbours.size()));

        List<ContextEdge> edges = new ArrayList<>();
        byKey.forEach((key, edge) -> edges.add(new ContextEdge(edge.from(), edge.to(), edge.kind(),
                List.copyOf(labelsByKey.get(key)))));
        edges.sort(Comparator.comparing(ContextEdge::from, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ContextEdge::to, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(edge -> edge.kind().name()));

        return new SystemContext(system, edges, drawn);
    }

    /**
     * Where the arrow starts, which depends on the kind of relation.
     * <p>
     * A REST arrow is the call, so it runs from the consumer to the provider. A message arrow runs the other
     * way, from the side that publishes or sends. Getting this backwards would reverse every arrow at once.
     */
    private static String arrowStartOf(SystemRelation relation) {
        return relation.kind() == RelationKind.REST_API ? relation.consumerSystem() : relation.providerSystem();
    }

    private static String arrowEndOf(SystemRelation relation) {
        return relation.kind() == RelationKind.REST_API ? relation.providerSystem() : relation.consumerSystem();
    }

    private static String counterpartOf(ContextEdge edge, String system) {
        return edge.from().equalsIgnoreCase(system) ? edge.to() : edge.from();
    }

    /** What tells two edges apart: the two ends and the kind, ignoring case. */
    static String edgeKey(String from, String to, RelationKind kind) {
        return canonical(from) + EDGE_KEY_SEPARATOR + canonical(to) + EDGE_KEY_SEPARATOR + kind;
    }

    private static String canonical(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** How many neighbours the diagram leaves out. The page still lists them. */
    public int truncated() {
        return neighbours().size() - drawn.size();
    }

    /** Every neighbouring system, sorted, drawn or not. */
    public List<String> neighbours() {
        return edges.stream()
                .map(edge -> counterpartOf(edge, system.name()))
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    public boolean isEmpty() {
        return edges.isEmpty();
    }
}
