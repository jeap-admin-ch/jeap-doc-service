package ch.admin.bit.jeap.doc.domain.architecture;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The table groups an instance configures, by component. Keyed by the component's name alone, because the
 * architecture repository keeps component names unique.
 */
public final class SchemaTableGroups {

    /** No component has a configured group. */
    public static final SchemaTableGroups NONE = new SchemaTableGroups(Map.of());

    private final Map<String, List<SchemaTableGroup>> byComponent;

    private SchemaTableGroups(Map<String, List<SchemaTableGroup>> byComponent) {
        this.byComponent = byComponent;
    }

    /**
     * The groups of every component, in the order each component lists them. Throws where two groups of one
     * component share a name, because the page would then show two entries under one heading.
     */
    public static SchemaTableGroups of(Map<String, List<SchemaTableGroup>> byComponent) {
        if (byComponent == null || byComponent.isEmpty()) {
            return NONE;
        }
        Map<String, List<SchemaTableGroup>> groups = new LinkedHashMap<>();
        byComponent.forEach((component, list) -> {
            if (component == null || component.isBlank()) {
                throw new IllegalArgumentException("a component without a name has groups");
            }
            List<SchemaTableGroup> configured = list == null ? List.of() : List.copyOf(list);
            Set<String> names = new HashSet<>();
            for (SchemaTableGroup group : configured) {
                if (!names.add(group.name().toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException(
                            "the component '" + component + "' has two groups called '" + group.name() + "'");
                }
            }
            groups.put(component, configured);
        });
        return new SchemaTableGroups(Map.copyOf(groups));
    }

    /** The groups configured for this component, in their configured order. Empty where there are none. */
    public List<SchemaTableGroup> forComponent(String component) {
        return component == null ? List.of() : byComponent.getOrDefault(component, List.of());
    }
}
