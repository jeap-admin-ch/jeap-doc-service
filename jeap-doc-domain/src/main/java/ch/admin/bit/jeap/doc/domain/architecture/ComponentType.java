package ch.admin.bit.jeap.doc.domain.architecture;

/**
 * What kind of building block a component is, with the label a page shows for it.
 * <p>
 * As with {@link RelationKind}, an unknown value becomes {@link #UNKNOWN} rather than failing the build.
 */
public enum ComponentType {

    BACKEND_SERVICE("Backend Service"),
    FRONTEND("Frontend"),
    GATEWAY("Gateway"),
    MOBILE_APP("Mobile App"),
    SELF_CONTAINED_SYSTEM("Self-Contained System"),
    UNKNOWN("Unknown");

    private final String label;

    ComponentType(String label) {
        this.label = label;
    }

    /**
     * What a reader sees, rather than the constant.
     */
    public String label() {
        return label;
    }

    public static ComponentType of(String type) {
        if (type == null) {
            return UNKNOWN;
        }
        for (ComponentType candidate : values()) {
            if (candidate.name().equalsIgnoreCase(type)) {
                return candidate;
            }
        }
        return UNKNOWN;
    }
}
