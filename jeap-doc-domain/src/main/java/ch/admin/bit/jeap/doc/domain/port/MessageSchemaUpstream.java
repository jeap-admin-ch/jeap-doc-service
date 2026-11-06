package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.architecture.imports.MessageVersionRef;

import java.util.List;

/**
 * Where the Avro schemas of the message type versions are read from.
 * <p>
 * <b>A port rather than a client, because the source is expected to change.</b> The architecture repository
 * renders these schemas today; a successor could render them from the message type registry directly, and this
 * is the seam that makes swapping one for the other a new adapter rather than a change to the store, the import
 * and the pages.
 * <p>
 * <b>A version is revalidated, not fetched once and trusted for ever.</b> It rarely moves - a changed schema is
 * normally published as a new version - but it does: the compatibility a version declares is derived upstream
 * from the version list, so publishing an intermediate version changes what an already published version
 * answers, and an import re-renders the schemas. An upstream that lists the tag of every version in its index
 * lets a run confirm the unchanged ones without a request, the way {@link ArchitectureArtifactUpstream}'s index
 * does for the artifacts. One that lists none is asked about every version, with the stored tag, and a version
 * tagged over its serialized body costs it a read and a hash per ask; that weaker trade is made because the
 * upstream's own contract forbids storing a version once and never asking again.
 */
public interface MessageSchemaUpstream {

    /**
     * Every message type version the upstream of this environment knows, without the schemas.
     * <p>
     * Asked unconditionally, and it is what the run diffs against the store. An entry carries the tag of its
     * version resource where the upstream lists one per version, and null where it does not - a run then
     * revalidates with the tag from the store.
     *
     * @throws ArchitectureModelUnavailableException where the upstream could not be read
     */
    List<MessageVersionRef> index(String environment);

    /**
     * One version, with both schemas.
     *
     * @param knownEtag the tag of the copy already stored, or null where there is none
     * @return the schemas, a confirmation that the stored row is current, or that the version could not be
     *         replicated - see {@link SchemaFetch} for why the last two are not the same answer
     * @throws ArchitectureModelUnavailableException where the upstream could not be read
     */
    SchemaFetch version(String environment, MessageVersionRef ref, String knownEtag);
}
