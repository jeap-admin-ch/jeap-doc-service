package ch.admin.bit.jeap.doc.domain.architecture;

import java.util.List;

/**
 * One operation of an OpenAPI specification, with the tags it declares.
 * <p>
 * The tags stay on the operation because that is what the specification says. {@link #group()} is the one
 * place that decides which of them files the operation.
 *
 * @param method     the HTTP method, upper-cased
 * @param path       the resource path as the specification writes it
 * @param summary    the one-line summary, or null
 * @param deprecated whether the specification marks it deprecated
 * @param tags       the tags it declares, in the order the specification listed them
 */
public record ApiOperation(String method, String path, String summary, boolean deprecated,
                           List<String> tags) {

    public ApiOperation {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    /**
     * The group this operation appears under: its <b>first</b> tag, or {@link RestApiOverview#UNGROUPED}.
     * <p>
     * An operation with several tags appears once. Listing it under each would make the page longer than the
     * API it describes.
     */
    public String group() {
        return tags.isEmpty() ? RestApiOverview.UNGROUPED : tags.getFirst();
    }
}
