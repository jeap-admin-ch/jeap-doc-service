package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.custom.CustomDocumentation;
import ch.admin.bit.jeap.doc.domain.custom.CustomPage;
import ch.admin.bit.jeap.doc.domain.custom.CustomProvenance;
import ch.admin.bit.jeap.doc.domain.custom.CustomSet;
import ch.admin.bit.jeap.doc.domain.custom.CustomSetKey;
import ch.admin.bit.jeap.doc.domain.custom.CustomSubject;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The documentation sets, on PostgreSQL.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
class CustomDocumentationRepositoryAdapter implements CustomDocumentationRepository {

    private final CustomSetJpaRepository sets;
    private final CustomPageJpaRepository pages;

    /**
     * <b>The order is what makes two uploads of one set safe.</b> The row is written before its pages, in one
     * statement that takes the row's lock - see {@link CustomSetJpaRepository#upsert}. An upload that arrives
     * while another is writing this set therefore waits there, before it deletes anything, rather than
     * deleting the pages the first one has not committed yet and leaving the set carrying both.
     */
    @Override
    @Transactional
    public Replaced replace(CustomSet set) {
        CustomSetKey key = set.key();
        // Under the lock, because it is about to be overwritten: the row is the only thing that names the
        // object the set used to lie in, and once it names the new one that object cannot be found again.
        Optional<CustomSetEntity> existing = sets.findForUpdate(key.site(), key.kind(), key.system(),
                key.name(), key.sourceFormat(), key.template(), key.location(), key.topic());
        // Whether there was a row, and separately which object it named: a set replaced by the same upload
        // and attempt writing twice names the same object, and is still not published for the first time.
        boolean isNew = existing.isEmpty();
        Optional<String> previousObjectKey = existing
                .map(CustomSetEntity::getObjectKey)
                .filter(objectKey -> !objectKey.equals(set.objectKey()));
        CustomProvenance provenance = set.provenance();
        sets.upsert(key.site(), key.kind().name(), key.system(), key.name(), key.sourceFormat().name(),
                key.template(), key.location(), key.topic(), set.revision(), set.objectKey(), set.sha256(),
                set.sizeInBytes(), provenance.sourceRepository(), provenance.sourceRef(),
                provenance.sourceRevision(), provenance.sourceTimestamp(), provenance.version(),
                provenance.uploadedAt());
        CustomSetEntity stored = findEntity(key).orElseThrow(() -> new IllegalStateException(
                "The documentation set %s was written and cannot be read back.".formatted(key)));
        pages.deleteBySetId(stored.getId());
        List<CustomPageEntity> written = pages.saveAll(set.pages().stream()
                .map(page -> CustomDocumentationMapper.toEntity(stored.getId(), page))
                .toList());
        log.info("The documentation of the {} {} of the system {} on the site {} is {}: {} file(s), {} bytes.",
                key.kind(), set.subject().slug(), key.system(), key.site(),
                isNew ? "published for the first time" : "replaced", written.size(), set.sizeInBytes());
        return new Replaced(CustomDocumentationMapper.toDomain(stored, written), previousObjectKey);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomSet> find(CustomSetKey key) {
        return findEntity(key).map(entity ->
                CustomDocumentationMapper.toDomain(entity, pagesOf(List.of(entity.getId()))
                        .getOrDefault(entity.getId(), List.of())));
    }

    @Override
    @Transactional(readOnly = true)
    public CustomDocumentation of(String site, String system) {
        List<CustomSetEntity> found = sets.findBySiteAndSystemNameOrderByIdAsc(site, system);
        return found.isEmpty() ? CustomDocumentation.nothing() : new CustomDocumentation(withPages(found));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomSubject> subjectsOf(String site) {
        // Sorted here rather than in the query: a system has no name of its own, and the order wanted puts it
        // in front of its components rather than where the database sorts a null.
        return sets.subjectsOf(site).stream()
                .sorted(Comparator.comparing(CustomSubject::system)
                        .thenComparing(subject -> subject.name() == null ? "" : subject.name()))
                .toList();
    }

    @Override
    @Transactional
    public boolean remove(CustomSetKey key) {
        Optional<CustomSetEntity> entity = findEntity(key);
        entity.ifPresent(found -> {
            pages.deleteBySetId(found.getId());
            sets.delete(found);
            log.info("The documentation set {} was removed.", key);
        });
        return entity.isPresent();
    }

    @Override
    @Transactional
    public int removeSubject(CustomSubject subject) {
        List<CustomSetEntity> found = sets.findSubject(subject.site(), subject.kind(), subject.system(),
                subject.name());
        for (CustomSetEntity entity : found) {
            pages.deleteBySetId(entity.getId());
        }
        sets.deleteAll(found);
        if (!found.isEmpty()) {
            log.info("The {} sets of the {} {} of the system {} were removed.", found.size(), subject.kind(),
                    subject.slug(), subject.system());
        }
        return found.size();
    }

    @Override
    @Transactional
    public List<String> removeSystem(String site, String system) {
        List<CustomSetEntity> found = sets.findBySiteAndSystemName(site, system);
        List<String> objectKeys = found.stream().map(CustomSetEntity::getObjectKey).toList();
        for (CustomSetEntity entity : found) {
            pages.deleteBySetId(entity.getId());
        }
        sets.deleteAll(found);
        if (!found.isEmpty()) {
            log.info("Every documentation set of the system {} on the site {} was removed: {} set(s).",
                    system, site, found.size());
        }
        return objectKeys;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> allObjectKeys() {
        return sets.objectKeys();
    }

    private Optional<CustomSetEntity> findEntity(CustomSetKey key) {
        return sets.find(key.site(), key.kind(), key.system(), key.name(), key.sourceFormat(), key.template(),
                key.location(), key.topic());
    }

    /** The sets with their files joined on, in one further query rather than one per set. */
    private List<CustomSet> withPages(List<CustomSetEntity> found) {
        Map<Long, List<CustomPageEntity>> byId = pagesOf(found.stream().map(CustomSetEntity::getId).toList());
        List<CustomSet> all = new ArrayList<>();
        for (CustomSetEntity entity : found) {
            all.add(CustomDocumentationMapper.toDomain(entity,
                    byId.getOrDefault(entity.getId(), List.of())));
        }
        return all;
    }

    private Map<Long, List<CustomPageEntity>> pagesOf(List<Long> setIds) {
        Map<Long, List<CustomPageEntity>> byId = new LinkedHashMap<>();
        for (CustomPageEntity page : pages.findBySetIdIn(setIds.toArray(Long[]::new))) {
            byId.computeIfAbsent(page.getSetId(), id -> new ArrayList<>()).add(page);
        }
        return byId;
    }
}
