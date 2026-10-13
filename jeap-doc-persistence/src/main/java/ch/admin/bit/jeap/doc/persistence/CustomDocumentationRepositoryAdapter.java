package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.custom.CustomDocumentation;
import ch.admin.bit.jeap.doc.domain.custom.CustomPage;
import ch.admin.bit.jeap.doc.domain.custom.CustomProvenance;
import ch.admin.bit.jeap.doc.domain.custom.CustomSet;
import ch.admin.bit.jeap.doc.domain.custom.CustomSetKey;
import ch.admin.bit.jeap.doc.domain.custom.CustomSubject;
import ch.admin.bit.jeap.doc.domain.custom.SubjectDeployment;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationRepository;
import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
    private final CustomSubjectDeploymentJpaRepository deployments;

    /**
     * <b>The order is what makes two uploads of one set safe.</b> The row is written before its pages, in one
     * statement that takes the row's lock - see {@link CustomSetJpaRepository#upsert}. An upload that arrives
     * while another is writing this set therefore waits there, before it deletes anything, rather than
     * deleting the pages the first one has not committed yet and leaving the set carrying both.
     * <p>
     * <b>The upload is recorded against the subject's deployment first</b>, before the set's row is locked:
     * the system's row, then the component's. Every removal takes the deployment rows it needs in that same
     * order before it touches any set, which is what keeps an upload and a removal - or two removals - from
     * waiting on each other.
     */
    @Override
    @Transactional
    public Replaced replace(CustomSet set) {
        CustomSetKey key = set.key();
        Instant uploadedAt = set.provenance().uploadedAt();
        deployments.recordUpload(key.site(), key.system(), null, uploadedAt);
        if (key.kind() == SubjectKind.COMPONENT) {
            deployments.recordUpload(key.site(), key.system(), key.name(), uploadedAt);
        }
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
                key.template(), key.location(), key.topic(), set.label(), set.revision(), set.objectKey(),
                set.sha256(), set.sizeInBytes(), provenance.sourceRepository(), provenance.sourceRef(),
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
    @Transactional(readOnly = true)
    public List<CustomSet> micrositesOf(String site) {
        // Without their pages: a microsite has none by design, and the rows it would join are a team's
        // markdown.
        return sets.findMicrosites(site).stream()
                .map(entity -> CustomDocumentationMapper.toDomain(entity, List.of()))
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
        deployments.lockState(subject.site(), subject.system(), null);
        if (subject.kind() == SubjectKind.COMPONENT) {
            deployments.lockState(subject.site(), subject.system(), subject.name());
        }
        List<CustomSetEntity> found = sets.findSubject(subject.site(), subject.kind(), subject.system(),
                subject.name());
        for (CustomSetEntity entity : found) {
            pages.deleteBySetId(entity.getId());
        }
        sets.deleteAll(found);
        if (subject.kind() == SubjectKind.COMPONENT) {
            deployments.deleteComponent(subject.site(), subject.system(), subject.name());
        }
        if (!found.isEmpty()) {
            log.info("The {} sets of the {} {} of the system {} were removed.", found.size(), subject.kind(),
                    subject.slug(), subject.system());
        }
        return found.size();
    }

    @Override
    @Transactional
    public List<String> removeSystem(String site, String system) {
        deployments.lockSystem(site, system);
        List<String> objectKeys = deleteSets(sets.findBySiteAndSystemName(site, system));
        deployments.deleteSystem(site, system);
        if (!objectKeys.isEmpty()) {
            log.info("Every documentation set of the system {} on the site {} was removed: {} set(s).",
                    system, site, objectKeys.size());
        }
        return objectKeys;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SubjectDeployment> deploymentsOf(String site) {
        return deployments.findBySiteOrderBySystemNameAscComponentAsc(site).stream()
                .map(CustomSubjectDeploymentEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public boolean markDeployed(String site, String system, String component, Instant at) {
        return deployments.markDeployed(site, system, component, at) > 0;
    }

    @Override
    @Transactional
    public List<String> removeDepartedComponent(String site, String system, String component, Instant at) {
        deployments.lockState(site, system, null);
        if (!isStillDeployed(site, system, component)) {
            return List.of();
        }
        List<String> objectKeys = deleteSets(sets.findSubject(site, SubjectKind.COMPONENT, system, component));
        deployments.undeployComponent(site, system, component, at);
        log.info("The component {} of the system {} is no longer deployed; its {} documentation set(s) on the "
                 + "site {} were removed.", component, system, objectKeys.size(), site);
        return objectKeys;
    }

    @Override
    @Transactional
    public List<String> removeDepartedSystem(String site, String system, Instant at) {
        if (!isStillDeployed(site, system, null)) {
            return List.of();
        }
        // Its components' rows too, before their sets, in the order an upload takes them. A component awaiting
        // its first deployment keeps the system: an upload may have arrived since the model was read.
        if (deployments.lockSystem(site, system).contains(SubjectDeployment.State.AWAITING_DEPLOYMENT.name())) {
            return List.of();
        }
        // A library is deployed nowhere, so a system's deployment says nothing about it: its documentation stays.
        List<String> objectKeys = deleteSets(
                sets.findBySiteAndSystemNameAndKindNot(site, system, SubjectKind.LIBRARY));
        deployments.undeploySystem(site, system, at);
        log.info("The system {} has no component deployed any more; its {} documentation set(s) on the site {} "
                 + "were removed, its libraries' were kept.", system, objectKeys.size(), site);
        return objectKeys;
    }

    /** Whether a system or component is marked deployed, locking its row until the transaction ends. */
    private boolean isStillDeployed(String site, String system, String component) {
        return deployments.lockState(site, system, component)
                .map(SubjectDeployment.State.DEPLOYED.name()::equals)
                .orElse(false);
    }

    /** Deletes the sets and their pages, and answers the objects they lay in. */
    private List<String> deleteSets(List<CustomSetEntity> found) {
        List<String> objectKeys = found.stream().map(CustomSetEntity::getObjectKey).toList();
        for (CustomSetEntity entity : found) {
            pages.deleteBySetId(entity.getId());
        }
        sets.deleteAll(found);
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
