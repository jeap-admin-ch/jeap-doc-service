package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.architecture.imports.MessageVersionRef;
import ch.admin.bit.jeap.doc.domain.architecture.MessageVersionSchemas;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Where the replicated Avro schemas of the message type versions are kept.
 * <p>
 * A row names its system and its message type and points into no model row - the model is replaced wholesale on
 * every import, and a reference into it would either be deleted with it, throwing away schemas that have not
 * changed, or stop the model from being replaceable at all. The two halves are joined by name when a page is
 * written, which is the same trade {@link ArchitectureArtifactRepository} makes for the same reason.
 */
public interface MessageSchemaRepository {

    /**
     * Every version of one environment that is stored, <b>without its schemas</b>: what deciding which versions
     * to fetch works on, and what carries the tag each is revalidated with. Reading the renderings to find out
     * which versions exist would defeat the replication.
     */
    List<MessageVersionRef> findRefs(String environment);

    /**
     * The schemas of one system, for a generation run that documents it.
     * <p>
     * <b>Per system, and that is deliberate.</b> A landscape's renderings together are far more text than a
     * build has any reason to hold while the site generator runs, and unlike the model these rows need no
     * consistent snapshot: a version is one self-contained row, replaced whole or not at all, so there is
     * nothing for a concurrent import to tear. It is the shape {@link ArchitectureArtifactRepository#findAll} was written for, and the two
     * should stay twins - though that one has no reader yet, and this one does.
     */
    List<MessageVersionSchemas> findAll(String environment, String system);

    /** Stores one version, replacing the row where this service already holds it. */
    void store(MessageVersionSchemas schemas);

    /**
     * Records that a stored version was revalidated and is still current, without rewriting its schemas.
     * <p>
     * What a run that keeps hitting its deadline orders the next one by, so that every run revalidates the
     * versions it has looked at least recently rather than the same first few.
     */
    void confirm(String environment, String system, String message, String version, Instant checkedAt);

    /** Removes versions the architecture repository no longer lists. */
    void remove(Collection<MessageVersionRef> versions);
}
