package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.Slugs;
import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureSnapshot;
import ch.admin.bit.jeap.doc.domain.architecture.ComponentType;
import ch.admin.bit.jeap.doc.domain.architecture.ContractRole;
import ch.admin.bit.jeap.doc.domain.architecture.DatabaseSchemaReference;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessage;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessageVersion;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.architecture.MessageContract;
import ch.admin.bit.jeap.doc.domain.architecture.MessageKind;
import ch.admin.bit.jeap.doc.domain.architecture.OpenApiReference;
import ch.admin.bit.jeap.doc.domain.architecture.RelationKind;
import ch.admin.bit.jeap.doc.domain.architecture.RestApiOperation;
import ch.admin.bit.jeap.doc.domain.architecture.SystemRelation;
import ch.admin.bit.jeap.doc.domain.architecture.Team;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureArtifactRepository;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

/**
 * The imported architecture model, in this service's own database.
 * <p>
 * Two things about this adapter are load-bearing. Reading a landscape costs <b>one query per table</b> and none
 * per system, because a generation run reads the whole thing at once. Writing one <b>replaces everything</b> in
 * a single transaction, so a build never sees a landscape half of which is an hour older than the other half.
 * <p>
 * The second of those needs the read to be one snapshot, and that is what
 * {@link Isolation#REPEATABLE_READ} on {@link #read} is for - see the comment on it.
 */
@Component
@RequiredArgsConstructor
class ArchitectureModelRepositoryAdapter implements ArchitectureModelRepository {

    private final ArchitectureTeamJpaRepository teams;
    private final ArchitectureSystemJpaRepository systems;
    private final ArchitectureSystemAliasJpaRepository aliases;
    private final ArchitectureComponentJpaRepository components;
    private final ArchitectureRestApiJpaRepository restApis;
    private final ArchitectureRelationJpaRepository relations;
    private final ArchitectureMessageJpaRepository messages;
    private final ArchitectureMessageVersionJpaRepository messageVersions;
    private final ArchitectureMessageContractJpaRepository messageContracts;
    private final ArchitectureMessageContractVersionJpaRepository contractVersions;
    private final ArchitectureArtifactRepository artifacts;

    /**
     * <b>At repeatable read, and that is the whole point of this method.</b>
     * <p>
     * The ten statements below read one table each, every one of them keyed by the identifiers the statement
     * before it returned. At the default isolation of PostgreSQL - read committed - each of them takes its own
     * snapshot, and an import that commits between two of them replaces every row of the environment with rows
     * whose identifiers come fresh from a sequence. The reader is then holding systems that no longer exist,
     * the {@code where parent_id in (:ids)} queries below match nothing, and what comes back is a landscape of
     * systems with <b>no components, no messages and no relations at all</b> - silently, with no exception for
     * a build to fail on.
     * <p>
     * One snapshot for the whole method is the fix. It costs nothing: a repeatable-read transaction that only
     * reads neither blocks the import nor can be aborted by it, because a serialization failure at this level
     * needs a write and there is none here.
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ArchitectureSnapshot read(String environment) {
        List<ArchitectureSystemEntity> systemRows = systems.findByEnvironmentOrderBySlug(environment);
        if (systemRows.isEmpty()) {
            return ArchitectureSnapshot.empty();
        }
        Long[] systemIds = systemRows.stream().map(ArchitectureSystemEntity::getId).toArray(Long[]::new);

        Map<Long, Team> teamsById = teams.findByEnvironment(environment).stream()
                .collect(Collectors.toMap(ArchitectureTeamEntity::getId, ArchitectureModelRepositoryAdapter::team));
        Map<Long, List<String>> aliasesBySystem = groupBy(
                aliases.findBySystemIdInOrderByOrdinal(systemIds),
                ArchitectureSystemAliasEntity::getSystemId, ArchitectureSystemAliasEntity::getAlias);
        Map<Long, List<SystemRelation>> relationsBySystem = groupBy(
                relations.findBySystemIdInOrderByOrdinal(systemIds),
                ArchitectureRelationEntity::getSystemId, ArchitectureModelRepositoryAdapter::relation);

        List<ArchitectureComponentEntity> componentRows = components.findBySystemIdInOrderByOrdinal(systemIds);
        Map<Long, List<RestApiOperation>> restApisByComponent = groupBy(
                restApis.findByComponentIdInOrderByOrdinal(idsOf(componentRows,
                        ArchitectureComponentEntity::getId)),
                ArchitectureRestApiEntity::getComponentId,
                api -> new RestApiOperation(api.getMethod(), api.getPath()));
        Map<Long, List<DocumentedComponent>> componentsBySystem = groupBy(componentRows,
                ArchitectureComponentEntity::getSystemId,
                row -> component(row, teamsById, restApisByComponent));

        List<ArchitectureMessageEntity> messageRows = messages.findBySystemIdInOrderByOrdinal(systemIds);
        Long[] messageIds = idsOf(messageRows, ArchitectureMessageEntity::getId);
        Map<Long, List<String>> versionsByMessage = groupBy(
                messageVersions.findByMessageIdInOrderByOrdinal(messageIds),
                ArchitectureMessageVersionEntity::getMessageId, ArchitectureMessageVersionEntity::getVersion);
        List<ArchitectureMessageContractEntity> contractRows =
                messageContracts.findByMessageIdInOrderByOrdinal(messageIds);
        Map<Long, List<String>> versionsByContract = groupBy(
                contractVersions.findByContractIdInOrderByOrdinal(idsOf(contractRows,
                        ArchitectureMessageContractEntity::getId)),
                ArchitectureMessageContractVersionEntity::getContractId,
                ArchitectureMessageContractVersionEntity::getVersion);
        Map<Long, List<MessageContract>> contractsByMessage = groupBy(contractRows,
                ArchitectureMessageContractEntity::getMessageId,
                row -> contract(row, versionsByContract));
        Map<Long, List<DocumentedMessage>> messagesBySystem = groupBy(messageRows,
                ArchitectureMessageEntity::getSystemId, row -> message(row, versionsByMessage, contractsByMessage));

        List<DocumentedSystem> documented = systemRows.stream()
                .map(row -> new DocumentedSystem(
                        row.getName(),
                        row.getSlug(),
                        row.getDescription(),
                        aliasesBySystem.getOrDefault(row.getId(), List.of()),
                        teamsById.get(row.getTeamId()),
                        componentsBySystem.getOrDefault(row.getId(), List.of()),
                        relationsBySystem.getOrDefault(row.getId(), List.of()),
                        messagesBySystem.getOrDefault(row.getId(), List.of())))
                .toList();
        // Off the rows that were just read rather than out of a query of its own: one import writes the same
        // instant onto every system it inserts, so this is that import's timestamp and it comes for free. The
        // oldest of them, so that a landscape which somehow carried two would be reported as the older.
        Instant importedAt = systemRows.stream()
                .map(ArchitectureSystemEntity::getImportedAt)
                .filter(Objects::nonNull)
                .min(Instant::compareTo)
                .orElse(null);
        return new ArchitectureSnapshot(ArchitectureModel.of(documented), importedAt);
    }

    @Override
    @Transactional
    public void replace(String environment, ArchitectureModel model, Instant importedAt) {
        // The systems first: everything below them is removed by the cascades of the schema. The teams after,
        // because both a system and a component reference one, and by now neither is left.
        systems.deleteByEnvironment(environment);
        teams.deleteByEnvironment(environment);
        systems.flush();

        Map<String, Long> teamIds = insertTeams(environment, model);
        for (DocumentedSystem system : model.systems()) {
            Long systemId = insertSystem(environment, system, teamIds, importedAt);
            insertAliases(systemId, system);
            insertComponents(systemId, system, teamIds);
            insertRelations(systemId, system);
            insertMessages(systemId, system);
        }
        systems.flush();

        // The artifacts do not point into the model, so nothing removed them with it. Whatever names a system
        // or component this landscape no longer has is now unreachable.
        artifacts.removeOrphans(environment);
    }

    private Map<String, Long> insertTeams(String environment, ArchitectureModel model) {
        Map<String, Team> distinct = new LinkedHashMap<>();
        for (DocumentedSystem system : model.systems()) {
            addTeam(distinct, system.team());
            system.components().forEach(component -> addTeam(distinct, component.team()));
        }
        Map<String, Long> ids = new HashMap<>();
        distinct.forEach((name, team) -> {
            ArchitectureTeamEntity entity = new ArchitectureTeamEntity();
            entity.setEnvironment(environment);
            entity.setName(team.name());
            entity.setContactAddress(team.contactAddress());
            entity.setJiraLink(team.jiraLink());
            entity.setConfluenceLink(team.confluenceLink());
            ids.put(name, teams.save(entity).getId());
        });
        return ids;
    }

    private static void addTeam(Map<String, Team> distinct, Team team) {
        if (team != null && team.name() != null) {
            distinct.putIfAbsent(team.name(), team);
        }
    }

    private Long insertSystem(String environment, DocumentedSystem system, Map<String, Long> teamIds,
                              Instant importedAt) {
        ArchitectureSystemEntity entity = new ArchitectureSystemEntity();
        entity.setEnvironment(environment);
        entity.setName(system.name());
        entity.setSlug(system.slug());
        entity.setDescription(system.description());
        entity.setTeamId(teamId(teamIds, system.team()));
        entity.setImportedAt(importedAt);
        return systems.save(entity).getId();
    }

    private void insertAliases(Long systemId, DocumentedSystem system) {
        int ordinal = 0;
        for (String alias : system.aliases()) {
            ArchitectureSystemAliasEntity entity = new ArchitectureSystemAliasEntity();
            entity.setSystemId(systemId);
            entity.setOrdinal(ordinal++);
            entity.setAlias(alias);
            aliases.save(entity);
        }
    }

    private void insertComponents(Long systemId, DocumentedSystem system, Map<String, Long> teamIds) {
        int ordinal = 0;
        for (DocumentedComponent component : system.components()) {
            ArchitectureComponentEntity entity = new ArchitectureComponentEntity();
            entity.setSystemId(systemId);
            entity.setOrdinal(ordinal++);
            entity.setName(component.name());
            entity.setSlug(component.slug());
            entity.setDescription(component.description());
            entity.setType(component.type().name());
            entity.setTeamId(teamId(teamIds, component.team()));
            entity.setImporter(component.importer());
            if (component.lastSeen() != null) {
                entity.setLastSeen(component.lastSeen().toInstant());
                entity.setLastSeenZone(component.lastSeen().getZone().getId());
            }
            if (component.openApi() != null) {
                entity.setOpenApiVersion(component.openApi().version());
                entity.setOpenApiServerUrl(component.openApi().serverUrl());
                entity.setOpenApiContentUrl(component.openApi().contentUrl());
                entity.setOpenApiSwaggerUrl(component.openApi().swaggerUrl());
            }
            if (component.databaseSchema() != null) {
                entity.setDbSchemaVersion(component.databaseSchema().schemaVersion());
                entity.setDbSchemaContentUrl(component.databaseSchema().contentUrl());
            }
            Long componentId = components.save(entity).getId();
            insertRestApis(componentId, component);
        }
    }

    private void insertRestApis(Long componentId, DocumentedComponent component) {
        int ordinal = 0;
        for (RestApiOperation operation : component.restApis()) {
            ArchitectureRestApiEntity entity = new ArchitectureRestApiEntity();
            entity.setComponentId(componentId);
            entity.setOrdinal(ordinal++);
            entity.setMethod(operation.method());
            entity.setPath(operation.path());
            restApis.save(entity);
        }
    }

    private void insertRelations(Long systemId, DocumentedSystem system) {
        int ordinal = 0;
        for (SystemRelation relation : system.relations()) {
            ArchitectureRelationEntity entity = new ArchitectureRelationEntity();
            entity.setSystemId(systemId);
            entity.setOrdinal(ordinal++);
            entity.setKind(relation.kind().name());
            entity.setConsumerSystem(relation.consumerSystem());
            entity.setConsumer(relation.consumer());
            entity.setProviderSystem(relation.providerSystem());
            entity.setProvider(relation.provider());
            entity.setMessageType(relation.messageType());
            entity.setMethod(relation.method());
            entity.setPath(relation.path());
            entity.setPactUrl(relation.pactUrl());
            relations.save(entity);
        }
    }

    private void insertMessages(Long systemId, DocumentedSystem system) {
        int ordinal = 0;
        for (DocumentedMessage message : system.messages()) {
            ArchitectureMessageEntity entity = new ArchitectureMessageEntity();
            entity.setSystemId(systemId);
            entity.setOrdinal(ordinal++);
            entity.setName(message.name());
            entity.setSlug(message.slug());
            entity.setKind(message.kind().name());
            entity.setScope(message.scope());
            entity.setTopic(message.topic());
            entity.setDescription(message.description());
            entity.setDescriptorUrl(message.descriptorUrl());
            entity.setDocumentationUrl(message.documentationUrl());
            Long messageId = messages.save(entity).getId();
            insertMessageVersions(messageId, message);
            insertContracts(messageId, message);
        }
    }

    private void insertMessageVersions(Long messageId, DocumentedMessage message) {
        int ordinal = 0;
        for (DocumentedMessageVersion version : message.versions()) {
            ArchitectureMessageVersionEntity entity = new ArchitectureMessageVersionEntity();
            entity.setMessageId(messageId);
            entity.setOrdinal(ordinal++);
            entity.setVersion(version.version());
            messageVersions.save(entity);
        }
    }

    private void insertContracts(Long messageId, DocumentedMessage message) {
        int ordinal = 0;
        for (MessageContract contract : message.contracts()) {
            ArchitectureMessageContractEntity entity = new ArchitectureMessageContractEntity();
            entity.setMessageId(messageId);
            entity.setOrdinal(ordinal++);
            entity.setRole(contract.role().name());
            entity.setComponentName(contract.component());
            entity.setSystemName(contract.system());
            entity.setTopic(contract.topic());
            Long contractId = messageContracts.save(entity).getId();
            int versionOrdinal = 0;
            for (String version : contract.versions()) {
                ArchitectureMessageContractVersionEntity versionEntity =
                        new ArchitectureMessageContractVersionEntity();
                versionEntity.setContractId(contractId);
                versionEntity.setOrdinal(versionOrdinal++);
                versionEntity.setVersion(version);
                contractVersions.save(versionEntity);
            }
        }
    }

    private static Long teamId(Map<String, Long> teamIds, Team team) {
        return team == null || team.name() == null ? null : teamIds.get(team.name());
    }

    private static Team team(ArchitectureTeamEntity entity) {
        return new Team(entity.getName(), entity.getContactAddress(), entity.getJiraLink(),
                entity.getConfluenceLink());
    }

    private static DocumentedComponent component(ArchitectureComponentEntity row, Map<Long, Team> teamsById,
                                                 Map<Long, List<RestApiOperation>> restApisByComponent) {
        ZonedDateTime lastSeen = row.getLastSeen() == null ? null
                : row.getLastSeen().atZone(zoneOf(row.getLastSeenZone()));
        OpenApiReference openApi = row.getOpenApiVersion() == null && row.getOpenApiContentUrl() == null ? null
                : new OpenApiReference(row.getOpenApiVersion(), row.getOpenApiServerUrl(),
                row.getOpenApiContentUrl(), row.getOpenApiSwaggerUrl());
        DatabaseSchemaReference schema = row.getDbSchemaVersion() == null && row.getDbSchemaContentUrl() == null
                ? null : new DatabaseSchemaReference(row.getDbSchemaVersion(), row.getDbSchemaContentUrl());
        return new DocumentedComponent(row.getName(), row.getSlug(), row.getDescription(),
                storedEnum(ComponentType.class, row.getType(), ComponentType.UNKNOWN), teamsById.get(row.getTeamId()), row.getImporter(), lastSeen,
                restApisByComponent.getOrDefault(row.getId(), List.of()), openApi, schema);
    }

    /**
     * The zone the architecture repository meant, or UTC when the row predates it being stored. An unknown zone
     * id must not stop a whole landscape from being read.
     */
    private static ZoneId zoneOf(String zoneId) {
        try {
            return zoneId == null ? ZoneId.of("UTC") : ZoneId.of(zoneId);
        } catch (RuntimeException unknownZone) {
            return ZoneId.of("UTC");
        }
    }

    private static SystemRelation relation(ArchitectureRelationEntity row) {
        return new SystemRelation(storedEnum(RelationKind.class, row.getKind(), RelationKind.OTHER), row.getConsumerSystem(), row.getConsumer(),
                row.getProviderSystem(), row.getProvider(), row.getMessageType(), row.getMethod(), row.getPath(),
                row.getPactUrl());
    }

    private static DocumentedMessage message(ArchitectureMessageEntity row,
                                             Map<Long, List<String>> versionsByMessage,
                                             Map<Long, List<MessageContract>> contractsByMessage) {
        return new DocumentedMessage(row.getName(), slugOf(row),
                storedEnum(MessageKind.class, row.getKind(), MessageKind.EVENT), row.getScope(), row.getTopic(),
                row.getDescription(), row.getDescriptorUrl(), row.getDocumentationUrl(),
                // The stored model knows the version strings; the schemas behind them live in their own table
                // and are joined in per system while a page is written.
                versionsByMessage.getOrDefault(row.getId(), List.<String>of()).stream()
                        .map(DocumentedMessageVersion::of).toList(),
                contractsByMessage.getOrDefault(row.getId(), List.of()));
    }

    /**
     * The slug as it was stored - or derived from the name for a row written before the slug was, so that a
     * site can be built between the deployment and the first import after it.
     */
    private static String slugOf(ArchitectureMessageEntity row) {
        return row.getSlug() != null ? row.getSlug() : Slugs.toMessageSlug(row.getName());
    }

    private static MessageContract contract(ArchitectureMessageContractEntity row,
                                            Map<Long, List<String>> versionsByContract) {
        return new MessageContract(storedEnum(ContractRole.class, row.getRole(), ContractRole.UNKNOWN), row.getComponentName(), row.getSystemName(),
                row.getTopic(), versionsByContract.getOrDefault(row.getId(), List.of()));
    }

    /**
     * An enum written by this adapter, read back by its own name.
     * <p>
     * Not through the {@code of} of the enum itself: those translate the <b>architecture repository's</b>
     * vocabulary - {@code PUBLISHER}, {@code EVENT_RELATION} - and would read every value this adapter stored
     * as the unknown one. A constant a newer version wrote falls back rather than failing a whole landscape.
     */
    private static <E extends Enum<E>> E storedEnum(Class<E> type, String stored, E fallback) {
        if (stored == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, stored);
        } catch (IllegalArgumentException unknownToThisVersion) {
            return fallback;
        }
    }

    /**
     * The identifiers of a table's rows, for the query that reads the table below it. They come from a
     * sequence and are never null by the time anything is read back.
     * <p>
     * An array rather than a list, because that is what the queries below bind: one parameter holding every
     * identifier, instead of one parameter each - see any of the {@code …JpaRepository} interfaces.
     */
    private static <E> Long[] idsOf(Collection<E> rows, ToLongFunction<E> id) {
        return rows.stream().mapToLong(id).boxed().toArray(Long[]::new);
    }

    /**
     * Groups the rows of one table by the id of their parent, keeping the order the query returned them in.
     */
    private static <E, V> Map<Long, List<V>> groupBy(Collection<E> rows, ToLongFunction<E> parentId,
                                                     Function<E, V> value) {
        Map<Long, List<V>> grouped = new HashMap<>();
        for (E row : rows) {
            grouped.computeIfAbsent(parentId.applyAsLong(row), key -> new ArrayList<>()).add(value.apply(row));
        }
        return grouped;
    }
}
