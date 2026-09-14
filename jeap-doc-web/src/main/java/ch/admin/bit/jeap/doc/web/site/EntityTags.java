package ch.admin.bit.jeap.doc.web.site;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;

import java.util.Arrays;

/**
 * What a reader already holds, and how it is compared.
 * <p>
 * Shared by everything this service serves out of the object storage - a file of a published site and a file
 * of an uploaded microsite - because a second copy of these rules is one that will disagree with the first.
 */
final class EntityTags {

    private EntityTags() {
    }

    /**
     * A <b>weak</b> tag. The object storage's is a strong one, but a strong tag promises the bytes are exactly
     * these, and the container refuses to compress a response carrying one. The documentation is text
     * throughout, so compression is worth more than byte ranges, and revalidation works the same either way.
     */
    static String weak(String storageTag) {
        return storageTag == null ? null : "W/" + quoted(storageTag);
    }

    /**
     * Whether the reader already holds this version. The header carries a list, and a proxy may have weakened
     * the tag on the way, so it is compared entry by entry and without the weak marker.
     */
    static boolean unchanged(HttpServletRequest request, String entityTag) {
        String ifNoneMatch = request.getHeader(HttpHeaders.IF_NONE_MATCH);
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        if ("*".equals(ifNoneMatch.strip())) {
            return true;
        }
        String current = withoutWeakMarker(entityTag);
        return Arrays.stream(ifNoneMatch.split(","))
                .map(String::strip)
                .map(EntityTags::withoutWeakMarker)
                .anyMatch(current::equals);
    }

    private static String withoutWeakMarker(String entityTag) {
        return entityTag.startsWith("W/") ? entityTag.substring(2) : entityTag;
    }

    private static String quoted(String entityTag) {
        return entityTag.startsWith("\"") ? entityTag : "\"" + entityTag + "\"";
    }
}
