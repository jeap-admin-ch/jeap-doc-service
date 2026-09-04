package ch.admin.bit.jeap.doc.domain.architecture.imports;

import java.time.Instant;

/**
 * One message type version without its schemas: what the comparison between what is stored and what the
 * upstream lists works on, and what a conditional request is made with.
 * <p>
 * The counterpart of {@link ArchitectureArtifactRef}. It differs in one way that shapes the whole step: the
 * index carries <b>no entity tag per version</b>, so listing does not say whether a version moved. The tag that
 * a run revalidates with is the one stored beside the schemas when they were last fetched.
 *
 * @param environment the environment whose architecture repository lists it
 * @param system      the system that defines the message type, by name
 * @param message     the message type, by name
 * @param version     the version, exactly as the registry spells it
 * @param contentUrl  where the version resource is, as the index gives it. Null on a reference read from the
 *                    store, which has no reason to keep it
 * @param etag        the tag of the stored copy, sent back as {@code If-None-Match}. Null on an index entry,
 *                    which carries none, and on a row stored before the tag was recorded
 * @param checkedAt   when this service last stored or confirmed the version - what a run that keeps hitting
 *                    its deadline orders the revalidations by. Null on an index entry
 */
public record MessageVersionRef(String environment, String system, String message, String version,
                                String contentUrl, String etag, Instant checkedAt) {

    /** An entry of the upstream's index: where to fetch it, and nothing about what is stored. */
    public static MessageVersionRef listed(String environment, String system, String message, String version,
                                           String contentUrl) {
        return new MessageVersionRef(environment, system, message, version, contentUrl, null, null);
    }

    /** A version this service holds: its tag and when it was last confirmed, and no URL. */
    public static MessageVersionRef stored(String environment, String system, String message, String version,
                                           String etag, Instant checkedAt) {
        return new MessageVersionRef(environment, system, message, version, null, etag, checkedAt);
    }

    /**
     * What makes two entries the same version. Without the URL, which is derived from the other three and may
     * be rewritten upstream without anything about the version having changed.
     */
    public String identity() {
        return system + " " + message + " " + version;
    }
}
