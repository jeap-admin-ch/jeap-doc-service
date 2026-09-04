package ch.admin.bit.jeap.doc.domain.architecture;

/**
 * Whether a message is an event or a command - which decides what its contracts are called and which of the two
 * lists of the building block view it appears in.
 */
public enum MessageKind {

    EVENT("Event", "Events", "Publisher", "Consumer"),
    COMMAND("Command", "Commands", "Sender", "Receiver");

    private final String label;
    private final String plural;
    private final String producerRole;
    private final String consumerRole;

    MessageKind(String label, String plural, String producerRole, String consumerRole) {
        this.label = label;
        this.plural = plural;
        this.producerRole = producerRole;
        this.consumerRole = consumerRole;
    }

    public String label() {
        return label;
    }

    public String plural() {
        return plural;
    }

    /**
     * What the side that sends is called: a publisher for an event, a sender for a command.
     */
    public String producerRole() {
        return producerRole;
    }

    /**
     * What the side that receives is called: a consumer for an event, a receiver for a command.
     */
    public String consumerRole() {
        return consumerRole;
    }

    /**
     * Whether the architecture repository named one of the two kinds there are.
     * <p>
     * {@link #of(String)} has to answer with one of them, because a message is filed under one or the other.
     * This is what lets the caller say so before it does.
     */
    public static boolean isKnown(String kind) {
        return "COMMAND".equalsIgnoreCase(kind) || "EVENT".equalsIgnoreCase(kind);
    }

    public static MessageKind of(String kind) {
        return "COMMAND".equalsIgnoreCase(kind) ? COMMAND : EVENT;
    }
}
