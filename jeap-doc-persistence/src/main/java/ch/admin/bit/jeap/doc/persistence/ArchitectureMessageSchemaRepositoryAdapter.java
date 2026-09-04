package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.architecture.MessageSchema;
import ch.admin.bit.jeap.doc.domain.architecture.imports.MessageVersionRef;
import ch.admin.bit.jeap.doc.domain.architecture.MessageVersionSchemas;
import ch.admin.bit.jeap.doc.domain.port.MessageSchemaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * The replicated message type schemas, in this service's own database.
 * <p>
 * There is <b>no snapshot to read</b>, unlike the model's adapter. A version is self-contained: an import
 * replaces one row at a time and removes ones the upstream no longer lists, so a page reading a system's
 * schemas can see a version that has just been refreshed or one that has just gone, but never a torn one.
 * <p>
 * {@link #store} writes a version whether or not it is already stored. The unique index on the four columns
 * that identify a version is what an insert would violate, and a violation fails the whole import kind, so the
 * row is looked up and replaced rather than inserted blind.
 */
@Component
@RequiredArgsConstructor
class ArchitectureMessageSchemaRepositoryAdapter implements MessageSchemaRepository {

    private final ArchitectureMessageSchemaJpaRepository schemas;

    /**
     * The versions of one environment that are stored, without their renderings.
     * <p>
     * Read as a projection, so the two rendering columns are not in the {@code select} at all: they are the
     * largest columns in this database and this answers which versions exist, not what is in them. Reading
     * them here would pull every replicated schema of the environment into the heap once per import run.
     */
    @Override
    @Transactional(readOnly = true)
    public List<MessageVersionRef> findRefs(String environment) {
        return schemas.findByEnvironment(environment).stream()
                .map(view -> MessageVersionRef.stored(view.getEnvironment(), view.getSystemName(),
                        view.getMessageName(), view.getVersion(), view.getEtag(), view.getCheckedAt()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MessageVersionSchemas> findAll(String environment, String system) {
        return schemas.findAllOfSystem(environment, system).stream()
                .map(ArchitectureMessageSchemaRepositoryAdapter::toDomain)
                .toList();
    }

    /**
     * Stores one version, replacing the stored row where there is one.
     * <p>
     * A blind insert would be enough for the version this service expects to see once, and would fail the
     * import of the whole environment for the ones it does not: an upstream that lists a version twice, or
     * that answers with a spelling of the system or the message type other than the one the index gave. Both
     * collapse onto the same row here, and the unique index refuses the second insert.
     */
    @Override
    @Transactional
    public void store(MessageVersionSchemas version) {
        ArchitectureMessageSchemaEntity entity = schemas
                .findOne(version.environment(), version.system(), version.message(), version.version())
                .orElseGet(ArchitectureMessageSchemaEntity::new);
        entity.setEnvironment(version.environment());
        entity.setSystemName(version.system());
        entity.setMessageName(version.message());
        entity.setVersion(version.version());
        entity.setCompatibilityMode(version.compatibilityMode());
        entity.setCompatibleVersion(version.compatibleVersion());
        // Set on every path, including where the side is absent: a replacement of a row whose key schema the
        // upstream has since dropped has to clear those columns rather than keep the old ones.
        entity.setKeySchemaName(nameOf(version.key()));
        entity.setKeySchemaUrl(urlOf(version.key()));
        entity.setKeySchema(sourceOf(version.key()));
        entity.setValueSchemaName(nameOf(version.value()));
        entity.setValueSchemaUrl(urlOf(version.value()));
        entity.setValueSchema(sourceOf(version.value()));
        entity.setEtag(version.etag());
        entity.setReplicatedAt(version.replicatedAt());
        entity.setCheckedAt(version.replicatedAt());
        schemas.save(entity);
    }

    @Override
    @Transactional
    public void confirm(String environment, String system, String message, String version, Instant checkedAt) {
        schemas.confirm(environment, system, message, version, checkedAt);
    }

    /** Removes the named versions, one statement each and no select. */
    @Override
    @Transactional
    public void remove(Collection<MessageVersionRef> versions) {
        versions.forEach(version -> schemas.removeOne(version.environment(), version.system(),
                version.message(), version.version()));
    }

    private static MessageVersionSchemas toDomain(ArchitectureMessageSchemaEntity entity) {
        return new MessageVersionSchemas(entity.getEnvironment(), entity.getSystemName(),
                entity.getMessageName(), entity.getVersion(), entity.getCompatibilityMode(),
                entity.getCompatibleVersion(),
                schemaOf(entity.getKeySchemaName(), entity.getKeySchemaUrl(), entity.getKeySchema()),
                schemaOf(entity.getValueSchemaName(), entity.getValueSchemaUrl(), entity.getValueSchema()),
                entity.getEtag(), entity.getReplicatedAt());
    }

    /**
     * One side of a version, or null where the row carries none. Null rather than a record of three nulls: the
     * page asks whether there is a key schema, and an empty record would answer yes.
     */
    private static MessageSchema schemaOf(String name, String url, String resolved) {
        MessageSchema schema = new MessageSchema(name, url, resolved);
        return schema.isEmpty() ? null : schema;
    }

    private static String nameOf(MessageSchema schema) {
        return schema == null ? null : schema.schemaName();
    }

    private static String urlOf(MessageSchema schema) {
        return schema == null ? null : schema.schemaUrl();
    }

    private static String sourceOf(MessageSchema schema) {
        return schema == null ? null : schema.resolvedSchema();
    }
}
