package ch.admin.bit.jeap.doc.domain.architecture.imports;

import java.time.Instant;

/**
 * One message type version without its schemas: what the comparison between what is stored and what the
 * upstream lists works on, and what a conditional request is made with.
 * <p>
 * The counterpart of {@link ArchitectureArtifactRef}, and like it an index entry can carry the <b>entity tag of
 * the resource it points at</b>: an architecture repository that lists one per version lets a run see without a
 * request that a version has not moved - see {@link #hasSameContentAs}. One that lists none leaves listing silent
 * on whether a version moved, and the tag a run revalidates with is then the one stored beside the schemas when
 * they were last fetched.
 *
 * @param environment the environment whose architecture repository lists it
 * @param system      the system that defines the message type, by name
 * @param message     the message type, by name
 * @param version     the version, exactly as the registry spells it
 * @param contentUrl  where the version resource is, as the index gives it. Null on a reference read from the
 *                    store, which has no reason to keep it
 * @param etag        on a reference read from the store, the tag of the stored copy, sent back as
 *                    {@code If-None-Match} - null on a row stored before the tag was recorded. On an index entry,
 *                    the tag the index lists for the version, which is the tag the version resource answers
 *                    with - null where the upstream lists none
 * @param checkedAt   when this service last stored or confirmed the version - what a run that keeps hitting
 *                    its deadline orders the revalidations by. Null on an index entry
 */
public record MessageVersionRef(String environment, String system, String message, String version,
                                String contentUrl, String etag, Instant checkedAt) {

    /** An entry of an upstream's index that lists no tag: where to fetch it, and nothing about what is stored. */
    public static MessageVersionRef listed(String environment, String system, String message, String version,
                                           String contentUrl) {
        return listed(environment, system, message, version, contentUrl, null);
    }

    /**
     * An entry of the upstream's index: where to fetch it, and the tag the upstream lists for it.
     *
     * @param etag the tag of the version resource as the index lists it, or null where it lists none
     */
    public static MessageVersionRef listed(String environment, String system, String message, String version,
                                           String contentUrl, String etag) {
        return new MessageVersionRef(environment, system, message, version, contentUrl, etag, null);
    }

    /** A version this service holds: its tag and when it was last confirmed, and no URL. */
    public static MessageVersionRef stored(String environment, String system, String message, String version,
                                           String etag, Instant checkedAt) {
        return new MessageVersionRef(environment, system, message, version, null, etag, checkedAt);
    }

    /**
     * Whether the upstream lists the same version as the one already stored, so that it can be confirmed without
     * asking for it.
     * <p>
     * Only on a tag both sides have: an index entry without one says nothing, and a blank tag is not a tag - a
     * row stored without one is fetched in full on every run, and two blanks must not confirm each other.
     */
    public boolean hasSameContentAs(MessageVersionRef stored) {
        return stored != null && etag != null && !etag.isBlank() && etag.equals(stored.etag());
    }

    /**
     * What makes two entries the same version. Without the URL, which is derived from the other three and may
     * be rewritten upstream without anything about the version having changed.
     */
    public String identity() {
        return system + " " + message + " " + version;
    }
}
