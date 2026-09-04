package ch.admin.bit.jeap.doc.domain.architecture;

import java.time.Instant;

/**
 * Everything the architecture repository knows about one version of one message type: both schemas, and the
 * compatibility the version declares.
 * <p>
 * <b>Replaced in place when it is stored again.</b> A version rarely moves - a changed schema is normally
 * published as a new version - but it is not fixed: the compatibility it declares is derived upstream from the
 * version list, so publishing an intermediate version changes what an already published version answers, and
 * an import re-renders the schemas. That is why a run revalidates what it holds instead of assuming it final,
 * and why the tag that revalidation is made with is stored beside the schemas.
 * <p>
 * It names its system and its message type and points into no model row. The architecture model is replaced
 * wholesale on every import, so a reference into it would take these rows down with it and cost a refetch of
 * every schema of the landscape; the two halves are joined by name when a page is written.
 *
 * @param environment       the environment whose architecture repository this came from
 * @param system            the system that defines the message type, by name
 * @param message           the message type, by name
 * @param version           the version, exactly as the registry spells it
 * @param compatibilityMode the Avro compatibility this version declares against {@link #compatibleVersion},
 *                          e.g. {@code BACKWARD} - or null where the descriptor declares none, which is
 *                          typically the first version of a message type
 * @param compatibleVersion the version this one is compatible with, or null where there is no predecessor
 * @param key               the key schema, or null where the message type has none
 * @param value             the value schema, or null where none was replicated
 * @param etag              the entity tag the upstream served these bytes under, sent back as
 *                          {@code If-None-Match} by the next run that reaches this version
 * @param replicatedAt      when this service stored the row
 */
public record MessageVersionSchemas(
        String environment,
        String system,
        String message,
        String version,
        String compatibilityMode,
        String compatibleVersion,
        MessageSchema key,
        MessageSchema value,
        String etag,
        Instant replicatedAt) {
}
