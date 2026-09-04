package ch.admin.bit.jeap.doc.domain.architecture;

import java.util.Locale;

/**
 * Which side of a message a component is on.
 * <p>
 * The architecture repository names the sides differently for events and commands - publisher and consumer
 * against sender and receiver. This is the one concept behind both.
 */
public enum ContractRole {

    PRODUCES,
    CONSUMES,

    /**
     * A role this service has not been told about.
     * <p>
     * It has its own value rather than defaulting to a consumer. Which side a component is on is the main
     * thing a message page is read for, and a wrong answer there would look right.
     */
    UNKNOWN;

    public static ContractRole of(String role) {
        if (role == null) {
            return UNKNOWN;
        }
        return switch (role.toUpperCase(Locale.ROOT)) {
            case "PUBLISHER", "SENDER" -> PRODUCES;
            case "CONSUMER", "RECEIVER" -> CONSUMES;
            default -> UNKNOWN;
        };
    }
}
