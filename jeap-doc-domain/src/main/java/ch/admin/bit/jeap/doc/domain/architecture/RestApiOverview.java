package ch.admin.bit.jeap.doc.domain.architecture;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a component's REST API looks like from the outside: its groups, and the operations in each.
 * <p>
 * An overview, not a rendering of the specification. The page links to the Swagger UI of the architecture
 * repository for that.
 *
 * @param version   the version the specification declares, or null
 * @param serverUrl where the API is served, as the specification's first server says, or null
 * @param groups    the groups, sorted by name with the ungrouped operations last
 */
public record RestApiOverview(String version, String serverUrl, List<ApiGroup> groups) {

    /** What the operations that declare no tag are filed under. */
    public static final String UNGROUPED = "Ungrouped";

    public RestApiOverview {
        groups = groups == null ? List.of() : List.copyOf(groups);
    }

    /**
     * The operations grouped by their first tag.
     * <p>
     * A tag the specification declares and no operation uses is not a group: it would be an empty table
     * promising part of an API that is not there.
     *
     * @param operations      every operation of the specification, each carrying its tags
     * @param tagDescriptions what the specification declares about a tag, by tag name
     */
    public static RestApiOverview of(String version, String serverUrl, List<ApiOperation> operations,
                                     Map<String, String> tagDescriptions) {
        Map<String, List<ApiOperation>> byGroup = new LinkedHashMap<>();
        for (ApiOperation operation : operations) {
            byGroup.computeIfAbsent(operation.group(), ignored -> new ArrayList<>()).add(operation);
        }
        List<ApiGroup> groups = new ArrayList<>();
        byGroup.forEach((name, grouped) -> {
            grouped.sort(Comparator.comparing(ApiOperation::path, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(ApiOperation::method, Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER)));
            groups.add(new ApiGroup(name, tagDescriptions.get(name), List.copyOf(grouped)));
        });
        // The ungrouped operations last, because they are the remainder.
        groups.sort(Comparator.comparingInt((ApiGroup group) -> UNGROUPED.equals(group.name()) ? 1 : 0)
                .thenComparing(ApiGroup::name, String.CASE_INSENSITIVE_ORDER));
        return new RestApiOverview(version, serverUrl, List.copyOf(groups));
    }

    /** Every operation of every group, which is what the count on the page is made of. */
    public List<ApiOperation> operations() {
        return groups.stream().flatMap(group -> group.operations().stream()).toList();
    }

    public boolean isEmpty() {
        return groups.isEmpty();
    }
}
