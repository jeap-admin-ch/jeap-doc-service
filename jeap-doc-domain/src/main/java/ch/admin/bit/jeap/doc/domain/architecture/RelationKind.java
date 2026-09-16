package ch.admin.bit.jeap.doc.domain.architecture;

import java.util.Locale;

/**
 * What connects two components: a REST call, an event or a command.
 * <p>
 * A kind this service has not heard of becomes {@link #OTHER} rather than failing the build. The importers
 * that fill the architecture repository gain new kinds without asking.
 */
public enum RelationKind {

    REST_API("uses", "REST", "REST Call", "REST Calls"),
    EVENT("publishes", "Event", "Event", "Events"),
    COMMAND("sends", "Command", "Command", "Commands"),
    OTHER("relates to", "Other", "Relation", "Relations");

    private final String verb;
    private final String type;
    private final String singular;
    private final String plural;

    RelationKind(String verb, String type, String singular, String plural) {
        this.verb = verb;
        this.type = type;
        this.singular = singular;
        this.plural = plural;
    }

    /**
     * What the relations tables show in their Type column. A noun rather than a verb, because a table row
     * does not say which end the verb belongs to.
     */
    public String type() {
        return type;
    }

    /** What a relation says about itself when it has no message and no path to name. */
    public String verb() {
        return verb;
    }

    /**
     * How many of this kind travel along one arrow, where the names of all of them would not fit on it:
     * {@code 5 Events}, {@code 6 Commands}, {@code 3 REST Calls}, and {@code 1 Event} for a single one.
     * <p>
     * The whole phrase is built here rather than by appending an <i>s</i> somewhere else, because
     * {@code REST Call} does not pluralise like the others and because a caller that has the count has no
     * business knowing the grammar.
     */
    public String count(int howMany) {
        return howMany + " " + (howMany == 1 ? singular : plural);
    }

    /**
     * The kind by the name the architecture repository uses, never failing on one it does not know.
     */
    public static RelationKind of(String type) {
        if (type == null) {
            return OTHER;
        }
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "REST_API_RELATION" -> REST_API;
            case "EVENT_RELATION" -> EVENT;
            case "COMMAND_RELATION" -> COMMAND;
            default -> OTHER;
        };
    }
}
