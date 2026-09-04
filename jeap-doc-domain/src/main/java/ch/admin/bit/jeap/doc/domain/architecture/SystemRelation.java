package ch.admin.bit.jeap.doc.domain.architecture;

/**
 * One active relation between two components, as the architecture repository has it.
 * <p>
 * Both ends carry the system they belong to, so a context view can be drawn without looking a component up
 * again.
 *
 * @param kind           what connects the two
 * @param consumerSystem the system of the consuming component, or null when it is not known
 * @param consumer       the consuming component, or null when the relation has no known consumer
 * @param providerSystem the system of the providing component, or null when it is not known
 * @param provider       the providing component, or null when the relation has no known provider
 * @param messageType    the event or command that travels, for message relations
 * @param method         the HTTP method, for REST relations
 * @param path           the resource path, for REST relations
 * @param pactUrl        the Pact contract, for REST relations that have one
 */
public record SystemRelation(
        RelationKind kind,
        String consumerSystem,
        String consumer,
        String providerSystem,
        String provider,
        String messageType,
        String method,
        String path,
        String pactUrl) {

    /**
     * What the edge says: the message that travels, or the resource that is called.
     */
    public String label() {
        if (messageType != null && !messageType.isBlank()) {
            return messageType;
        }
        if (path != null && !path.isBlank()) {
            return method == null || method.isBlank() ? path : method + " " + path;
        }
        return kind.verb();
    }

    /**
     * Whether this relation touches the given system at either end.
     */
    public boolean touches(String system) {
        return system != null && (system.equalsIgnoreCase(consumerSystem) || system.equalsIgnoreCase(providerSystem));
    }

    /** Whether both ends are inside the given system, which the whitebox view draws as an internal edge. */
    public boolean isInternalTo(String system) {
        return system != null && system.equalsIgnoreCase(consumerSystem) && system.equalsIgnoreCase(providerSystem);
    }

    /**
     * The system at the other end, seen from the given one, or null when the relation does not leave it or the
     * counterpart is unknown.
     */
    public String counterpartSystemOf(String system) {
        if (system == null) {
            return null;
        }
        if (system.equalsIgnoreCase(providerSystem)) {
            return system.equalsIgnoreCase(consumerSystem) ? null : consumerSystem;
        }
        if (system.equalsIgnoreCase(consumerSystem)) {
            return providerSystem;
        }
        return null;
    }
}
