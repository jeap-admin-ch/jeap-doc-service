package ch.admin.bit.jeap.doc.domain.architecture;

import java.util.List;

/**
 * A group of operations of one REST API: one tag of the specification, and what is filed under it.
 *
 * @param name        the tag, or {@link RestApiOverview#UNGROUPED} for the operations that declare none
 * @param description what the specification says about the tag, or null
 * @param operations  the operations of the group, sorted by path and method
 */
public record ApiGroup(String name, String description, List<ApiOperation> operations) {

    public ApiGroup {
        operations = operations == null ? List.of() : List.copyOf(operations);
    }
}
