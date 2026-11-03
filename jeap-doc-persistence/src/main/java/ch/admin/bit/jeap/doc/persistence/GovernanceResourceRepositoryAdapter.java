package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.governance.GovernanceResource;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceResourceKind;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceResourceRef;
import ch.admin.bit.jeap.doc.domain.port.GovernanceResourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The resources read from the Governance Service's {@code /docs-api}.
 * <p>
 * A single resource is its own transaction, but what one import of an index changes goes through
 * {@link #replace}, in one: an index and the resources it lists must never be visible in two different states.
 */
@Component
@RequiredArgsConstructor
class GovernanceResourceRepositoryAdapter implements GovernanceResourceRepository {

    private final GovernanceResourceJpaRepository resources;

    @Override
    @Transactional(readOnly = true)
    public List<GovernanceResourceRef> findRefs(GovernanceResourceKind kind) {
        return resources.findByKindOrderByResourceKeyAsc(kind.name()).stream()
                .map(view -> new GovernanceResourceRef(GovernanceResourceKind.valueOf(view.getKind()),
                        view.getResourceKey(), view.getEtag()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GovernanceResource> find(GovernanceResourceKind kind, String key) {
        return resources.findOne(kind.name(), key).map(GovernanceResourceRepositoryAdapter::resource);
    }

    @Override
    @Transactional
    public void store(GovernanceResource resource) {
        GovernanceResourceEntity entity = resources.findOne(resource.kind().name(), resource.key())
                .orElseGet(GovernanceResourceEntity::new);
        entity.setKind(resource.kind().name());
        entity.setResourceKey(resource.key());
        entity.setEtag(resource.etag());
        entity.setContent(resource.content());
        entity.setSizeInBytes(resource.sizeInBytes());
        entity.setImportedAt(resource.importedAt());
        entity.setCheckedAt(resource.importedAt());
        resources.save(entity);
    }

    // Repeatable read, because the default of PostgreSQL gives every statement its own snapshot.
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public <T> T readConsistently(Supplier<T> reads) {
        return reads.get();
    }

    @Override
    @Transactional
    public void replace(GovernanceResource index, Collection<GovernanceResource> toStore,
                        Collection<GovernanceResourceRef> removed) {
        remove(removed);
        toStore.forEach(this::store);
        store(index);
    }

    @Override
    @Transactional
    public void confirm(GovernanceResourceKind kind, String key, Instant checkedAt) {
        resources.confirm(kind.name(), key, checkedAt);
    }

    @Override
    @Transactional
    public void remove(Collection<GovernanceResourceRef> refs) {
        for (GovernanceResourceRef ref : refs) {
            resources.removeOne(ref.kind().name(), ref.key());
        }
    }

    private static GovernanceResource resource(GovernanceResourceEntity entity) {
        return new GovernanceResource(GovernanceResourceKind.valueOf(entity.getKind()), entity.getResourceKey(),
                entity.getEtag(), entity.getContent(), entity.getSizeInBytes(), entity.getImportedAt());
    }
}
