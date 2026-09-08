package ch.admin.bit.jeap.doc.domain.architecture.view;

import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.architecture.RelationKind;
import ch.admin.bit.jeap.doc.domain.architecture.SystemRelation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What one component talks to: the components of its own system it exchanges something with, and the
 * components of other systems it exchanges something with, each inside a box for the system that owns it.
 * <p>
 * Only the counterparts it actually exchanges something with. Showing every component of a neighbour would
 * make the page that system's whitebox view, and showing every sibling would make it this system's.
 * <p>
 * <b>A neighbour is drawn open, or whole, and never both.</b> Where none of a neighbour's counterpart
 * components is known, or where the bound on the boxes left no room for them, the neighbour is one box - which
 * is what the whole view was before it named foreign components, and what it degrades to as the bound shrinks.
 * <p>
 * Computed across the whole landscape, for the reason {@link SystemContext} is: a relation belongs to the
 * system that <i>defines</i> it, so a component that only consumes another system's events appears in none of
 * its own system's relations.
 *
 * @param system    the system the component belongs to
 * @param component the component in the middle
 * @param edges     one edge per pair of counterparts, kind and direction, with everything travelling along
 *                  it. <b>Every</b> one, including the counterparts the diagram has no box for
 * @param drawn     the boxes the diagram has, in the order it draws them: this component first, then its
 *                  siblings, then the components of other systems, then the systems drawn whole
 * @param arrows    the arrows the diagram draws - the edges whose two ends both have a box, mapped onto those
 *                  boxes. An edge onto a neighbour drawn whole lands on that neighbour's box, and two such
 *                  edges are one arrow
 */
public record ComponentContext(
        DocumentedSystem system,
        DocumentedComponent component,
        List<Edge> edges,
        List<Node> drawn,
        List<Edge> arrows) {

    public ComponentContext {
        edges = List.copyOf(edges);
        drawn = List.copyOf(drawn);
        arrows = List.copyOf(arrows);
    }

    /** What a box stands for. */
    public enum NodeKind {
        /** The component the page is about. */
        SELF,
        /** A component of the same system. */
        SIBLING,
        /** A component of another system. */
        NEIGHBOUR_COMPONENT,
        /** Another system, as a single box: what is known of it is that this component talks to it. */
        NEIGHBOUR_SYSTEM,
        /**
         * A component whose owning system the model does not say. Never drawn - a box outside every system
         * would read as <i>a component of no system</i>, which is not what the model says.
         */
        COMPONENT_OF_UNKNOWN_SYSTEM
    }

    /**
     * One end of a relation as the view names it.
     * <p>
     * <b>A name is not an identity.</b> Two systems may each have a component called {@code gateway}, so a
     * diagram keying a box on the name alone draws one box carrying the arrows of both. Everything that tells
     * two boxes apart goes through {@link #key()}, which keeps the two names apart rather than joining them
     * into one string - a name may contain any separator.
     * <p>
     * <b>The slugs are resolved here, from the model.</b> A template reads a slug and never derives one, and a
     * resolved slug is also the evidence that the page exists: it is non-null only because this landscape
     * documents that system, and that system has that component. A link inside a fence is checked by nothing.
     *
     * @param systemName    the owning system as the <b>model</b> spells it, or the neighbour itself for a
     *                      system drawn whole. Null only where the model does not say
     * @param componentName the component, or null for a system drawn whole
     */
    public record Node(NodeKind kind, String systemName, String systemSlug,
                       String componentName, String componentSlug) {

        /** What the box is labelled with. */
        public String label() {
            return componentName == null ? systemName : componentName;
        }

        /** Whether this box is a whole system rather than a component. */
        public boolean isSystem() {
            return componentName == null;
        }

        /** What tells two boxes apart: the system, and the component inside it. */
        public Key key() {
            return new Key(folded(systemName), folded(componentName));
        }

        private static String folded(String value) {
            return value == null ? null : value.toLowerCase(Locale.ROOT);
        }

        /** The identity of a box, as the parts rather than as one joined string. */
        public record Key(String system, String component) {
        }
    }

    /**
     * One arrow of the view.
     *
     * @param labels what travels, sorted and without repetition
     */
    public record Edge(Node from, Node to, RelationKind kind, List<String> labels) {

        public Edge {
            labels = List.copyOf(labels);
        }

        /** The end that is not the component the page is about, for a page listing its counterparts. */
        public Node counterpartOf(Node self) {
            return from.key().equals(self.key()) ? to : from;
        }
    }

    /** A system drawn open, with the boxes of its components that this component exchanges something with. */
    public record DrawnSystem(String name, String slug, List<Node> components) {

        public DrawnSystem {
            components = List.copyOf(components);
        }

        /**
         * The system itself as a box, which is what an arrow lands on where a relation names the system and
         * not one of its components. It is the identity the arrows of such a relation carry, so a renderer
         * has to give the package this node's alias.
         */
        public Node asNode() {
            return new Node(NodeKind.NEIGHBOUR_SYSTEM, name, slug, null, null);
        }
    }

    /**
     * @param maxComponents how many component boxes the diagram may draw - the siblings and the components of
     *                      other systems together. Siblings first, then one component of each neighbour in
     *                      turn, so that one large neighbour cannot push this component's own siblings out
     * @param maxSystems    how many other systems it may reach, whether drawn open or whole
     */
    public static ComponentContext of(ArchitectureModel model, DocumentedSystem system,
                                      DocumentedComponent component, int maxComponents, int maxSystems) {
        Nodes nodes = new Nodes(model, system, component);
        Map<EdgeKey, Edge> byKey = new LinkedHashMap<>();
        Map<EdgeKey, Set<String>> labelsByKey = new LinkedHashMap<>();

        for (SystemRelation relation : model.relations()) {
            Ends ends = endsOf(relation, nodes);
            if (ends == null) {
                continue;
            }
            EdgeKey key = new EdgeKey(ends.from().key(), ends.to().key(), relation.kind());
            byKey.putIfAbsent(key, new Edge(ends.from(), ends.to(), relation.kind(), List.of()));
            labelsByKey.computeIfAbsent(key, ignored -> new TreeSet<>()).add(relation.label());
        }

        List<Edge> edges = new ArrayList<>();
        byKey.forEach((key, edge) -> edges.add(new Edge(edge.from(), edge.to(), edge.kind(),
                List.copyOf(labelsByKey.get(key)))));
        edges.sort(Comparator.comparing((Edge edge) -> edge.from().label(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(edge -> edge.to().label(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(edge -> edge.kind().name()));

        List<Node> drawn = drawnOf(nodes.self(), edges, maxComponents, maxSystems);
        return new ComponentContext(system, component, edges, drawn,
                arrowsOf(edges, drawn, openSystemsOf(drawn)));
    }

    /**
     * Which boxes the diagram gets.
     * <p>
     * <b>Siblings first, then one component of each neighbour in turn.</b> A neighbour of two hundred
     * components would otherwise fill the budget and push this component's own siblings off its own page, and
     * naming one counterpart in each neighbour is worth more than naming forty in the first.
     * <p>
     * A neighbour left with no component box is drawn whole, so the picture degrades to the one this view drew
     * before it named foreign components rather than losing the relation.
     */
    private static List<Node> drawnOf(Node self, List<Edge> edges, int maxComponents, int maxSystems) {
        List<Node> siblings = counterpartsOf(edges, self, NodeKind.SIBLING);
        List<Node> reachedSystems = reachedSystemsOf(edges, self, Math.max(maxSystems, 0));

        int budget = Math.max(maxComponents, 0);
        List<Node> drawn = new ArrayList<>();
        drawn.add(self);
        for (Node sibling : siblings) {
            if (drawn.size() - 1 >= budget) {
                break;
            }
            drawn.add(sibling);
        }

        // One from each neighbour per pass, in the order the neighbours are reached, until the budget is out.
        Map<String, List<Node>> owed = new LinkedHashMap<>();
        for (Node neighbour : reachedSystems) {
            List<Node> components = counterpartsIn(edges, self, neighbour.systemName());
            if (!components.isEmpty()) {
                owed.put(neighbour.systemName(), new ArrayList<>(components));
            }
        }
        Set<Node> foreign = new LinkedHashSet<>();
        boolean handedOne = true;
        while (handedOne && drawn.size() - 1 < budget) {
            handedOne = false;
            for (List<Node> queue : owed.values()) {
                if (queue.isEmpty() || drawn.size() - 1 >= budget) {
                    continue;
                }
                Node next = queue.removeFirst();
                foreign.add(next);
                drawn.add(next);
                handedOne = true;
            }
        }

        // A neighbour that got no box of its own for any of its components is drawn whole.
        for (Node neighbour : reachedSystems) {
            boolean drawnOpen = foreign.stream()
                    .anyMatch(node -> node.systemName().equalsIgnoreCase(neighbour.systemName()));
            if (!drawnOpen) {
                drawn.add(neighbour);
            }
        }
        return List.copyOf(drawn);
    }

    /**
     * The arrows the diagram draws: an edge whose ends both have a box, with each end mapped onto the box
     * that stands for it.
     * <p>
     * An edge onto a component of a neighbour drawn <b>whole</b> lands on that neighbour's box, and two such
     * edges of one kind are <b>one</b> arrow carrying both sets of labels - two parallel arrows between the
     * same pair of boxes say nothing the one does not. An edge whose ends land on the same box draws nothing.
     */
    private static List<Edge> arrowsOf(List<Edge> edges, List<Node> drawn, List<DrawnSystem> openSystems) {
        Map<Node.Key, Node> boxes = new LinkedHashMap<>();
        drawn.forEach(node -> boxes.put(node.key(), node));

        Map<EdgeKey, Edge> byKey = new LinkedHashMap<>();
        Map<EdgeKey, Set<String>> labelsByKey = new LinkedHashMap<>();
        for (Edge edge : edges) {
            Node from = boxOf(edge.from(), boxes, openSystems);
            Node to = boxOf(edge.to(), boxes, openSystems);
            if (from == null || to == null || from.key().equals(to.key())) {
                continue;
            }
            EdgeKey key = new EdgeKey(from.key(), to.key(), edge.kind());
            byKey.putIfAbsent(key, new Edge(from, to, edge.kind(), List.of()));
            labelsByKey.computeIfAbsent(key, ignored -> new TreeSet<>()).addAll(edge.labels());
        }
        List<Edge> arrows = new ArrayList<>();
        byKey.forEach((key, arrow) -> arrows.add(new Edge(arrow.from(), arrow.to(), arrow.kind(),
                List.copyOf(labelsByKey.get(key)))));
        arrows.sort(Comparator.comparing((Edge edge) -> edge.from().label(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(edge -> edge.to().label(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(edge -> edge.kind().name()));
        return arrows;
    }

    /**
     * The box that stands for an end.
     * <p>
     * Its own where it has one. A component of a neighbour drawn <b>whole</b> is represented by that
     * neighbour's box. And a relation that names only a neighbour which is drawn <b>open</b> lands on the
     * package of that neighbour - the alternative is an arrow the diagram drops, and a relation that
     * silently loses its arrow is the one thing the page's table cannot make up for.
     *
     * @return null where the end has no box and its system has none either
     */
    private static Node boxOf(Node end, Map<Node.Key, Node> boxes, List<DrawnSystem> openSystems) {
        Node own = boxes.get(end.key());
        if (own != null) {
            return own;
        }
        if (end.kind() == NodeKind.NEIGHBOUR_COMPONENT) {
            return boxes.get(new Node(NodeKind.NEIGHBOUR_SYSTEM, end.systemName(), end.systemSlug(), null,
                    null).key());
        }
        if (end.kind() == NodeKind.NEIGHBOUR_SYSTEM) {
            return openSystems.stream()
                    .filter(open -> open.name().equalsIgnoreCase(end.systemName()))
                    .findFirst()
                    .map(open -> end)
                    .orElse(null);
        }
        return null;
    }

    /** The systems this component reaches, in the alphabet, bounded. Each is a candidate for a box. */
    private static List<Node> reachedSystemsOf(List<Edge> edges, Node self, int maxSystems) {
        Map<String, Node> byName = new LinkedHashMap<>();
        for (Edge edge : edges) {
            Node counterpart = edge.counterpartOf(self);
            if (counterpart.kind() == NodeKind.NEIGHBOUR_COMPONENT
                || counterpart.kind() == NodeKind.NEIGHBOUR_SYSTEM) {
                byName.putIfAbsent(counterpart.systemName().toLowerCase(Locale.ROOT),
                        new Node(NodeKind.NEIGHBOUR_SYSTEM, counterpart.systemName(),
                                counterpart.systemSlug(), null, null));
            }
        }
        List<Node> systems = new ArrayList<>(byName.values());
        systems.sort(Comparator.comparing(Node::label, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(systems.subList(0, Math.min(maxSystems, systems.size())));
    }

    /** The counterparts of one kind, in the alphabet and without repetition. */
    private static List<Node> counterpartsOf(List<Edge> edges, Node self, NodeKind kind) {
        Map<Node.Key, Node> byKey = new LinkedHashMap<>();
        for (Edge edge : edges) {
            Node counterpart = edge.counterpartOf(self);
            if (counterpart.kind() == kind) {
                byKey.putIfAbsent(counterpart.key(), counterpart);
            }
        }
        List<Node> nodes = new ArrayList<>(byKey.values());
        nodes.sort(Comparator.comparing(Node::label, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(nodes);
    }

    /** The counterpart components inside one other system, in the alphabet. */
    private static List<Node> counterpartsIn(List<Edge> edges, Node self, String systemName) {
        return counterpartsOf(edges, self, NodeKind.NEIGHBOUR_COMPONENT).stream()
                .filter(node -> node.systemName().equalsIgnoreCase(systemName))
                .toList();
    }

    /** The two ends of one relation, or null where it says nothing about this component. */
    private static Ends endsOf(SystemRelation relation, Nodes nodes) {
        // A REST call points from the caller to the provider; a message points the other way.
        boolean restApi = relation.kind() == RelationKind.REST_API;
        Node from = nodes.of(restApi ? relation.consumerSystem() : relation.providerSystem(),
                restApi ? relation.consumer() : relation.provider());
        Node to = nodes.of(restApi ? relation.providerSystem() : relation.consumerSystem(),
                restApi ? relation.provider() : relation.consumer());
        if (from == null || to == null) {
            return null;
        }
        // By identity and not by name: another system's component may be called what this one is called, and
        // comparing the names would drop that relation as a self-loop.
        boolean touchesThis = from.key().equals(nodes.self().key()) || to.key().equals(nodes.self().key());
        if (!touchesThis || from.key().equals(to.key())) {
            return null;
        }
        return new Ends(from, to);
    }

    private record Ends(Node from, Node to) {
    }

    /** What tells two edges apart: the two ends and the kind, as the parts rather than as one joined string. */
    private record EdgeKey(Node.Key from, Node.Key to, RelationKind kind) {
    }

    /** Every counterpart this component exchanges anything with, drawn or not, in the alphabet. */
    public List<Node> counterparts() {
        Map<Node.Key, Node> byKey = new LinkedHashMap<>();
        Node self = selfNode();
        for (Edge edge : edges) {
            Node counterpart = edge.counterpartOf(self);
            byKey.putIfAbsent(counterpart.key(), counterpart);
        }
        List<Node> nodes = new ArrayList<>(byKey.values());
        nodes.sort(Comparator.comparing(Node::label, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(nodes);
    }

    /**
     * The counterparts the model cannot place: it names the component and not the system that owns it. They
     * are never drawn, and the page's table carries them like every other relation.
     */
    public List<Node> unplaced() {
        return counterparts().stream()
                .filter(node -> node.kind() == NodeKind.COMPONENT_OF_UNKNOWN_SYSTEM)
                .toList();
    }

    /** The systems drawn open, this component's own first, each with the component boxes inside it. */
    public List<DrawnSystem> drawnSystemsWithComponents() {
        return openSystemsOf(drawn);
    }

    /**
     * The same, over a set of boxes: what the arrows are mapped through as well, so that an arrow cannot land
     * on a package the diagram does not draw.
     */
    private static List<DrawnSystem> openSystemsOf(List<Node> drawn) {
        Map<String, List<Node>> bySystem = new LinkedHashMap<>();
        Map<String, String> slugBySystem = new LinkedHashMap<>();
        for (Node node : drawn) {
            if (node.isSystem()) {
                continue;
            }
            bySystem.computeIfAbsent(node.systemName(), ignored -> new ArrayList<>()).add(node);
            slugBySystem.putIfAbsent(node.systemName(), node.systemSlug());
        }
        return bySystem.entrySet().stream()
                .map(entry -> new DrawnSystem(entry.getKey(), slugBySystem.get(entry.getKey()),
                        entry.getValue()))
                .toList();
    }

    /** The neighbours drawn as a single box: none of their counterpart components has one. */
    public List<Node> drawnWholeSystems() {
        return drawn.stream().filter(Node::isSystem).toList();
    }

    /** How many counterparts the diagram has no box for. The page's table lists all of them anyway. */
    public int truncated() {
        Set<Node.Key> drawnKeys = new LinkedHashSet<>();
        drawn.forEach(node -> drawnKeys.add(node.key()));
        return (int) counterparts().stream()
                .filter(node -> node.kind() != NodeKind.COMPONENT_OF_UNKNOWN_SYSTEM)
                .filter(node -> !drawnKeys.contains(node.key()))
                // A component of a neighbour drawn whole is not left out: its relation is on that box.
                .filter(node -> node.kind() != NodeKind.NEIGHBOUR_COMPONENT
                                || drawnWholeSystems().stream().noneMatch(
                                        whole -> whole.systemName().equalsIgnoreCase(node.systemName())))
                .count();
    }

    public boolean isEmpty() {
        return edges.isEmpty();
    }

    private Node selfNode() {
        return new Node(NodeKind.SELF, system.name(), system.slug(), component.name(), component.slug());
    }

    /**
     * Resolves a relation's end onto a box, and hands out one node per identity.
     * <p>
     * <b>The index is built once per view</b> rather than looked up per relation: this runs over the whole
     * landscape's relations for every component page of a publication, and a scan per end would make it cost
     * the relation count times the system count.
     * <p>
     * <b>The model's spelling wins.</b> A relation carries whatever the architecture repository stored, and a
     * system reached under an alias in one relation and under its name in another would otherwise be two
     * boxes for one system, each holding some of its components.
     */
    private static final class Nodes {

        private final ArchitectureModel model;
        private final DocumentedSystem system;
        private final Node self;
        private final Map<String, DocumentedSystem> systemsByName = new LinkedHashMap<>();
        private final Map<Node.Key, Node> interned = new LinkedHashMap<>();

        private Nodes(ArchitectureModel model, DocumentedSystem system, DocumentedComponent component) {
            this.model = model;
            this.system = system;
            this.self = new Node(NodeKind.SELF, system.name(), system.slug(), component.name(),
                    component.slug());
            for (DocumentedSystem documented : model.systems()) {
                systemsByName.putIfAbsent(folded(documented.name()), documented);
                documented.aliases().forEach(alias -> systemsByName.putIfAbsent(folded(alias), documented));
            }
        }

        private Node self() {
            return self;
        }

        /** The box one end of a relation belongs on, or null where the relation names no end at all. */
        private Node of(String systemName, String componentName) {
            if (isBlank(componentName)) {
                // Only the system is known, so the system is the box - and where neither is, there is no end.
                if (isBlank(systemName) || namesThisSystem(systemName)) {
                    return null;
                }
                return node(NodeKind.NEIGHBOUR_SYSTEM, owner(systemName, null), systemName, null);
            }
            DocumentedSystem owner = owner(systemName, componentName);
            if (owner == null) {
                // The component is known and its system is not. Kept as a relation, never drawn: a box
                // outside every system would read as a component of no system, which is not what this says.
                return node(NodeKind.COMPONENT_OF_UNKNOWN_SYSTEM, null, systemName, componentName);
            }
            NodeKind kind = owner.name().equalsIgnoreCase(system.name())
                    ? (componentName.equalsIgnoreCase(self.componentName()) ? NodeKind.SELF : NodeKind.SIBLING)
                    : NodeKind.NEIGHBOUR_COMPONENT;
            return node(kind, owner, owner.name(), componentName);
        }

        /**
         * Whether an end that names only a system names the one this component belongs to.
         * <p>
         * Such an end is no counterpart: it says the relation touches this system, which is where the
         * component already is. Drawn, it would be a whole box for this system beside the package the view
         * opens for it - both keyed on the system alone, so PlantUML would be handed one alias twice and
         * render an error box, which fails no build. The system-level view has the same guard in
         * {@code SystemRelation.counterpartSystemOf}.
         */
        private boolean namesThisSystem(String systemName) {
            if (systemName.equalsIgnoreCase(system.name())) {
                return true;
            }
            DocumentedSystem named = owner(systemName, null);
            return named != null && named.name().equalsIgnoreCase(system.name());
        }

        /**
         * The system that owns an end. Named, it is resolved by name or alias; unnamed, only an unambiguous
         * owner will do - exactly one system of this landscape having a component of that name means the
         * export merely left the system out, while two mean nothing can be concluded.
         */
        private DocumentedSystem owner(String systemName, String componentName) {
            if (!isBlank(systemName)) {
                return systemsByName.get(folded(systemName));
            }
            if (isBlank(componentName)) {
                return null;
            }
            List<DocumentedSystem> owners = model.systemsOf(componentName);
            return owners.size() == 1 ? owners.getFirst() : null;
        }

        private Node node(NodeKind kind, DocumentedSystem owner, String systemName, String componentName) {
            String resolvedSystem = owner != null ? owner.name() : systemName;
            String componentSlug = owner == null || componentName == null ? null
                    : owner.components().stream()
                            .filter(candidate -> candidate.name().equalsIgnoreCase(componentName))
                            .findFirst()
                            .map(DocumentedComponent::slug)
                            .orElse(null);
            String resolvedComponent = owner == null || componentName == null ? componentName
                    : owner.components().stream()
                            .filter(candidate -> candidate.name().equalsIgnoreCase(componentName))
                            .findFirst()
                            .map(DocumentedComponent::name)
                            .orElse(componentName);
            Node node = new Node(kind, resolvedSystem, owner != null ? owner.slug() : null, resolvedComponent,
                    componentSlug);
            return interned.computeIfAbsent(node.key(), ignored -> node);
        }

        private static String folded(String value) {
            return value == null ? null : value.toLowerCase(Locale.ROOT);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
