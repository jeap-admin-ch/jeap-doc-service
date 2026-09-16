package ch.admin.bit.jeap.doc.domain.architecture.view;

import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessage;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.architecture.MessageKind;
import ch.admin.bit.jeap.doc.domain.architecture.ObservedReactions;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a runtime view draws: which messages and reactions are on it, how the reactions cluster, what each node
 * links to, and the table that says the same thing in words.
 * <p>
 * <b>The shape is decided here and the syntax in the structure template.</b> Nothing in this class knows that
 * the drawing is GraphViz, and nothing in the template decides what is drawn - the same split
 * {@link SystemContext} and {@link WhiteboxView} already follow.
 * <p>
 * <b>A node links only where this site has a page.</b> The observer knows components and message types that
 * the model of this environment does not carry, and a link to a page nothing writes is a {@code 404} the
 * reader is offered rather than a page that is missing.
 *
 * @param messages  the message nodes, in the order they are drawn
 * @param clusters  the reactions, grouped by the trigger and the component they belong to
 * @param triggers  which message makes which reaction happen
 * @param actions   which message a reaction publishes in answer
 * @param rows      the table under the diagram: the complete list, and the searchable one
 */
@Slf4j
public record ReactionView(
        List<MessageNode> messages,
        List<ReactionCluster> clusters,
        List<TriggerEdge> triggers,
        List<ActionEdge> actions,
        List<Row> rows) {

    /** What a message node's label loses, because every message type ends in one of them. */
    private static final List<String> MESSAGE_SUFFIXES = List.of("Event", "Command");

    /** What a component's label loses, because most of them end in one of them. */
    private static final List<String> COMPONENT_SUFFIXES = List.of("-service", "-scs");

    public ReactionView {
        messages = messages == null ? List.of() : List.copyOf(messages);
        clusters = clusters == null ? List.of() : List.copyOf(clusters);
        triggers = triggers == null ? List.of() : List.copyOf(triggers);
        actions = actions == null ? List.of() : List.copyOf(actions);
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    public static ReactionView empty() {
        return new ReactionView(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /**
     * Whether there is anything to draw. <b>An empty view is a chapter that is not written</b>, not a page
     * saying a system has no reactions.
     */
    public boolean isEmpty() {
        return messages.isEmpty() && clusters.isEmpty();
    }

    /**
     * The view of one observed graph, resolved against the landscape it belongs to.
     *
     * @param subject the system whose page this is drawn on, or null on a message page. It is what decides
     *                which messages are drawn as belonging to another system
     */
    public static ReactionView of(ObservedReactions observed, ArchitectureModel model,
                                  DocumentedSystem subject) {
        return of(observed, ReactionModelIndex.of(model), subject);
    }

    /**
     * The same, against a landscape that was indexed once for every graph of it - which is what a build hands
     * over. See {@link ReactionModelIndex}.
     */
    public static ReactionView of(ObservedReactions observed, ReactionModelIndex names,
                                  DocumentedSystem subject) {
        if (observed == null || observed.isEmpty()) {
            return empty();
        }
        ObservedReactions graph = withoutNamelessNodes(observed);
        if (graph.isEmpty()) {
            return empty();
        }
        // Grouped once, because everything below asks the same question per reaction: a stream over every
        // edge per reaction is the graph's own size squared, and a busy component's graph is not small.
        Map<Long, List<ObservedReactions.ObservedTrigger>> triggersOfReaction =
                by(graph.triggers(), ObservedReactions.ObservedTrigger::reactionId);
        Map<Long, List<ObservedReactions.ObservedAction>> actionsOfReaction =
                by(graph.actions(), ObservedReactions.ObservedAction::reactionId);
        List<MessageNode> messages = graph.messages().stream()
                .map(message -> messageNode(message, names, subject))
                .sorted(Comparator.comparing(MessageNode::label).thenComparingLong(MessageNode::id))
                .toList();
        List<ReactionCluster> clusters = clustersOf(graph, names, triggersOfReaction);
        List<TriggerEdge> triggers = graph.triggers().stream()
                .map(trigger -> new TriggerEdge(trigger.messageId(), trigger.reactionId(), trigger.median(),
                        weightOf(trigger.median())))
                .toList();
        List<ActionEdge> actions = graph.actions().stream()
                .map(action -> new ActionEdge(action.reactionId(), action.messageId()))
                .toList();
        return new ReactionView(messages, clusters, triggers, actions,
                rowsOf(messages, clusters, mediansOf(graph), triggersOfReaction, actionsOfReaction));
    }

    /** The edges of a graph by the reaction they belong to, in the order they arrived. */
    private static <T> Map<Long, List<T>> by(List<T> edges, java.util.function.ToLongFunction<T> reaction) {
        Map<Long, List<T>> byReaction = new LinkedHashMap<>();
        edges.forEach(edge -> byReaction
                .computeIfAbsent(reaction.applyAsLong(edge), id -> new ArrayList<>())
                .add(edge));
        return byReaction;
    }

    /**
     * The same graph without the nodes that have no name, and without the edges that led to them.
     * <p>
     * <b>A name is what a node is drawn as</b>, and the reader of an upstream payload deliberately tolerates a
     * field that is absent or is not a string - so one such node would otherwise be a label that is null, and
     * a whole part's site build failing on it. One bad node costs one node.
     */
    private static ObservedReactions withoutNamelessNodes(ObservedReactions observed) {
        List<ObservedReactions.ObservedMessage> messages = observed.messages().stream()
                .filter(message -> isNamed(message.messageType()))
                .toList();
        List<ObservedReactions.ObservedReaction> reactions = observed.reactions().stream()
                .filter(reaction -> isNamed(reaction.component()))
                .toList();
        if (messages.size() == observed.messages().size()
            && reactions.size() == observed.reactions().size()) {
            return observed;
        }
        log.warn("{} message node(s) and {} reaction node(s) of an observed graph carry no name and are left "
                 + "out of it, with the edges that led to them.",
                observed.messages().size() - messages.size(),
                observed.reactions().size() - reactions.size());
        Set<Long> messageIds = messages.stream().map(ObservedReactions.ObservedMessage::id)
                .collect(Collectors.toSet());
        Set<Long> reactionIds = reactions.stream().map(ObservedReactions.ObservedReaction::id)
                .collect(Collectors.toSet());
        return new ObservedReactions(messages, reactions,
                observed.triggers().stream()
                        .filter(trigger -> messageIds.contains(trigger.messageId())
                                           && reactionIds.contains(trigger.reactionId()))
                        .toList(),
                observed.actions().stream()
                        .filter(action -> reactionIds.contains(action.reactionId())
                                          && messageIds.contains(action.messageId()))
                        .toList());
    }

    private static boolean isNamed(String name) {
        return name != null && !name.isBlank();
    }

    /**
     * The reactions, grouped as they are drawn: <b>one cluster per trigger and component</b>.
     * <p>
     * A component reacting to the same message several times is one box with its reactions in it, which is
     * what makes a graph of a busy component readable. A reaction with no trigger at all is its own cluster -
     * the observer can hold one, and leaving it out would lose a component from the picture.
     */
    private static List<ReactionCluster> clustersOf(
            ObservedReactions observed, ReactionModelIndex names,
            Map<Long, List<ObservedReactions.ObservedTrigger>> triggersOfReaction) {
        Map<String, List<ReactionNode>> byCluster = new LinkedHashMap<>();
        Map<String, Long> triggerOf = new LinkedHashMap<>();
        Map<String, String> componentOf = new LinkedHashMap<>();
        for (ObservedReactions.ObservedReaction reaction : observed.reactions()) {
            Long trigger = firstTriggerOf(triggersOfReaction, reaction.id());
            String key = trigger + "|" + reaction.component();
            byCluster.computeIfAbsent(key, id -> new ArrayList<>())
                    .add(new ReactionNode(reaction.id(), Long.toString(reaction.id())));
            triggerOf.put(key, trigger);
            componentOf.put(key, reaction.component());
        }
        List<ReactionCluster> clusters = new ArrayList<>();
        byCluster.forEach((key, reactions) -> {
            String component = componentOf.get(key);
            Optional<DocumentedSystem> owner = ownerOf(names, component);
            clusters.add(new ReactionCluster(component, labelOfComponent(component),
                    owner.flatMap(system -> names.componentSlug(system, component)).orElse(null),
                    owner.map(DocumentedSystem::slug).orElse(null), triggerOf.get(key), reactions));
        });
        return List.copyOf(clusters);
    }

    private static Long firstTriggerOf(Map<Long, List<ObservedReactions.ObservedTrigger>> triggersOfReaction,
                                       long reaction) {
        List<ObservedReactions.ObservedTrigger> triggers = triggersOfReaction.get(reaction);
        return triggers == null || triggers.isEmpty() ? null : triggers.getFirst().messageId();
    }

    /**
     * One row per reaction: what triggered it, which component reacted, what it published in answer, and how
     * often it was seen. <b>The complete list</b> - a diagram the plugin refuses as too large leaves the table
     * as the whole of the page's content, and the table is the only representation the browser's own
     * find-in-page can search.
     */
    private static List<Row> rowsOf(
            List<MessageNode> messages, List<ReactionCluster> clusters,
            Map<Long, Integer> medianOfReaction,
            Map<Long, List<ObservedReactions.ObservedTrigger>> triggersOfReaction,
            Map<Long, List<ObservedReactions.ObservedAction>> actionsOfReaction) {
        Map<Long, MessageNode> byId = new LinkedHashMap<>();
        messages.forEach(message -> byId.put(message.id(), message));
        List<Row> rows = new ArrayList<>();
        for (ReactionCluster cluster : clusters) {
            for (ReactionNode reaction : cluster.reactions()) {
                List<String> answers = actionsOfReaction
                        .getOrDefault(reaction.id(), List.of()).stream()
                        .map(action -> byId.get(action.messageId()))
                        .filter(Objects::nonNull)
                        .map(ReactionView::named)
                        .sorted()
                        .toList();
                Integer median = medianOf(reaction.id(), medianOfReaction, triggersOfReaction);
                MessageNode trigger = cluster.triggerMessageId() == null ? null
                        : byId.get(cluster.triggerMessageId());
                rows.add(new Row(trigger == null ? null : named(trigger), cluster.component(), answers,
                        median));
            }
        }
        rows.sort(Comparator.comparing((Row row) -> row.trigger() == null ? "" : row.trigger())
                .thenComparing(Row::component));
        return List.copyOf(rows);
    }

    /** The number the observer counted for each reaction, where it sent one. */
    private static Map<Long, Integer> mediansOf(ObservedReactions graph) {
        Map<Long, Integer> medians = new LinkedHashMap<>();
        graph.reactions().stream()
                .filter(reaction -> reaction.median() != null)
                .forEach(reaction -> medians.put(reaction.id(), reaction.median()));
        return medians;
    }

    /**
     * How often a reaction was seen: <b>its own number</b>, and its trigger's only where the observer sent
     * none on the node.
     * <p>
     * The observer counts per reaction and has carried the number on the reaction node since its 12.2.0. The
     * trigger edge is the fallback, because a graph stored before that version has nothing on the node - and
     * a reaction that no message triggered has no edge at all, which is what the node's number is for.
     */
    private static Integer medianOf(long reaction, Map<Long, Integer> medianOfReaction,
                                    Map<Long, List<ObservedReactions.ObservedTrigger>> triggersOfReaction) {
        Integer median = medianOfReaction.get(reaction);
        if (median != null) {
            return median;
        }
        // The trigger first and its median after it: a median is null where the observer kept no number, and
        // finding one in a stream of nulls throws.
        return triggersOfReaction.getOrDefault(reaction, List.of()).stream()
                .findFirst()
                .map(ObservedReactions.ObservedTrigger::median)
                .orElse(null);
    }

    /**
     * A message as the table names it: the type, and the variant after it where there is one.
     * <p>
     * The variant is what tells two rows apart. Four reactions to four variants of one message type are four
     * rows of the same three names without it, and the diagram above them draws four distinct nodes.
     */
    private static String named(MessageNode message) {
        return message.variant() == null || message.variant().isBlank()
                ? message.name()
                : message.name() + " [" + message.variant() + "]";
    }

    private static MessageNode messageNode(ObservedReactions.ObservedMessage message,
                                           ReactionModelIndex names, DocumentedSystem subject) {
        Optional<DocumentedSystem> definedIn = names.systemOfMessage(message.messageType());
        Optional<DocumentedMessage> documented = names.message(message.messageType());
        boolean ofAnotherSystem = subject != null && definedIn
                .map(system -> !system.slug().equals(subject.slug()))
                .orElse(true);
        return new MessageNode(message.id(), message.messageType(), message.variant(),
                labelOfMessage(message.messageType()), definedIn.map(DocumentedSystem::slug).orElse(null),
                documented.map(DocumentedMessage::slug).orElse(null),
                documented.map(DocumentedMessage::kind).orElse(null), ofAnotherSystem);
    }

    /**
     * The system that documents a component, which is what a reaction node links into. A component two
     * systems have is nobody's here: a link would have to guess which, so none is written.
     */
    private static Optional<DocumentedSystem> ownerOf(ReactionModelIndex names, String component) {
        List<DocumentedSystem> systems = names.systemsOfComponent(component);
        return systems.size() == 1 ? Optional.of(systems.getFirst()) : Optional.empty();
    }

    /**
     * A trigger's line weight: one, two or three by the number of digits of the median, so that a busier
     * trigger is a thicker arrow without any one of them dominating the picture.
     */
    private static int weightOf(Integer median) {
        if (median == null || median < 10) {
            return 1;
        }
        return median < 100 ? 2 : 3;
    }

    /** The message type without the suffix every message type carries, which says nothing on a graph. */
    private static String labelOfMessage(String messageType) {
        return withoutSuffix(messageType, MESSAGE_SUFFIXES);
    }

    /** The component without the suffix most of them carry. */
    private static String labelOfComponent(String component) {
        return withoutSuffix(component, COMPONENT_SUFFIXES);
    }

    private static String withoutSuffix(String name, List<String> suffixes) {
        if (name == null) {
            return null;
        }
        for (String suffix : suffixes) {
            if (name.length() > suffix.length() && name.endsWith(suffix)) {
                return name.substring(0, name.length() - suffix.length());
            }
        }
        return name;
    }

    /**
     * A message on the graph.
     *
     * @param name            the message type as it is documented, which is what the table lists
     * @param variant         the variant, or null. Drawn on a second line, because two variants of one type
     *                        are two nodes that would otherwise be identical
     * @param label           the name without its {@code Event} or {@code Command} suffix
     * @param systemSlug      the system that defines it, or null when this landscape does not
     * @param messageSlug     the page's segment, or null when there is no page to link
     * @param ofAnotherSystem whether it belongs to a system other than the one whose page this is
     */
    public record MessageNode(long id, String name, String variant, String label, String systemSlug,
                              String messageSlug, MessageKind kind, boolean ofAnotherSystem) {

        /** Whether a link may be written: this run has to have written the page it would point at. */
        public boolean isLinkable() {
            return systemSlug != null && messageSlug != null && kind != null;
        }
    }

    /**
     * The reactions of one component to one trigger, drawn as a box around them.
     *
     * @param triggerMessageId the message they all answer, or null for reactions the observer holds without
     *                         one
     * @param componentSlug    the component's page segment, or null when this landscape does not document it
     *                         - two systems with a component of that name is also null, because a link would
     *                         have to guess which
     */
    public record ReactionCluster(String component, String label, String componentSlug, String systemSlug,
                                  Long triggerMessageId, List<ReactionNode> reactions) {

        public ReactionCluster {
            reactions = reactions == null ? List.of() : List.copyOf(reactions);
        }

        public boolean isLinkable() {
            return systemSlug != null && componentSlug != null;
        }
    }

    /**
     * One reaction. <b>The label is the observer's row id</b> and means nothing to a reader - it is what makes
     * the node unique and what a deep link addresses, and it is the only name the observer's graph carries.
     */
    public record ReactionNode(long id, String label) {
    }

    /**
     * @param weight the line width, 1 to 3, from the median
     */
    public record TriggerEdge(long messageId, long reactionId, Integer median, int weight) {
    }

    public record ActionEdge(long reactionId, long messageId) {
    }

    /**
     * One row of the table under the diagram.
     *
     * @param trigger the message that triggered it with its variant, or null where the observer holds a
     *                reaction without one
     * @param answers the messages published in answer with their variants, sorted, possibly none
     * @param median  how often it was observed, or null
     */
    public record Row(String trigger, String component, List<String> answers, Integer median) {

        public Row {
            answers = answers == null ? List.of() : List.copyOf(answers);
        }
    }
}
