package ch.admin.bit.jeap.doc.domain.architecture;

import java.util.List;

/**
 * A component's contract on a message: that it produces or consumes it, on which topic, in which versions.
 *
 * @param role      which side of the message the component is on
 * @param component the component holding the contract
 * @param system    the system that component belongs to, or null when it is not known
 * @param topic     the topic the message travels on
 * @param versions  the versions this component has contracted for
 */
public record MessageContract(
        ContractRole role,
        String component,
        String system,
        String topic,
        List<String> versions) {

    public MessageContract {
        versions = versions == null ? List.of() : List.copyOf(versions);
    }

    public boolean produces() {
        return role == ContractRole.PRODUCES;
    }
}
